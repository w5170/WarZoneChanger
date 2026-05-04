package com.warzone.changer.packet

import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

class PacketHandler(
    private val tunInput: FileInputStream,
    private val tunOutput: FileOutputStream,
    private val targetIps: Set<String>,
    private val fakeHttpResponse: ByteArray,
    private val protectFn: (Socket) -> Boolean,
    private val protectDg: (DatagramSocket) -> Boolean
) {
    companion object {
        private const val TAG = "PH"
        private const val BUF = 65535
    }

    @Volatile private var running = false
    private val tcpSessions = ConcurrentHashMap<String, TcpSess>()

    data class TcpSess(
        var state: Int = 0, // 0=connecting, 1=syn_sent, 2=established, 3=closing
        var clientSeq: Long = 0,
        var ourSeq: Long = 0,
        var sock: Socket? = null,
        var sin: InputStream? = null,
        var sout: OutputStream? = null,
        val isTarget: Boolean = false,
        val sip: String = "", val sp: Int = 0,
        val dip: String = "", val dp: Int = 0
    )

    fun start() {
        running = true
        Thread({ readLoop() }, "tun").start()
        Log.i(TAG, "Started targets=$targetIps")
    }

    fun stop() {
        running = false
        for ((_, s) in tcpSessions) try { s.sock?.close() } catch (_: Exception) {}
        tcpSessions.clear()
    }

    private fun readLoop() {
        val buf = ByteArray(BUF)
        while (running) {
            try {
                val n = tunInput.read(buf)
                if (n >= 20) dispatch(buf.copyOf(n))
            } catch (e: IOException) {
                if (running) Log.e(TAG, "read", e)
                // 不退出循环，继续尝试
                try { Thread.sleep(10) } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.e(TAG, "unexpected error in readLoop", e)
                try { Thread.sleep(10) } catch (_: Exception) {}
            }
        }
    }

    private fun dispatch(d: ByteArray) {
        try {
            if ((d[0].toInt() shr 4) != 4) return
            when (d[9].toInt() and 0xFF) {
                17 -> handleDns(d)
                6 -> handleTcp(d)
            }
        } catch (e: Exception) {
            Log.e(TAG, "dispatch error", e)
        }
    }

    // ==================== DNS ====================
    private fun handleDns(d: ByteArray) {
        val ih = (d[0].toInt() and 0xF) * 4
        if (d.size < ih + 8) return
        val dp = u16(d, ih + 2)
        if (dp != 53) return

        val srcIp = ip(d, 12); val dstIp = ip(d, 16)
        val sp = u16(d, ih)
        val query = d.copyOfRange(ih + 8, d.size)

        Thread({
            try {
                val ds = DatagramSocket()
                protectDg(ds)
                ds.soTimeout = 3000
                ds.send(DatagramPacket(query, query.size, InetAddress.getByName("8.8.8.8"), 53))
                val resp = ByteArray(1024)
                val pkt = DatagramPacket(resp, resp.size)
                ds.receive(pkt); ds.close()
                writeUdp(dstIp, sp, srcIp, 53, resp.copyOf(pkt.length))
            } catch (_: Exception) {}
        }, "dns").start()
    }

    private fun writeUdp(sip: String, sp: Int, dip: String, dp: Int, payload: ByteArray) {
        val ih = 20; val uh = 8; val total = ih + uh + payload.size
        val p = ByteArray(total)
        p[0] = 0x45.toByte(); w16(p, 2, total); p[6] = 0x40.toByte(); p[8] = 64; p[9] = 17
        wIp(p, 12, sip); wIp(p, 16, dip); ipCsum(p)
        w16(p, ih, sp); w16(p, ih + 2, dp); w16(p, ih + 4, uh + payload.size)
        System.arraycopy(payload, 0, p, ih + uh, payload.size)
        writeTun(p)
    }

    // ==================== TCP ====================
    private fun handleTcp(d: ByteArray) {
        val ih = (d[0].toInt() and 0xF) * 4
        if (d.size < ih + 20) return

        val srcIp = ip(d, 12); val dstIp = ip(d, 16)
        val sp = u16(d, ih); val dp = u16(d, ih + 2)
        val tcp = d.copyOfRange(ih, d.size)
        val seq = u32(tcp, 4); val ackN = u32(tcp, 8)
        val th = ((tcp[12].toInt() and 0xF0) shr 4) * 4
        val flags = tcp[13].toInt() and 0x3F
        val payload = if (th < tcp.size) tcp.copyOfRange(th, tcp.size) else ByteArray(0)

        val syn = (flags and 0x02) != 0; val ack = (flags and 0x10) != 0
        val fin = (flags and 0x01) != 0; val rst = (flags and 0x04) != 0

        val key = "$srcIp:$sp>$dstIp:$dp"
        val isTarget = dstIp in targetIps && dp == 80

        if (rst) { closeSession(key); return }

        // SYN
        if (syn && !ack) {
            if (tcpSessions.containsKey(key)) return // 重复SYN，忽略
            val ourSeq = 100000L + (Math.random() * 900000).toLong()
            val sess = TcpSess(0, seq, ourSeq, isTarget = isTarget,
                sip = srcIp, sp = sp, dip = dstIp, dp = dp)
            tcpSessions[key] = sess

            if (isTarget) {
                // 目标：直接发SYN-ACK
                sess.state = 1
                sendTcp(dstIp, dp, srcIp, sp, ourSeq, seq + 1, 0x12)
                sess.ourSeq++
            } else {
                // 非目标：先连接真实服务器
                sess.state = 0
                Thread({ connectAndSynAck(sess, key) }, "conn-$key").start()
            }
            return
        }

        val sess = tcpSessions[key] ?: return

        // ACK（握手第三步）
        if (ack && !syn && payload.isEmpty()) {
            if (sess.state == 1) {
                sess.state = 2 // established
                sess.clientSeq = seq
            } else if (sess.state == 0) {
                // 服务器还没连上，记录客户端seq
                sess.clientSeq = seq
            }
            return
        }

        // 有数据
        if (payload.isNotEmpty()) {
            sess.clientSeq = seq + payload.size
            if (isTarget && sess.state == 2) {
                handleTargetHttp(sess, key, payload, srcIp, sp, dstIp, dp)
            } else if (!isTarget && sess.state == 2) {
                // 转发到真实服务器
                sendTcp(dstIp, dp, srcIp, sp, sess.ourSeq, sess.clientSeq, 0x10)
                try { sess.sout?.write(payload); sess.sout?.flush() } catch (_: Exception) {}
            } else {
                // 还没established，只ACK
                sendTcp(dstIp, dp, srcIp, sp, sess.ourSeq, sess.clientSeq, 0x10)
            }
            return
        }

        // FIN
        if (fin) {
            sess.clientSeq = seq + 1
            sendTcp(dstIp, dp, srcIp, sp, sess.ourSeq, sess.clientSeq, 0x11)
            closeSession(key)
        }
    }

    private fun connectAndSynAck(sess: TcpSess, key: String) {
        try {
            val s = Socket()
            protectFn(s)
            s.connect(InetSocketAddress(sess.dip, sess.dp), 8000)
            sess.sock = s; sess.sin = s.getInputStream(); sess.sout = s.getOutputStream()
            // 连接成功，发SYN-ACK
            sess.state = 1
            sendTcp(sess.dip, sess.dp, sess.sip, sess.sp, sess.ourSeq, sess.clientSeq + 1, 0x12)
            sess.ourSeq++
            // 开始从服务器读数据
            startServerReader(sess, key)
        } catch (e: Exception) {
            Log.w(TAG, "connect fail ${sess.dip}:${sess.dp} ${e.message}")
            // 发RST
            sendTcp(sess.dip, sess.dp, sess.sip, sess.sp, 0, sess.clientSeq + 1, 0x04)
            tcpSessions.remove(key)
        }
    }

    private fun startServerReader(sess: TcpSess, key: String) {
        Thread({
            try {
                val buf = ByteArray(8192)
                while (running && sess.state >= 1) {
                    val n = sess.sin?.read(buf) ?: -1
                    if (n <= 0) break
                    val payload = buf.copyOf(n)
                    sendTcp(sess.dip, sess.dp, sess.sip, sess.sp,
                        sess.ourSeq, sess.clientSeq, 0x18, payload)
                    sess.ourSeq += n
                }
            } catch (_: Exception) {}
            finally {
                if (sess.state >= 1) {
                    sendTcp(sess.dip, sess.dp, sess.sip, sess.sp,
                        sess.ourSeq, sess.clientSeq, 0x11)
                    sess.state = 3
                    tcpSessions.remove(key)
                }
                try { sess.sock?.close() } catch (_: Exception) {}
            }
        }, "srv-$key").start()
    }

    private fun handleTargetHttp(sess: TcpSess, key: String, payload: ByteArray,
                                  sip: String, sp: Int, dip: String, dp: Int) {
        val http = String(payload, Charsets.US_ASCII)
        if (http.startsWith("GET ") || http.startsWith("POST ") ||
            http.startsWith("HEAD ") || http.startsWith("PUT ")) {
            Log.i(TAG, "Intercepted! $key")
            sendTcp(dip, dp, sip, sp, sess.ourSeq, sess.clientSeq, 0x10)
            sendTcp(dip, dp, sip, sp, sess.ourSeq, sess.clientSeq, 0x18, fakeHttpResponse)
            sess.ourSeq += fakeHttpResponse.size
            Thread.sleep(30)
            sendTcp(dip, dp, sip, sp, sess.ourSeq, sess.clientSeq, 0x11)
            sess.state = 3; tcpSessions.remove(key)
        } else {
            sendTcp(dip, dp, sip, sp, sess.ourSeq, sess.clientSeq, 0x10)
        }
    }

    private fun closeSession(key: String) {
        val s = tcpSessions.remove(key) ?: return
        try { s.sock?.close() } catch (_: Exception) {}
    }

    // ==================== 写TCP包 ====================
    private fun sendTcp(sip: String, sp: Int, dip: String, dp: Int,
                        seqN: Long, ackN: Long, flags: Int, payload: ByteArray = ByteArray(0)) {
        val ih = 20; val th = 20; val total = ih + th + payload.size
        val p = ByteArray(total)
        p[0] = 0x45.toByte(); w16(p, 2, total); p[6] = 0x40.toByte(); p[8] = 64; p[9] = 6
        wIp(p, 12, sip); wIp(p, 16, dip); ipCsum(p)
        w16(p, ih, sp); w16(p, ih + 2, dp); w32(p, ih + 4, seqN); w32(p, ih + 8, ackN)
        p[ih + 12] = 0x50.toByte(); p[ih + 13] = (flags and 0x3F).toByte()
        p[ih + 14] = 0xFF.toByte(); p[ih + 15] = 0xFF.toByte()
        if (payload.isNotEmpty()) System.arraycopy(payload, 0, p, ih + th, payload.size)
        tcpCsum(p, ih, sip, dip)
        writeTun(p)
    }

    // ==================== 工具 ====================
    private fun writeTun(p: ByteArray) {
        try { synchronized(tunOutput) { tunOutput.write(p); tunOutput.flush() } }
        catch (_: IOException) {}
    }

    private fun ip(d: ByteArray, o: Int) = "${d[o].toInt() and 0xFF}.${d[o+1].toInt() and 0xFF}.${d[o+2].toInt() and 0xFF}.${d[o+3].toInt() and 0xFF}"
    private fun u16(d: ByteArray, o: Int) = ((d[o].toInt() and 0xFF) shl 8) or (d[o+1].toInt() and 0xFF)
    private fun u32(d: ByteArray, o: Int): Long = ((d[o].toLong() and 0xFF) shl 24) or ((d[o+1].toLong() and 0xFF) shl 16) or ((d[o+2].toLong() and 0xFF) shl 8) or (d[o+3].toLong() and 0xFF)
    private fun w16(d: ByteArray, o: Int, v: Int) { d[o] = ((v shr 8) and 0xFF).toByte(); d[o+1] = (v and 0xFF).toByte() }
    private fun w32(d: ByteArray, o: Int, v: Long) { d[o] = ((v shr 24) and 0xFF).toByte(); d[o+1] = ((v shr 16) and 0xFF).toByte(); d[o+2] = ((v shr 8) and 0xFF).toByte(); d[o+3] = (v and 0xFF).toByte() }
    private fun wIp(d: ByteArray, o: Int, ip: String) { ip.split(".").forEachIndexed { i, s -> d[o+i] = s.toInt().toByte() } }
    private fun ipCsum(p: ByteArray) {
        p[10] = 0; p[11] = 0; var s = 0L
        for (i in 0 until 20 step 2) s += ((p[i].toInt() and 0xFF) shl 8) or (p[i+1].toInt() and 0xFF)
        while (s shr 16 != 0L) s = (s and 0xFFFF) + (s shr 16)
        val c = s.toInt().inv() and 0xFFFF; p[10] = ((c shr 8) and 0xFF).toByte(); p[11] = (c and 0xFF).toByte()
    }
    private fun tcpCsum(p: ByteArray, t: Int, sip: String, dip: String) {
        val tl = p.size - t; val ps = ByteArray(12 + tl)
        wIp(ps, 0, sip); wIp(ps, 4, dip); ps[9] = 6; w16(ps, 10, tl)
        p[t+16] = 0; p[t+17] = 0; System.arraycopy(p, t, ps, 12, tl)
        var s = 0L; for (i in ps.indices step 2) s += ((ps[i].toInt() and 0xFF) shl 8) or (if (i+1 < ps.size) (ps[i+1].toInt() and 0xFF) else 0)
        while (s shr 16 != 0L) s = (s and 0xFFFF) + (s shr 16)
        val c = s.toInt().inv() and 0xFFFF; p[t+16] = ((c shr 8) and 0xFF).toByte(); p[t+17] = (c and 0xFF).toByte()
    }
}
