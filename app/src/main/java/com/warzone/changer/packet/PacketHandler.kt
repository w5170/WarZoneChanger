package com.warzone.changer.packet

import android.net.VpnService
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
    private val protect: (Socket) -> Boolean,
    private val vpnService: VpnService
) {
    companion object {
        private const val TAG = "PacketHandler"
        private const val BUF_SIZE = 65535
    }

    @Volatile private var running = false
    private val tcpSessions = ConcurrentHashMap<String, TcpSession>()

    data class TcpSession(
        var state: Int = 0, // 0=init, 1=syn_ack, 2=established, 3=closed
        var clientSeq: Long = 0,
        var ourSeq: Long = 0,
        var serverSocket: Socket? = null,
        var serverReader: java.io.InputStream? = null,
        var serverWriter: java.io.OutputStream? = null,
        val isTarget: Boolean = false,
        val srcIp: String = "",
        val srcPort: Int = 0,
        val dstIp: String = "",
        val dstPort: Int = 0
    )

    fun start() {
        running = true
        Thread({ readLoop() }, "tun-reader").start()
        Log.i(TAG, "Started, targets=$targetIps")
    }

    fun stop() {
        running = false
        for ((_, s) in tcpSessions) {
            try { s.serverSocket?.close() } catch (_: Exception) {}
        }
        tcpSessions.clear()
        Log.i(TAG, "Stopped")
    }

    private fun readLoop() {
        val buf = ByteArray(BUF_SIZE)
        while (running) {
            try {
                val len = tunInput.read(buf)
                if (len > 0) {
                    val data = buf.copyOf(len)
                    processPacket(data)
                }
            } catch (e: IOException) {
                if (running) Log.e(TAG, "Read error", e)
            }
        }
    }

    private fun processPacket(data: ByteArray) {
        if (data.size < 20) return
        val ver = (data[0].toInt() shr 4) and 0xF
        if (ver != 4) return
        val proto = data[9].toInt() and 0xFF

        when (proto) {
            17 -> handleUdp(data)  // UDP (DNS)
            6 -> handleTcp(data)   // TCP
        }
    }

    // ==================== DNS (UDP) ====================
    private fun handleUdp(data: ByteArray) {
        val ipHdrLen = (data[0].toInt() and 0xF) * 4
        if (data.size < ipHdrLen + 8) return

        val srcIp = readIp(data, 12)
        val dstIp = readIp(data, 16)
        val srcPort = readU16(data, ipHdrLen)
        val dstPort = readU16(data, ipHdrLen + 2)

        if (dstPort != 53) return // 只处理 DNS

        // 提取 DNS 查询
        val udpPayloadOffset = ipHdrLen + 8
        val dnsQuery = data.copyOfRange(udpPayloadOffset, data.size)

        Thread({
            try {
                val socket = DatagramSocket()
                vpnService.protect(socket)
                socket.soTimeout = 3000

                val dnsServer = InetAddress.getByName("8.8.8.8")
                socket.send(DatagramPacket(dnsQuery, dnsQuery.size, dnsServer, 53))

                val response = ByteArray(1024)
                val pkt = DatagramPacket(response, response.size)
                socket.receive(pkt)
                socket.close()

                // 构建 IP+UDP 响应包写回 TUN
                writeUdpResponse(dstIp, dstPort, srcIp, 53, response.copyOf(pkt.length))
            } catch (e: Exception) {
                Log.w(TAG, "DNS forward failed: ${e.message}")
            }
        }, "dns-${srcIp}:${srcPort}").start()
    }

    private fun writeUdpResponse(srcIp: String, srcPort: Int, dstIp: String, dstPort: Int, payload: ByteArray) {
        val ipH = 20; val udpH = 8
        val total = ipH + udpH + payload.size
        val pkt = ByteArray(total)

        // IP header
        pkt[0] = 0x45.toByte()
        writeU16(pkt, 2, total)
        pkt[6] = 0x40.toByte()
        pkt[8] = 64
        pkt[9] = 17 // UDP
        writeIp(pkt, 12, srcIp)
        writeIp(pkt, 16, dstIp)
        ipChecksum(pkt)

        // UDP header
        writeU16(pkt, ipH, srcPort)
        writeU16(pkt, ipH + 2, dstPort)
        writeU16(pkt, ipH + 4, udpH + payload.size)
        // UDP checksum = 0 (optional for IPv4)
        System.arraycopy(payload, 0, pkt, ipH + udpH, payload.size)

        writeTun(pkt)
    }

    // ==================== TCP ====================
    private fun handleTcp(data: ByteArray) {
        val ipHdrLen = (data[0].toInt() and 0xF) * 4
        if (data.size < ipHdrLen + 20) return

        val srcIp = readIp(data, 12)
        val dstIp = readIp(data, 16)
        val srcPort = readU16(data, ipHdrLen)
        val dstPort = readU16(data, ipHdrLen + 2)

        val tcp = data.copyOfRange(ipHdrLen, data.size)
        val seq = readU32(tcp, 4)
        val ack = readU32(tcp, 8)
        val tcpHdrLen = ((tcp[12].toInt() and 0xF0) shr 4) * 4
        val flags = tcp[13].toInt() and 0x3F
        val payload = if (tcpHdrLen < tcp.size) tcp.copyOfRange(tcpHdrLen, tcp.size) else ByteArray(0)

        val isSyn = (flags and 0x02) != 0
        val isAck = (flags and 0x10) != 0
        val isFin = (flags and 0x01) != 0
        val isRst = (flags and 0x04) != 0

        val key = "${srcIp}:${srcPort}->${dstIp}:${dstPort}"
        val isTarget = dstIp in targetIps && dstPort == 80

        if (isRst) {
            val sess = tcpSessions.remove(key)
            try { sess?.serverSocket?.close() } catch (_: Exception) {}
            return
        }

        if (isSyn && !isAck) {
            handleSyn(key, srcIp, srcPort, dstIp, dstPort, seq, isTarget)
            return
        }

        val sess = tcpSessions[key] ?: return

        if (isAck && payload.isEmpty() && sess.state == 1) {
            // 三次握手完成
            sess.state = 2
            if (!isTarget && sess.serverSocket == null) {
                // 非目标：连接真实服务器
                connectRealServer(sess, key)
            }
            return
        }

        if (payload.isNotEmpty() && sess.state == 2) {
            if (isTarget) {
                handleTargetData(sess, key, payload, seq, srcIp, srcPort, dstIp, dstPort)
            } else {
                handleForwardData(sess, key, payload, seq)
            }
            return
        }

        if (isAck && payload.isEmpty()) {
            sess.clientSeq = seq
        }

        if (isFin) {
            handleFin(sess, key, seq, srcIp, srcPort, dstIp, dstPort)
        }
    }

    private fun handleSyn(key: String, srcIp: String, srcPort: Int, dstIp: String, dstPort: Int, clientSeq: Long, isTarget: Boolean) {
        val ourSeq = (Math.random() * 0xFFFFFFFFL).toLong()
        val sess = TcpSession(
            state = 1, clientSeq = clientSeq, ourSeq = ourSeq,
            isTarget = isTarget, srcIp = srcIp, srcPort = srcPort, dstIp = dstIp, dstPort = dstPort
        )
        tcpSessions[key] = sess

        // SYN-ACK
        writeTcp(dstIp, dstPort, srcIp, srcPort, ourSeq, clientSeq + 1, 0x12, ByteArray(0))
        sess.ourSeq++
        Log.d(TAG, "SYN $key target=$isTarget")
    }

    private fun connectRealServer(sess: TcpSession, key: String) {
        Thread({
            try {
                val socket = Socket()
                protect(socket)
                socket.connect(InetSocketAddress(sess.dstIp, sess.dstPort), 5000)
                sess.serverSocket = socket
                sess.serverReader = socket.getInputStream()
                sess.serverWriter = socket.getOutputStream()
                Log.d(TAG, "Connected to real server: ${sess.dstIp}:${sess.dstPort}")

                // 开始从真实服务器读数据
                val buf = ByteArray(8192)
                while (running && sess.state == 2) {
                    val n = sess.serverReader!!.read(buf)
                    if (n <= 0) break
                    val payload = buf.copyOf(n)
                    writeTcp(sess.dstIp, sess.dstPort, sess.srcIp, sess.srcPort,
                        sess.ourSeq, sess.clientSeq, 0x18, payload)
                    sess.ourSeq += n
                }
            } catch (e: Exception) {
                Log.d(TAG, "Server connection failed: ${e.message}")
            } finally {
                if (sess.state == 2) {
                    writeTcp(sess.dstIp, sess.dstPort, sess.srcIp, sess.srcPort,
                        sess.ourSeq, sess.clientSeq, 0x11, ByteArray(0))
                    sess.state = 3
                    tcpSessions.remove(key)
                }
                try { sess.serverSocket?.close() } catch (_: Exception) {}
            }
        }, "relay-${key}").start()
    }

    private fun handleForwardData(sess: TcpSession, key: String, payload: ByteArray, seq: Long) {
        sess.clientSeq = seq + payload.size
        // ACK 到客户端
        writeTcp(sess.dstIp, sess.dstPort, sess.srcIp, sess.srcPort,
            sess.ourSeq, sess.clientSeq, 0x10, ByteArray(0))
        // 转发到真实服务器
        try {
            sess.serverWriter?.write(payload)
            sess.serverWriter?.flush()
        } catch (e: Exception) {
            Log.w(TAG, "Forward write failed: ${e.message}")
        }
    }

    private fun handleTargetData(sess: TcpSession, key: String, payload: ByteArray, seq: Long,
                                  srcIp: String, srcPort: Int, dstIp: String, dstPort: Int) {
        sess.clientSeq = seq + payload.size
        val http = String(payload, Charsets.US_ASCII)

        if (http.startsWith("GET ") || http.startsWith("POST ") ||
            http.startsWith("HEAD ") || http.startsWith("PUT ")) {
            Log.d(TAG, "HTTP intercepted: $key")

            // ACK
            writeTcp(dstIp, dstPort, srcIp, srcPort, sess.ourSeq, sess.clientSeq, 0x10, ByteArray(0))
            // PSH+ACK 假响应
            writeTcp(dstIp, dstPort, srcIp, srcPort, sess.ourSeq, sess.clientSeq, 0x18, fakeHttpResponse)
            sess.ourSeq += fakeHttpResponse.size
            // FIN+ACK
            Thread.sleep(20)
            writeTcp(dstIp, dstPort, srcIp, srcPort, sess.ourSeq, sess.clientSeq, 0x11, ByteArray(0))
            sess.state = 3
            tcpSessions.remove(key)
            Log.d(TAG, "Fake response sent: $key")
        } else {
            // 非 HTTP，ACK
            writeTcp(dstIp, dstPort, srcIp, srcPort, sess.ourSeq, sess.clientSeq, 0x10, ByteArray(0))
        }
    }

    private fun handleFin(sess: TcpSession, key: String, seq: Long,
                           srcIp: String, srcPort: Int, dstIp: String, dstPort: Int) {
        sess.clientSeq = seq + 1
        writeTcp(dstIp, dstPort, srcIp, srcPort, sess.ourSeq, sess.clientSeq, 0x11, ByteArray(0))
        sess.state = 3
        tcpSessions.remove(key)
        try { sess.serverSocket?.close() } catch (_: Exception) {}
        Log.d(TAG, "FIN: $key")
    }

    // ==================== 写 TCP 包 ====================
    private fun writeTcp(srcIp: String, srcPort: Int, dstIp: String, dstPort: Int,
                         seqNum: Long, ackNum: Long, flags: Int, payload: ByteArray) {
        val ipH = 20; val tcpH = 20
        val total = ipH + tcpH + payload.size
        val pkt = ByteArray(total)

        // IP
        pkt[0] = 0x45.toByte()
        writeU16(pkt, 2, total)
        pkt[6] = 0x40.toByte()
        pkt[8] = 64
        pkt[9] = 6
        writeIp(pkt, 12, srcIp)
        writeIp(pkt, 16, dstIp)
        ipChecksum(pkt)

        // TCP
        writeU16(pkt, ipH, srcPort)
        writeU16(pkt, ipH + 2, dstPort)
        writeU32(pkt, ipH + 4, seqNum)
        writeU32(pkt, ipH + 8, ackNum)
        pkt[ipH + 12] = 0x50.toByte()
        pkt[ipH + 13] = (flags and 0x3F).toByte()
        pkt[ipH + 14] = 0xFF.toByte()
        pkt[ipH + 15] = 0xFF.toByte()
        if (payload.isNotEmpty()) System.arraycopy(payload, 0, pkt, ipH + tcpH, payload.size)
        tcpChecksum(pkt, ipH, srcIp, dstIp)

        writeTun(pkt)
    }

    // ==================== 工具 ====================
    private fun writeTun(pkt: ByteArray) {
        try {
            synchronized(tunOutput) { tunOutput.write(pkt); tunOutput.flush() }
        } catch (e: IOException) { Log.e(TAG, "Write error", e) }
    }

    private fun readIp(d: ByteArray, o: Int) = "${d[o].toInt() and 0xFF}.${d[o+1].toInt() and 0xFF}.${d[o+2].toInt() and 0xFF}.${d[o+3].toInt() and 0xFF}"
    private fun readU16(d: ByteArray, o: Int) = ((d[o].toInt() and 0xFF) shl 8) or (d[o+1].toInt() and 0xFF)
    private fun readU32(d: ByteArray, o: Int): Long = ((d[o].toLong() and 0xFF) shl 24) or ((d[o+1].toLong() and 0xFF) shl 16) or ((d[o+2].toLong() and 0xFF) shl 8) or (d[o+3].toLong() and 0xFF)
    private fun writeU16(d: ByteArray, o: Int, v: Int) { d[o] = ((v shr 8) and 0xFF).toByte(); d[o+1] = (v and 0xFF).toByte() }
    private fun writeU32(d: ByteArray, o: Int, v: Long) { d[o] = ((v shr 24) and 0xFF).toByte(); d[o+1] = ((v shr 16) and 0xFF).toByte(); d[o+2] = ((v shr 8) and 0xFF).toByte(); d[o+3] = (v and 0xFF).toByte() }
    private fun writeIp(d: ByteArray, o: Int, ip: String) { val p = ip.split("."); for (i in 0..3) d[o+i] = p[i].toInt().toByte() }

    private fun ipChecksum(pkt: ByteArray) {
        pkt[10] = 0; pkt[11] = 0
        var sum = 0L
        for (i in 0 until 20 step 2) sum += ((pkt[i].toInt() and 0xFF) shl 8) or (pkt[i+1].toInt() and 0xFF)
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        val c = sum.toInt().inv() and 0xFFFF
        pkt[10] = ((c shr 8) and 0xFF).toByte(); pkt[11] = (c and 0xFF).toByte()
    }

    private fun tcpChecksum(pkt: ByteArray, t: Int, srcIp: String, dstIp: String) {
        val tcpLen = pkt.size - t
        val pseudo = ByteArray(12 + tcpLen)
        writeIp(pseudo, 0, srcIp); writeIp(pseudo, 4, dstIp)
        pseudo[8] = 0; pseudo[9] = 6
        writeU16(pseudo, 10, tcpLen)
        pkt[t+16] = 0; pkt[t+17] = 0
        System.arraycopy(pkt, t, pseudo, 12, tcpLen)
        var sum = 0L
        for (i in pseudo.indices step 2) sum += ((pseudo[i].toInt() and 0xFF) shl 8) or (if (i+1 < pseudo.size) (pseudo[i+1].toInt() and 0xFF) else 0)
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        val c = sum.toInt().inv() and 0xFFFF
        pkt[t+16] = ((c shr 8) and 0xFF).toByte(); pkt[t+17] = (c and 0xFF).toByte()
    }
}
