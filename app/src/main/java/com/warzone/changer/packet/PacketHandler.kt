package com.warzone.changer.packet

import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
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
    private val protectTcp: (Socket) -> Boolean,
    private val protectUdp: (DatagramSocket) -> Boolean
) {
    companion object {
        private const val TAG = "PH"
        private const val MAX_SESS = 200
    }

    @Volatile private var running = false
    private val sessions = ConcurrentHashMap<String, Sess>()

    data class Sess(
        var st: Int = 0, // 0=connecting, 1=syn_sent, 2=est, 3=done
        var cSeq: Long = 0, var sSeq: Long = 0,
        var sock: Socket? = null,
        var sin: java.io.InputStream? = null,
        var sout: java.io.OutputStream? = null,
        val target: Boolean = false,
        val sip: String = "", val sp: Int = 0,
        val dip: String = "", val dp: Int = 0
    )

    fun start() {
        running = true
        Thread({
            try {
                val buf = ByteArray(65535)
                while (running) {
                    try {
                        val n = tunInput.read(buf)
                        if (n >= 20) dispatch(buf.copyOf(n))
                    } catch (e: IOException) {
                        if (running) Log.e(TAG, "read", e)
                    }
                }
            } catch (e: Exception) { Log.e(TAG, "readLoop", e) }
        }, "tun").start()
        Log.i(TAG, "Started")
    }

    fun stop() {
        running = false
        for ((_, s) in sessions) try { s.sock?.close() } catch (_: Exception) {}
        sessions.clear()
    }

    private fun dispatch(d: ByteArray) {
        try {
            if ((d[0].toInt() shr 4) != 4) return
            when (d[9].toInt() and 0xFF) {
                17 -> doDns(d)
                6 -> doTcp(d)
            }
        } catch (e: Exception) { Log.e(TAG, "dispatch", e) }
    }

    // ========== DNS ==========
    private fun doDns(d: ByteArray) {
        try {
            val ih = (d[0].toInt() and 0xF) * 4
            if (d.size < ih + 8) return
            if (u16(d, ih + 2) != 53) return
            val srcIp = ip(d, 12); val dstIp = ip(d, 16)
            val sp = u16(d, ih)
            val query = d.copyOfRange(ih + 8, d.size)
            Thread({
                try {
                    val ds = DatagramSocket()
                    protectUdp(ds)
                    ds.soTimeout = 3000
                    ds.send(DatagramPacket(query, query.size, InetAddress.getByName("8.8.8.8"), 53))
                    val resp = ByteArray(1024)
                    val pkt = DatagramPacket(resp, resp.size)
                    ds.receive(pkt); ds.close()
                    Log.d(TAG, "DNS resp ${pkt.length}b")
                    writeUdp(dstIp, 53, srcIp, sp, resp.copyOf(pkt.length))
                } catch (_: Exception) {}
            }, "dns").start()
        } catch (e: Exception) { Log.e(TAG, "dns", e) }
    }

    private fun writeUdp(sip: String, sp: Int, dip: String, dp: Int, payload: ByteArray) {
        val ih = 20; val uh = 8; val total = ih + uh + payload.size
        val p = ByteArray(total)
        p[0] = 0x45.toByte(); w16(p, 2, total); p[6] = 0x40.toByte(); p[8] = 64; p[9] = 17
        wIp(p, 12, sip); wIp(p, 16, dip); ipCsum(p)
        w16(p, ih, sp); w16(p, ih + 2, dp); w16(p, ih + 4, uh + payload.size)
        System.arraycopy(payload, 0, p, ih + uh, payload.size)
        wt(p)
    }

    // ========== TCP ==========
    private fun doTcp(d: ByteArray) {
        try {
            val ih = (d[0].toInt() and 0xF) * 4
            if (d.size < ih + 20) return
            val srcIp = ip(d, 12); val dstIp = ip(d, 16)
            val sp = u16(d, ih); val dp = u16(d, ih + 2)
            val tcp = d.copyOfRange(ih, d.size)
            val seq = u32(tcp, 4)
            val th = ((tcp[12].toInt() and 0xF0) shr 4) * 4
            val flags = tcp[13].toInt() and 0x3F
            val payload = if (th < tcp.size) tcp.copyOfRange(th, tcp.size) else ByteArray(0)

            val syn = (flags and 0x02) != 0; val ack = (flags and 0x10) != 0
            val fin = (flags and 0x01) != 0; val rst = (flags and 0x04) != 0

            val key = "$srcIp:$sp>$dstIp:$dp"
            val isTgt = dstIp in targetIps && dp == 80

            if (rst) { closeS(key); return }

            if (syn && !ack) {
                if (sessions.containsKey(key)) return
                if (sessions.size >= MAX_SESS) return
                val ss = Sess(0, seq, (100000..999999).random().toLong(),
                    target = isTgt, sip = srcIp, sp = sp, dip = dstIp, dp = dp)
                sessions[key] = ss
                if (isTgt) {
                    ss.st = 1; sendTcp(dstIp, dp, srcIp, sp, ss.sSeq, seq + 1, 0x12); ss.sSeq++
                } else {
                    Thread({ doConnect(ss, key) }, "c$key").start()
                }
                return
            }

            val ss = sessions[key] ?: return

            if (ack && !syn && payload.isEmpty()) {
                if (ss.st == 1) { ss.st = 2; ss.cSeq = seq }
                else if (ss.st == 0) { ss.cSeq = seq }
                return
            }

            if (payload.isNotEmpty()) {
                ss.cSeq = seq + payload.size
                if (isTgt && ss.st == 2) {
                    doTarget(ss, key, payload, srcIp, sp, dstIp, dp)
                } else if (!isTgt && ss.st == 2) {
                    sendTcp(dstIp, dp, srcIp, sp, ss.sSeq, ss.cSeq, 0x10)
                    try { ss.sout?.write(payload); ss.sout?.flush() } catch (_: Exception) {}
                } else {
                    sendTcp(dstIp, dp, srcIp, sp, ss.sSeq, ss.cSeq, 0x10)
                }
                return
            }

            if (ack && payload.isEmpty()) ss.cSeq = seq

            if (fin) {
                ss.cSeq = seq + 1
                sendTcp(dstIp, dp, srcIp, sp, ss.sSeq, ss.cSeq, 0x11)
                closeS(key)
            }
        } catch (e: Exception) { Log.e(TAG, "tcp", e) }
    }

    private fun doConnect(ss: Sess, key: String) {
        try {
            val s = Socket()
            protectTcp(s)
            s.connect(InetSocketAddress(ss.dip, ss.dp), 8000)
            s.tcpNoDelay = true
            ss.sock = s; ss.sin = s.getInputStream(); ss.sout = s.getOutputStream()
            ss.st = 1
            sendTcp(ss.dip, ss.dp, ss.sip, ss.sp, ss.sSeq, ss.cSeq + 1, 0x12)
            ss.sSeq++
            // 从服务器读
            try {
                val buf = ByteArray(8192)
                while (running && ss.st >= 1) {
                    val n = ss.sin?.read(buf) ?: -1
                    if (n <= 0) break
                    sendTcp(ss.dip, ss.dp, ss.sip, ss.sp, ss.sSeq, ss.cSeq, 0x18, buf.copyOf(n))
                    ss.sSeq += n
                }
            } catch (_: Exception) {}
            finally {
                try { sendTcp(ss.dip, ss.dp, ss.sip, ss.sp, ss.sSeq, ss.cSeq, 0x11) } catch (_: Exception) {}
                ss.st = 3; closeS(key)
            }
        } catch (e: Exception) {
            Log.w(TAG, "conn fail ${ss.dip}:${ss.dp}")
            try { sendTcp(ss.dip, ss.dp, ss.sip, ss.sp, 0, ss.cSeq + 1, 0x04) } catch (_: Exception) {}
            sessions.remove(key)
        }
    }

    private fun doTarget(ss: Sess, key: String, payload: ByteArray,
                          sip: String, sp: Int, dip: String, dp: Int) {
        val http = String(payload, Charsets.US_ASCII)
        if (http.startsWith("GET ") || http.startsWith("POST ") ||
            http.startsWith("HEAD ") || http.startsWith("PUT ")) {
            Log.i(TAG, "Hit! $key")
            sendTcp(dip, dp, sip, sp, ss.sSeq, ss.cSeq, 0x10)
            sendTcp(dip, dp, sip, sp, ss.sSeq, ss.cSeq, 0x18, fakeHttpResponse)
            ss.sSeq += fakeHttpResponse.size
            Thread.sleep(30)
            sendTcp(dip, dp, sip, sp, ss.sSeq, ss.cSeq, 0x11)
            ss.st = 3; sessions.remove(key)
        } else {
            sendTcp(dip, dp, sip, sp, ss.sSeq, ss.cSeq, 0x10)
        }
    }

    private fun closeS(key: String) {
        val s = sessions.remove(key) ?: return
        try { s.sock?.close() } catch (_: Exception) {}
    }

    // ========== 写TCP ==========
    private fun sendTcp(sip: String, sp: Int, dip: String, dp: Int,
                        seqN: Long, ackN: Long, flags: Int, payload: ByteArray = ByteArray(0)) {
        try {
            val ih = 20; val th = 20; val total = ih + th + payload.size
            val p = ByteArray(total)
            p[0] = 0x45.toByte(); w16(p, 2, total); p[6] = 0x40.toByte(); p[8] = 64; p[9] = 6
            wIp(p, 12, sip); wIp(p, 16, dip); ipCsum(p)
            w16(p, ih, sp); w16(p, ih + 2, dp); w32(p, ih + 4, seqN); w32(p, ih + 8, ackN)
            p[ih + 12] = 0x50.toByte(); p[ih + 13] = (flags and 0x3F).toByte()
            p[ih + 14] = 0xFF.toByte(); p[ih + 15] = 0xFF.toByte()
            if (payload.isNotEmpty()) System.arraycopy(payload, 0, p, ih + th, payload.size)
            tcpCsum(p, ih, sip, dip)
            wt(p)
        } catch (e: Exception) { Log.e(TAG, "sendTcp", e) }
    }

    private fun wt(p: ByteArray) {
        try { synchronized(tunOutput) { tunOutput.write(p); tunOutput.flush() } }
        catch (_: Exception) {}
    }

    // ========== 工具 ==========
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
