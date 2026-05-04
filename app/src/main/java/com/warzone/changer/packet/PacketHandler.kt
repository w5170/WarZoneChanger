package com.warzone.changer.packet

import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * 原作者方案：只拦截目标IP的HTTP请求，其他流量完全不碰
 * VPN只路由目标IP，所以到达这里的包全部都是目标流量
 */
class PacketHandler(
    private val tunInput: FileInputStream,
    private val tunOutput: FileOutputStream,
    private val targetIps: Set<String>,
    private val fakeHttpResponse: ByteArray
) {
    companion object {
        private const val TAG = "PacketHandler"
        private const val BUF_SIZE = 32767
    }

    @Volatile private var running = false
    private val sessions = ConcurrentHashMap<String, Session>()

    data class Session(
        var clientSeq: Long = 0,
        var ourSeq: Long = 0,
        var state: Int = 0  // 0=init, 1=established, 2=done
    )

    fun start() {
        running = true
        Thread({ readLoop() }, "tun-reader").start()
        Log.i(TAG, "Started, targets=$targetIps")
    }

    fun stop() {
        running = false
        sessions.clear()
        Log.i(TAG, "Stopped")
    }

    private fun readLoop() {
        val buf = ByteArray(BUF_SIZE)
        while (running) {
            try {
                val len = tunInput.read(buf)
                if (len >= 40) processPacket(buf.copyOf(len))
            } catch (e: IOException) {
                if (running) Log.e(TAG, "Read err", e)
            }
        }
    }

    private fun processPacket(data: ByteArray) {
        // IP header
        if ((data[0].toInt() shr 4) != 4) return  // 只处理IPv4
        if (data[9].toInt() != 6) return            // 只处理TCP
        val ipH = (data[0].toInt() and 0xF) * 4
        if (data.size < ipH + 20) return            // TCP头最小20字节

        val srcIp = ip(data, 12)
        val dstIp = ip(data, 16)
        val srcPort = u16(data, ipH)
        val dstPort = u16(data, ipH + 2)

        // TCP header
        val tcp = data.copyOfRange(ipH, data.size)
        val seq = u32(tcp, 4)
        val ackNum = u32(tcp, 8)
        val tcpH = ((tcp[12].toInt() and 0xF0) shr 4) * 4
        val flags = tcp[13].toInt() and 0x3F
        val payload = if (tcpH < tcp.size) tcp.copyOfRange(tcpH, tcp.size) else ByteArray(0)

        val syn = (flags and 0x02) != 0
        val ack = (flags and 0x10) != 0
        val fin = (flags and 0x01) != 0
        val rst = (flags and 0x04) != 0
        val psh = (flags and 0x08) != 0

        val key = "$dstIp:$dstPort<$srcIp:$srcPort"

        if (rst) { sessions.remove(key); return }

        // ===== SYN: 开始新连接 =====
        if (syn && !ack) {
            val s = Session(clientSeq = seq, ourSeq = 100000L + (Math.random() * 900000).toLong())
            sessions[key] = s
            // 发送 SYN-ACK
            sendTcp(dstIp, dstPort, srcIp, srcPort, s.ourSeq, seq + 1, 0x12)
            s.ourSeq++
            Log.d(TAG, "SYN $key")
            return
        }

        val s = sessions[key] ?: return

        // ===== ACK 完成三次握手 =====
        if (ack && !syn && payload.isEmpty() && s.state == 0) {
            s.state = 1  // established
            s.clientSeq = seq
            Log.d(TAG, "Established $key")
            return
        }

        // ===== 有数据 =====
        if (payload.isNotEmpty() && s.state == 1) {
            s.clientSeq = seq + payload.size
            val http = String(payload, Charsets.US_ASCII)

            if (http.startsWith("GET ") || http.startsWith("POST ") ||
                http.startsWith("HEAD ") || http.startsWith("PUT ")) {
                Log.i(TAG, "HTTP intercepted! $key")
                Log.d(TAG, "Request: ${http.take(80)}")

                // ACK 收到的请求
                sendTcp(dstIp, dstPort, srcIp, srcPort, s.ourSeq, s.clientSeq, 0x10)

                // PSH+ACK 发送假响应
                sendTcp(dstIp, dstPort, srcIp, srcPort, s.ourSeq, s.clientSeq, 0x18, fakeHttpResponse)
                s.ourSeq += fakeHttpResponse.size

                // FIN+ACK 关闭
                Thread.sleep(50)
                sendTcp(dstIp, dstPort, srcIp, srcPort, s.ourSeq, s.clientSeq, 0x11)
                s.state = 2
                sessions.remove(key)
                Log.i(TAG, "Fake response sent, closed $key")
            } else {
                // 非HTTP数据，只ACK
                sendTcp(dstIp, dstPort, srcIp, srcPort, s.ourSeq, s.clientSeq, 0x10)
            }
            return
        }

        // 空ACK更新序列号
        if (ack && payload.isEmpty()) {
            s.clientSeq = seq
        }

        // ===== FIN =====
        if (fin) {
            s.clientSeq = seq + 1
            sendTcp(dstIp, dstPort, srcIp, srcPort, s.ourSeq, s.clientSeq, 0x11)
            sessions.remove(key)
            Log.d(TAG, "FIN $key")
        }
    }

    // ===== 构建IP+TCP包写入TUN =====
    private fun sendTcp(srcIp: String, srcPort: Int, dstIp: String, dstPort: Int,
                        seqN: Long, ackN: Long, flags: Int, payload: ByteArray = ByteArray(0)) {
        val ipH = 20; val tcpH = 20
        val total = ipH + tcpH + payload.size
        val p = ByteArray(total)

        // IP header
        p[0] = 0x45.toByte()           // version=4, ihl=5
        w16(p, 2, total)               // total length
        p[6] = 0x40.toByte()           // DF flag
        p[8] = 64                      // TTL
        p[9] = 6                       // protocol=TCP
        wIp(p, 12, srcIp)              // source IP
        wIp(p, 16, dstIp)              // dest IP
        ipCsum(p)                      // IP checksum

        // TCP header
        val t = ipH
        w16(p, t, srcPort)             // source port
        w16(p, t + 2, dstPort)         // dest port
        w32(p, t + 4, seqN)            // sequence
        w32(p, t + 8, ackN)            // ack
        p[t + 12] = 0x50.toByte()      // data offset=5 (20 bytes)
        p[t + 13] = (flags and 0x3F).toByte()
        p[t + 14] = 0xFF.toByte()      // window size
        p[t + 15] = 0xFF.toByte()
        if (payload.isNotEmpty()) System.arraycopy(payload, 0, p, t + tcpH, payload.size)
        tcpCsum(p, t, srcIp, dstIp)

        try {
            synchronized(tunOutput) { tunOutput.write(p); tunOutput.flush() }
        } catch (e: IOException) { Log.e(TAG, "Write err", e) }
    }

    // ===== 工具方法 =====
    private fun ip(d: ByteArray, o: Int) =
        "${d[o].toInt() and 0xFF}.${d[o+1].toInt() and 0xFF}.${d[o+2].toInt() and 0xFF}.${d[o+3].toInt() and 0xFF}"
    private fun u16(d: ByteArray, o: Int) = ((d[o].toInt() and 0xFF) shl 8) or (d[o+1].toInt() and 0xFF)
    private fun u32(d: ByteArray, o: Int): Long =
        ((d[o].toLong() and 0xFF) shl 24) or ((d[o+1].toLong() and 0xFF) shl 16) or
        ((d[o+2].toLong() and 0xFF) shl 8) or (d[o+3].toLong() and 0xFF)
    private fun w16(d: ByteArray, o: Int, v: Int) {
        d[o] = ((v shr 8) and 0xFF).toByte(); d[o+1] = (v and 0xFF).toByte()
    }
    private fun w32(d: ByteArray, o: Int, v: Long) {
        d[o] = ((v shr 24) and 0xFF).toByte(); d[o+1] = ((v shr 16) and 0xFF).toByte()
        d[o+2] = ((v shr 8) and 0xFF).toByte(); d[o+3] = (v and 0xFF).toByte()
    }
    private fun wIp(d: ByteArray, o: Int, ip: String) {
        ip.split(".").forEachIndexed { i, s -> d[o+i] = s.toInt().toByte() }
    }
    private fun ipCsum(p: ByteArray) {
        p[10] = 0; p[11] = 0; var s = 0L
        for (i in 0 until 20 step 2) s += ((p[i].toInt() and 0xFF) shl 8) or (p[i+1].toInt() and 0xFF)
        while (s shr 16 != 0L) s = (s and 0xFFFF) + (s shr 16)
        val c = s.toInt().inv() and 0xFFFF
        p[10] = ((c shr 8) and 0xFF).toByte(); p[11] = (c and 0xFF).toByte()
    }
    private fun tcpCsum(p: ByteArray, t: Int, srcIp: String, dstIp: String) {
        val tcpLen = p.size - t
        val ps = ByteArray(12 + tcpLen)
        wIp(ps, 0, srcIp); wIp(ps, 4, dstIp); ps[9] = 6; w16(ps, 10, tcpLen)
        p[t+16] = 0; p[t+17] = 0
        System.arraycopy(p, t, ps, 12, tcpLen)
        var s = 0L
        for (i in ps.indices step 2) s += ((ps[i].toInt() and 0xFF) shl 8) or (if (i+1 < ps.size) (ps[i+1].toInt() and 0xFF) else 0)
        while (s shr 16 != 0L) s = (s and 0xFFFF) + (s shr 16)
        val c = s.toInt().inv() and 0xFFFF
        p[t+16] = ((c shr 8) and 0xFF).toByte(); p[t+17] = (c and 0xFF).toByte()
    }
}
