package com.warzone.changer.packet

import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * 简化版包处理器 — 只处理目标流量
 *
 * 因为 VPN 只路由 apis.map.qq.com 的 IP，所以到达这里的包
 * 全部都是目标流量，直接拦截返回假响应即可。
 * 不需要转发逻辑！
 */
class PacketHandler(
    private val tunInput: FileInputStream,
    private val tunOutput: FileOutputStream,
    private val targetIps: Set<String>,
    private val fakeHttpResponse: ByteArray
) {
    companion object {
        private const val TAG = "PacketHandler"
        private const val BUF_SIZE = 65535
    }

    @Volatile private var running = false
    private val sessions = ConcurrentHashMap<String, Session>()

    data class Session(
        var clientSeq: Long = 0,
        var serverSeq: Long = 100000L,
        var state: Int = 0 // 0=syn_rcvd, 1=established, 2=closed
    )

    fun start() {
        running = true
        Thread({ readLoop() }, "tun-reader").start()
        Log.i(TAG, "Started, target IPs: $targetIps")
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
                if (len > 0) processPacket(buf, len)
            } catch (e: IOException) {
                if (running) Log.e(TAG, "Read error", e)
            }
        }
    }

    private fun processPacket(data: ByteArray, len: Int) {
        if (len < 40) return // min IP(20) + TCP(20)
        val ver = (data[0].toInt() shr 4) and 0xF
        if (ver != 4) return
        val proto = data[9].toInt() and 0xFF
        if (proto != 6) return // 只处理 TCP

        val ipHdrLen = (data[0].toInt() and 0xF) * 4
        if (len < ipHdrLen + 20) return

        val srcIp = ip(data, 12); val dstIp = ip(data, 16)
        val tcp = data.copyOfRange(ipHdrLen, len)
        val srcPort = u16(tcp, 0); val dstPort = u16(tcp, 2)
        val seq = u32(tcp, 4); val ack = u32(tcp, 8)
        val tcpHdrLen = ((tcp[12].toInt() and 0xF0) shr 4) * 4
        val flags = tcp[13].toInt() and 0x3F
        val payload = if (tcpHdrLen < tcp.size) tcp.copyOfRange(tcpHdrLen, tcp.size) else ByteArray(0)

        val isSyn = (flags and 0x02) != 0
        val isAck = (flags and 0x10) != 0
        val isFin = (flags and 0x01) != 0
        val isRst = (flags and 0x04) != 0

        val key = "$srcIp:$srcPort"
        var sess = sessions[key]

        if (isRst) { sessions.remove(key); return }

        if (isSyn && !isAck) {
            // 三次握手第一步：收到 SYN → 发 SYN-ACK
            val s = Session(clientSeq = seq)
            sessions[key] = s
            writeTcp(dstIp, dstPort, srcIp, srcPort, s.serverSeq, seq + 1, 0x12, ByteArray(0))
            s.serverSeq++
            Log.d(TAG, "SYN from $key")
            return
        }

        if (sess == null) { return }

        if (isAck && sess.state == 0) {
            // 三次握手第三步：ACK → 连接建立
            sess.state = 1
            Log.d(TAG, "Established: $key")
        }

        if (payload.isNotEmpty() && sess.state == 1) {
            // 有数据 → 检查是否是 HTTP 请求
            val http = String(payload, Charsets.US_ASCII)
            if (http.startsWith("GET ") || http.startsWith("POST ") ||
                http.startsWith("HEAD ") || http.startsWith("PUT ")) {
                Log.d(TAG, "HTTP intercepted from $key")

                // ACK 收到的数据
                sess.clientSeq = seq + payload.size
                writeTcp(dstIp, dstPort, srcIp, srcPort, sess.serverSeq, sess.clientSeq, 0x10, ByteArray(0))

                // PSH+ACK 发送假响应
                writeTcp(dstIp, dstPort, srcIp, srcPort, sess.serverSeq, sess.clientSeq, 0x18, fakeHttpResponse)
                sess.serverSeq += fakeHttpResponse.size

                // FIN+ACK 关闭
                Thread.sleep(20)
                writeTcp(dstIp, dstPort, srcIp, srcPort, sess.serverSeq, sess.clientSeq, 0x11, ByteArray(0))
                sess.state = 2
                Log.d(TAG, "Fake response sent, closing: $key")
            } else {
                // 非 HTTP 数据，ACK 它
                sess.clientSeq = seq + payload.size
                writeTcp(dstIp, dstPort, srcIp, srcPort, sess.serverSeq, sess.clientSeq, 0x10, ByteArray(0))
            }
        }

        if (isAck && payload.isEmpty()) {
            sess.clientSeq = seq
        }

        if (isFin) {
            sess.clientSeq = seq + 1
            writeTcp(dstIp, dstPort, srcIp, srcPort, sess.serverSeq, sess.clientSeq, 0x11, ByteArray(0))
            sessions.remove(key)
            Log.d(TAG, "FIN: $key")
        }
    }

    // ========== 构建 TCP 包写入 TUN ==========
    private fun writeTcp(srcIp: String, srcPort: Int, dstIp: String, dstPort: Int,
                         seqNum: Long, ackNum: Long, flags: Int, payload: ByteArray) {
        val ipH = 20; val tcpH = 20
        val total = ipH + tcpH + payload.size
        val pkt = ByteArray(total)

        // IP
        pkt[0] = 0x45.toByte()
        w16(pkt, 2, total)
        pkt[6] = 0x40.toByte() // DF
        pkt[8] = 64
        pkt[9] = 6 // TCP
        wIp(pkt, 12, srcIp); wIp(pkt, 16, dstIp)
        ipCsum(pkt)

        // TCP
        val t = ipH
        w16(pkt, t, srcPort); w16(pkt, t + 2, dstPort)
        w32(pkt, t + 4, seqNum); w32(pkt, t + 8, ackNum)
        pkt[t + 12] = 0x50.toByte() // data offset=5
        pkt[t + 13] = (flags and 0x3F).toByte()
        pkt[t + 14] = 0xFF.toByte(); pkt[t + 15] = 0xFF.toByte() // window
        if (payload.isNotEmpty()) System.arraycopy(payload, 0, pkt, t + tcpH, payload.size)
        tcpCsum(pkt, t, srcIp, dstIp)

        try {
            synchronized(tunOutput) { tunOutput.write(pkt); tunOutput.flush() }
        } catch (e: IOException) { Log.e(TAG, "Write error", e) }
    }

    // ========== 工具方法 ==========
    private fun ip(d: ByteArray, o: Int) = "${d[o].toInt() and 0xFF}.${d[o+1].toInt() and 0xFF}.${d[o+2].toInt() and 0xFF}.${d[o+3].toInt() and 0xFF}"
    private fun u16(d: ByteArray, o: Int) = ((d[o].toInt() and 0xFF) shl 8) or (d[o+1].toInt() and 0xFF)
    private fun u32(d: ByteArray, o: Int): Long = ((d[o].toLong() and 0xFF) shl 24) or ((d[o+1].toLong() and 0xFF) shl 16) or ((d[o+2].toLong() and 0xFF) shl 8) or (d[o+3].toLong() and 0xFF)
    private fun w16(d: ByteArray, o: Int, v: Int) { d[o] = ((v shr 8) and 0xFF).toByte(); d[o+1] = (v and 0xFF).toByte() }
    private fun w32(d: ByteArray, o: Int, v: Long) { d[o] = ((v shr 24) and 0xFF).toByte(); d[o+1] = ((v shr 16) and 0xFF).toByte(); d[o+2] = ((v shr 8) and 0xFF).toByte(); d[o+3] = (v and 0xFF).toByte() }
    private fun wIp(d: ByteArray, o: Int, ip: String) { val p = ip.split("."); for (i in 0..3) d[o+i] = p[i].toInt().toByte() }

    private fun ipCsum(pkt: ByteArray) {
        pkt[10] = 0; pkt[11] = 0
        var sum = 0L
        for (i in 0 until 20 step 2) sum += ((pkt[i].toInt() and 0xFF) shl 8) or (pkt[i+1].toInt() and 0xFF)
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        val c = sum.toInt().inv() and 0xFFFF
        pkt[10] = ((c shr 8) and 0xFF).toByte(); pkt[11] = (c and 0xFF).toByte()
    }

    private fun tcpCsum(pkt: ByteArray, t: Int, srcIp: String, dstIp: String) {
        val tcpLen = pkt.size - t
        val pseudo = ByteArray(12 + tcpLen)
        wIp(pseudo, 0, srcIp); wIp(pseudo, 4, dstIp)
        pseudo[8] = 0; pseudo[9] = 6
        pseudo[10] = ((tcpLen shr 8) and 0xFF).toByte(); pseudo[11] = (tcpLen and 0xFF).toByte()
        pkt[t+16] = 0; pkt[t+17] = 0
        System.arraycopy(pkt, t, pseudo, 12, tcpLen)
        var sum = 0L
        for (i in pseudo.indices step 2) sum += ((pseudo[i].toInt() and 0xFF) shl 8) or (if (i+1 < pseudo.size) (pseudo[i+1].toInt() and 0xFF) else 0)
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        val c = sum.toInt().inv() and 0xFFFF
        pkt[t+16] = ((c shr 8) and 0xFF).toByte(); pkt[t+17] = (c and 0xFF).toByte()
    }
}
