package com.warzone.changer.packet

import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 核心包处理器 — 只处理目标流量
 *
 * 架构：VPN 只路由 apis.map.qq.com 的 IP，其他流量不经过 VPN
 * → 本类只需处理目标 IP:80 的 TCP 包
 * → SYN → SYN-ACK
 * → HTTP 请求 → 返回假响应（改 adcode）
 * → FIN → FIN-ACK
 * → 其他端口 → RST
 *
 * 不做任何转发，不会影响游戏其他网络连接
 */
class PacketHandler(
    private val tunInput: FileInputStream,
    private val tunOutput: FileOutputStream,
    private val targetIps: Set<String>,
    private val fakeHttpResponse: ByteArray
) {
    companion object {
        private const val TAG = "PacketHandler"
        private const val TARGET_PORT = 80
        private const val BUF_SIZE = 65535
        private const val SESSION_TIMEOUT_MS = 30_000L
    }

    @Volatile
    private var running = false
    private val executor = Executors.newCachedThreadPool()
    private val sessions = ConcurrentHashMap<String, Session>()

    private class Session(
        val key: String,
        val srcIp: String, val srcPort: Int,
        val dstIp: String, val dstPort: Int,
        var clientSeq: Long,
        var serverSeq: Long,
        var state: State = State.SYN_RECEIVED,
        var lastActivity: Long = System.currentTimeMillis()
    ) {
        enum class State { SYN_RECEIVED, ESTABLISHED, CLOSED }
    }

    // ===== 启动/停止 =====

    fun start() {
        running = true
        executor.submit { readLoop() }
        executor.submit { cleanupLoop() }
        Log.i(TAG, "PacketHandler started. Target IPs: $targetIps")
    }

    fun stop() {
        running = false
        sessions.clear()
        executor.shutdownNow()
        try { executor.awaitTermination(2, TimeUnit.SECONDS) } catch (_: Exception) {}
        Log.i(TAG, "PacketHandler stopped")
    }

    // ===== 主循环 =====

    private fun readLoop() {
        val buf = ByteArray(BUF_SIZE)
        while (running) {
            try {
                val len = tunInput.read(buf)
                if (len > 0) processPacket(buf.copyOf(len))
            } catch (e: IOException) {
                if (running) Log.e(TAG, "TUN read error", e)
            }
        }
    }

    private fun cleanupLoop() {
        while (running) {
            try {
                Thread.sleep(5000)
                val now = System.currentTimeMillis()
                val expired = sessions.entries.filter { now - it.value.lastActivity > SESSION_TIMEOUT_MS }
                expired.forEach { (k, _) ->
                    sessions.remove(k)
                    Log.d(TAG, "Session expired: $k")
                }
            } catch (_: InterruptedException) { break }
            catch (e: Exception) { Log.e(TAG, "Cleanup error", e) }
        }
    }

    // ===== 包处理 =====

    private fun processPacket(data: ByteArray) {
        if (data.size < 20) return

        val version = (data[0].toInt() shr 4) and 0x0F
        if (version != 4) return

        val ipHdrLen = (data[0].toInt() and 0x0F) * 4
        val protocol = data[9].toInt() and 0x0F
        val srcIp = readIp(data, 12)
        val dstIp = readIp(data, 16)

        // 只处理发往目标 IP 的包
        if (dstIp !in targetIps && srcIp !in targetIps) return

        when (protocol) {
            6 -> handleTcp(data, ipHdrLen, srcIp, dstIp)
            17 -> handleUdp(data, ipHdrLen, srcIp, dstIp)
            1 -> handleIcmp(data, ipHdrLen, srcIp, dstIp)
        }
    }

    // ===== TCP 处理 =====

    private fun handleTcp(data: ByteArray, ipHdrLen: Int, srcIp: String, dstIp: String) {
        if (data.size < ipHdrLen + 20) return

        val tcpOff = ipHdrLen
        val srcPort = readU16(data, tcpOff)
        val dstPort = readU16(data, tcpOff + 2)
        val seq = readU32(data, tcpOff + 4)
        val ack = readU32(data, tcpOff + 8)
        val dataOff = ((data[tcpOff + 12].toInt() and 0xF0) shr 4) * 4
        val flags = data[tcpOff + 13].toInt() and 0x3F
        val window = readU16(data, tcpOff + 14)

        val isSyn = (flags and 0x02) != 0
        val isAck = (flags and 0x10) != 0
        val isFin = (flags and 0x01) != 0
        val isRst = (flags and 0x04) != 0
        val isPsh = (flags and 0x08) != 0

        val payloadStart = ipHdrLen + dataOff
        val payload = if (payloadStart < data.size) data.copyOfRange(payloadStart, data.size) else ByteArray(0)

        val key = "$srcIp:$srcPort->$dstIp:$dstPort"

        // 非目标端口 → RST
        if (dstPort != TARGET_PORT && dstIp in targetIps) {
            if (isSyn && !isAck) sendRst(dstIp, dstPort, srcIp, srcPort, 0, seq + 1)
            return
        }
        // 从目标 IP 发来的包（响应方向）→ 可能是上一个会话的迟到包，忽略
        if (srcIp in targetIps && dstIp !in targetIps) return

        when {
            isRst -> {
                sessions.remove(key)
            }
            isSyn && !isAck -> {
                handleSyn(key, srcIp, srcPort, dstIp, dstPort, seq)
            }
            isAck && sessions.containsKey(key) -> {
                val session = sessions[key]!!
                session.lastActivity = System.currentTimeMillis()
                session.clientSeq = seq

                if (payload.isNotEmpty() && session.state == Session.State.ESTABLISHED) {
                    val http = String(payload, Charsets.US_ASCII)
                    if (http.startsWith("GET ") || http.startsWith("POST ") ||
                        http.startsWith("HEAD ") || http.startsWith("PUT ") ||
                        http.startsWith("OPTIONS ")) {
                        Log.d(TAG, "Intercepted HTTP: ${http.take(80)}")
                        session.clientSeq = seq + payload.size
                        sendAck(session)
                        sendFakeResponse(session)
                    }
                }

                if (isFin) {
                    session.clientSeq = seq + 1
                    sendFinAck(session)
                    sessions.remove(key)
                }
            }
            else -> {
                // 未知状态，RST
                if (isSyn || isAck) {
                    sendRst(dstIp, dstPort, srcIp, srcPort, 0, seq + if (payload.isNotEmpty()) payload.size else 1)
                }
            }
        }
    }

    private fun handleSyn(key: String, srcIp: String, srcPort: Int, dstIp: String, dstPort: Int, clientSeq: Long) {
        val serverSeq = 100000L + (Math.random() * 900000).toLong()
        val session = Session(
            key = key,
            srcIp = srcIp, srcPort = srcPort,
            dstIp = dstIp, dstPort = dstPort,
            clientSeq = clientSeq,
            serverSeq = serverSeq
        )
        sessions[key] = session

        // SYN-ACK
        val pkt = buildTcpPacket(
            srcIp = dstIp, srcPort = dstPort,
            dstIp = srcIp, dstPort = srcPort,
            seq = serverSeq, ack = clientSeq + 1,
            flags = 0x12, payload = ByteArray(0)
        )
        writeTun(pkt)
        session.serverSeq = serverSeq + 1
        Log.d(TAG, "SYN-ACK sent: $key")
    }

    private fun sendAck(session: Session) {
        val pkt = buildTcpPacket(
            srcIp = session.dstIp, srcPort = session.dstPort,
            dstIp = session.srcIp, dstPort = session.srcPort,
            seq = session.serverSeq, ack = session.clientSeq,
            flags = 0x10, payload = ByteArray(0)
        )
        writeTun(pkt)
    }

    private fun sendFakeResponse(session: Session) {
        // PSH+ACK + 假数据
        val pkt = buildTcpPacket(
            srcIp = session.dstIp, srcPort = session.dstPort,
            dstIp = session.srcIp, dstPort = session.srcPort,
            seq = session.serverSeq, ack = session.clientSeq,
            flags = 0x18, payload = fakeHttpResponse
        )
        writeTun(pkt)
        session.serverSeq += fakeHttpResponse.size

        // 稍后发 FIN+ACK 关闭
        Thread.sleep(30)
        val fin = buildTcpPacket(
            srcIp = session.dstIp, srcPort = session.dstPort,
            dstIp = session.srcIp, dstPort = session.srcPort,
            seq = session.serverSeq, ack = session.clientSeq,
            flags = 0x11, payload = ByteArray(0)
        )
        writeTun(fin)
        session.state = Session.State.CLOSED
        sessions.remove(session.key)
        Log.d(TAG, "Fake response sent, connection closed")
    }

    private fun sendFinAck(session: Session) {
        val pkt = buildTcpPacket(
            srcIp = session.dstIp, srcPort = session.dstPort,
            dstIp = session.srcIp, dstPort = session.srcPort,
            seq = session.serverSeq, ack = session.clientSeq,
            flags = 0x11, payload = ByteArray(0)
        )
        writeTun(pkt)
    }

    private fun sendRst(srcIp: String, srcPort: Int, dstIp: String, dstPort: Int, seq: Long, ack: Long) {
        val pkt = buildTcpPacket(
            srcIp = srcIp, srcPort = srcPort,
            dstIp = dstIp, dstPort = dstPort,
            seq = seq, ack = ack,
            flags = 0x14, payload = ByteArray(0)
        )
        writeTun(pkt)
    }

    // ===== UDP/DNS 处理 =====

    private fun handleUdp(data: ByteArray, ipHdrLen: Int, srcIp: String, dstIp: String) {
        if (data.size < ipHdrLen + 8) return
        val srcPort = readU16(data, ipHdrLen)
        val dstPort = readU16(data, ipHdrLen + 2)
        val payload = data.copyOfRange(ipHdrLen + 8, data.size)

        // DNS 查询转发
        if (dstPort == 53) {
            executor.submit {
                try {
                    val sock = java.net.DatagramSocket()
                    sock.soTimeout = 5000
                    sock.send(java.net.DatagramPacket(payload, payload.size,
                        java.net.InetSocketAddress("8.8.8.8", 53)))
                    val buf = ByteArray(1024)
                    val recv = java.net.DatagramPacket(buf, buf.size)
                    sock.receive(recv)
                    sock.close()

                    val resp = buildUdpPacket(srcIp = dstIp, srcPort = 53,
                        dstIp = srcIp, dstPort = srcPort,
                        payload = buf.copyOf(recv.length))
                    writeTun(resp)
                } catch (e: Exception) {
                    Log.w(TAG, "DNS forward error", e)
                }
            }
        }
    }

    // ===== ICMP 处理 =====

    private fun handleIcmp(data: ByteArray, ipHdrLen: Int, srcIp: String, dstIp: String) {
        if (data.size < ipHdrLen + 8) return
        val icmpType = data[ipHdrLen].toInt() and 0xFF
        if (icmpType == 8) {
            // Echo Reply
            val reply = data.copyOf()
            System.arraycopy(data, 12, reply, 16, 4)
            System.arraycopy(data, 16, reply, 12, 4)
            reply[8] = 64
            reply[ipHdrLen] = 0
            reply[ipHdrLen + 2] = 0; reply[ipHdrLen + 3] = 0
            val csum = checksum(reply, ipHdrLen, reply.size - ipHdrLen)
            reply[ipHdrLen + 2] = ((csum shr 8) and 0xFF).toByte()
            reply[ipHdrLen + 3] = (csum and 0xFF).toByte()
            fixIpChecksum(reply)
            writeTun(reply)
        }
    }

    // ===== 构建 TCP 包 =====

    private fun buildTcpPacket(
        srcIp: String, srcPort: Int,
        dstIp: String, dstPort: Int,
        seq: Long, ack: Long,
        flags: Int, payload: ByteArray
    ): ByteArray {
        val ipLen = 20
        val tcpLen = 20
        val total = ipLen + tcpLen + payload.size
        val pkt = ByteArray(total)

        // IP
        pkt[0] = 0x45.toByte()
        pkt[2] = ((total shr 8) and 0xFF).toByte()
        pkt[3] = (total and 0xFF).toByte()
        pkt[6] = 0x40.toByte() // DF
        pkt[8] = 64
        pkt[9] = 6
        writeIp(pkt, 12, srcIp)
        writeIp(pkt, 16, dstIp)

        // TCP
        val t = ipLen
        pkt[t] = ((srcPort shr 8) and 0xFF).toByte()
        pkt[t + 1] = (srcPort and 0xFF).toByte()
        pkt[t + 2] = ((dstPort shr 8) and 0xFF).toByte()
        pkt[t + 3] = (dstPort and 0xFF).toByte()
        writeU32(pkt, t + 4, seq)
        writeU32(pkt, t + 8, ack)
        pkt[t + 12] = 0x50.toByte() // data offset 5
        pkt[t + 13] = (flags and 0x3F).toByte()
        pkt[t + 14] = 0xFF.toByte(); pkt[t + 15] = 0xFF.toByte()

        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, pkt, ipLen + tcpLen, payload.size)
        }

        // TCP checksum
        val tcpCsum = tcpChecksum(pkt, ipLen, srcIp, dstIp)
        pkt[t + 16] = ((tcpCsum shr 8) and 0xFF).toByte()
        pkt[t + 17] = (tcpCsum and 0xFF).toByte()

        fixIpChecksum(pkt)
        return pkt
    }

    // ===== 构建 UDP 包 =====

    private fun buildUdpPacket(
        srcIp: String, srcPort: Int,
        dstIp: String, dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val ipLen = 20
        val udpLen = 8 + payload.size
        val total = ipLen + udpLen
        val pkt = ByteArray(total)

        pkt[0] = 0x45.toByte()
        pkt[2] = ((total shr 8) and 0xFF).toByte()
        pkt[3] = (total and 0xFF).toByte()
        pkt[6] = 0x40.toByte()
        pkt[8] = 64
        pkt[9] = 17
        writeIp(pkt, 12, srcIp)
        writeIp(pkt, 16, dstIp)

        val u = ipLen
        pkt[u] = ((srcPort shr 8) and 0xFF).toByte()
        pkt[u + 1] = (srcPort and 0xFF).toByte()
        pkt[u + 2] = ((dstPort shr 8) and 0xFF).toByte()
        pkt[u + 3] = (dstPort and 0xFF).toByte()
        pkt[u + 4] = ((udpLen shr 8) and 0xFF).toByte()
        pkt[u + 5] = (udpLen and 0xFF).toByte()

        System.arraycopy(payload, 0, pkt, u + 8, payload.size)
        fixIpChecksum(pkt)
        return pkt
    }

    // ===== 校验和 =====

    private fun fixIpChecksum(pkt: ByteArray) {
        pkt[10] = 0; pkt[11] = 0
        val c = checksum(pkt, 0, 20)
        pkt[10] = ((c shr 8) and 0xFF).toByte()
        pkt[11] = (c and 0xFF).toByte()
    }

    private fun checksum(data: ByteArray, off: Int, len: Int): Int {
        var sum = 0L
        var i = off
        var rem = len
        while (rem > 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2; rem -= 2
        }
        if (rem == 1) sum += (data[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.toInt().inv() and 0xFFFF
    }

    private fun tcpChecksum(pkt: ByteArray, tcpOff: Int, srcIp: String, dstIp: String): Int {
        val tcpLen = pkt.size - tcpOff
        val pseudo = ByteArray(12)
        writeIp(pseudo, 0, srcIp)
        writeIp(pseudo, 4, dstIp)
        pseudo[9] = 6
        pseudo[10] = ((tcpLen shr 8) and 0xFF).toByte()
        pseudo[11] = (tcpLen and 0xFF).toByte()

        val saved = byteArrayOf(pkt[tcpOff + 16], pkt[tcpOff + 17])
        pkt[tcpOff + 16] = 0; pkt[tcpOff + 17] = 0

        val combined = ByteArray(12 + tcpLen)
        System.arraycopy(pseudo, 0, combined, 0, 12)
        System.arraycopy(pkt, tcpOff, combined, 12, tcpLen)
        val c = checksum(combined, 0, combined.size)

        pkt[tcpOff + 16] = saved[0]; pkt[tcpOff + 17] = saved[1]
        return c
    }

    // ===== IO 工具 =====

    private fun writeTun(data: ByteArray) {
        try {
            synchronized(tunOutput) {
                tunOutput.write(data)
                tunOutput.flush()
            }
        } catch (e: IOException) {
            Log.e(TAG, "TUN write error", e)
        }
    }

    private fun readIp(data: ByteArray, off: Int) =
        "${data[off].toInt() and 0xFF}.${data[off+1].toInt() and 0xFF}.${data[off+2].toInt() and 0xFF}.${data[off+3].toInt() and 0xFF}"

    private fun writeIp(data: ByteArray, off: Int, ip: String) {
        ip.split(".").forEachIndexed { i, s -> data[off + i] = s.toInt().toByte() }
    }

    private fun readU16(data: ByteArray, off: Int) =
        ((data[off].toInt() and 0xFF) shl 8) or (data[off + 1].toInt() and 0xFF)

    private fun readU32(data: ByteArray, off: Int): Long =
        ((data[off].toLong() and 0xFF) shl 24) or
        ((data[off+1].toLong() and 0xFF) shl 16) or
        ((data[off+2].toLong() and 0xFF) shl 8) or
        (data[off+3].toLong() and 0xFF)

    private fun writeU32(data: ByteArray, off: Int, v: Long) {
        data[off] = ((v shr 24) and 0xFF).toByte()
        data[off+1] = ((v shr 16) and 0xFF).toByte()
        data[off+2] = ((v shr 8) and 0xFF).toByte()
        data[off+3] = (v and 0xFF).toByte()
    }
}
