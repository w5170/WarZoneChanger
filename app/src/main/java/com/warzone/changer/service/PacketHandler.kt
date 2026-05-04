package com.warzone.changer.service

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import com.warzone.changer.data.LocationStore
import com.warzone.changer.injector.LocationInterceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 轻量级 TCP/IP 包处理器，替代 NetBare 的完整实现。
 *
 * 工作流程：
 * 1. 从 VPN TUN 设备读取 IP 数据包
 * 2. 解析 TCP 头部，提取 HTTP 请求载荷
 * 3. 识别目标为 apis.map.qq.com:80 的请求
 * 4. 返回假的腾讯地图 API 响应（修改 adcode）
 * 5. 其他 TCP 连接通过隧道转发
 *
 * 注意：仅处理 HTTP (port 80)，HTTPS 直通。
 */
class PacketHandler(private val vpnService: VpnService) {

    companion object {
        private const val TAG = "PacketHandler"
        private const val TARGET_HOST = "apis.map.qq.com"
        private const val HTTP_PORT = 80
    }

    private val executor = Executors.newCachedThreadPool()
    @Volatile
    private var running = false

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    // 跟踪活动的 TCP 连接：key = "srcPort-dstIp:dstPort"
    private val connections = ConcurrentHashMap<String, Connection>()

    data class Connection(
        val socket: Socket,
        val outputStream: java.io.OutputStream,
        val inputStream: java.io.InputStream
    )

    /**
     * 启动包处理循环
     */
    fun start(vpnFd: ParcelFileDescriptor) {
        running = true
        val input = FileInputStream(vpnFd.fileDescriptor)
        val output = FileOutputStream(vpnFd.fileDescriptor)
        val packet = ByteArray(32767)

        executor.execute {
            Log.i(TAG, "Packet handler started")
            while (running) {
                try {
                    val length = input.read(packet)
                    if (length <= 0) continue

                    val buffer = ByteBuffer.wrap(packet, 0, length)
                    buffer.order(ByteOrder.BIG_ENDIAN)

                    processPacket(buffer, output)
                } catch (e: Exception) {
                    if (running) Log.e(TAG, "Packet read error: ${e.message}")
                }
            }
            Log.i(TAG, "Packet handler stopped")
        }
    }

    fun stop() {
        running = false
        connections.values.forEach { conn ->
            try { conn.socket.close() } catch (_: Exception) {}
        }
        connections.clear()
        executor.shutdownNow()
    }

    // ==================== IP 包处理 ====================

    private fun processPacket(packet: ByteBuffer, output: FileOutputStream) {
        if (packet.remaining() < 20) return

        val version = (packet.get(0).toInt() ushr 4) and 0x0F
        if (version != 4) return // 仅处理 IPv4

        val ihl = packet.get(0).toInt() and 0x0F
        val ipHeaderLen = ihl * 4
        if (packet.remaining() < ipHeaderLen) return

        val protocol = packet.get(9).toInt() and 0xFF
        val srcIpBytes = ByteArray(4)
        val dstIpBytes = ByteArray(4)
        packet.position(12)
        packet.get(srcIpBytes)
        packet.get(dstIpBytes)

        val srcIp = InetAddress.getByAddress(srcIpBytes).hostAddress ?: return
        val dstIp = InetAddress.getByAddress(dstIpBytes).hostAddress ?: return

        if (protocol != 6) return // 仅处理 TCP

        val totalLen = ((packet.get(2).toInt() and 0xFF) shl 8) or (packet.get(3).toInt() and 0xFF)

        // 解析 TCP 头部
        packet.position(ipHeaderLen)
        if (packet.remaining() < 20) return

        val srcPort = ((packet.get().toInt() and 0xFF) shl 8) or (packet.get().toInt() and 0xFF)
        val dstPort = ((packet.get().toInt() and 0xFF) shl 8) or (packet.get().toInt() and 0xFF)

        // 提取 TCP seq 和 ack 号
        val seq = packet.getInt(ipHeaderLen + 4)    // 序列号
        val ack = packet.getInt(ipHeaderLen + 8)    // 确认号

        val dataOffset = ((packet.get(ipHeaderLen + 12).toInt() ushr 4) and 0x0F) * 4
        val tcpHeaderLen = dataOffset
        val payloadOffset = ipHeaderLen + tcpHeaderLen
        val payloadLen = totalLen - ipHeaderLen - tcpHeaderLen

        if (payloadLen <= 0) return // 纯 ACK/SYN 包，不需要处理

        // 提取 TCP 载荷
        val payload = ByteArray(payloadLen)
        packet.position(payloadOffset)
        packet.get(payload)

        val payloadStr = String(payload, Charsets.US_ASCII)

        // 检查是否是 HTTP 请求（GET/POST/HEAD 等）
        if (dstPort == HTTP_PORT && isHttpRequest(payloadStr)) {
            // 响应 seq = 收到的 ack, 响应 ack = 收到的 seq + payload长度
            handleHttpRequest(srcIp, srcPort, dstIp, dstPort, ack, seq + payloadLen, payloadStr, payload, output)
        }
    }

    private fun isHttpRequest(data: String): Boolean {
        return data.startsWith("GET ") || data.startsWith("POST ") ||
               data.startsWith("HEAD ") || data.startsWith("PUT ") ||
               data.startsWith("DELETE ") || data.startsWith("OPTIONS ")
    }

    // ==================== HTTP 请求处理 ====================

    private fun handleHttpRequest(
        srcIp: String, srcPort: Int,
        dstIp: String, dstPort: Int,
        respSeq: Int, respAck: Int,
        payloadStr: String, payloadRaw: ByteArray,
        output: FileOutputStream
    ) {
        val lines = payloadStr.split("\r\n")
        if (lines.isEmpty()) return

        val requestLine = lines[0]
        val parts = requestLine.split(" ")
        if (parts.size < 2) return

        val method = parts[0]
        val path = parts[1]

        // 提取 Host 头
        var host = dstIp
        for (line in lines.drop(1)) {
            if (line.lowercase().startsWith("host:")) {
                host = line.substring(5).trim()
                break
            }
        }

        // ★ 检查是否是目标请求
        if (host == TARGET_HOST && path.contains("/ws/geocoder/v1")) {
            Log.i(TAG, "★ 拦截: $method http://$host$path")
            val fakeResponse = LocationInterceptor.buildFakeResponse(vpnService)
            val respBody = fakeResponse.toByteArray(Charsets.UTF_8)
            sendFakeTcpResponse(
                output, srcIp, srcPort, dstIp, dstPort,
                respSeq, respAck, respBody, "application/json"
            )
        } else {
            // 非目标 HTTP 请求：通过 OkHttp 代理转发
            forwardHttpRequest(
                output, srcIp, srcPort, dstIp, dstPort,
                respSeq, respAck, method, host, path, lines.drop(1), payloadRaw
            )
        }
    }

    /**
     * 通过 OkHttp 转发 HTTP 请求（模拟服务器响应）
     */
    private fun forwardHttpRequest(
        output: FileOutputStream,
        srcIp: String, srcPort: Int,
        dstIp: String, dstPort: Int,
        respSeq: Int, respAck: Int,
        method: String, host: String, path: String,
        headers: List<String>, rawPayload: ByteArray
    ) {
        executor.execute {
            try {
                val url = "http://$host$path"
                val reqBuilder = Request.Builder().url(url)

                for (h in headers) {
                    val idx = h.indexOf(':')
                    if (idx > 0) {
                        val name = h.substring(0, idx).trim()
                        val value = h.substring(idx + 1).trim()
                        if (!name.equals("Host", true) &&
                            !name.equals("Connection", true)) {
                            reqBuilder.header(name, value)
                        }
                    }
                }

                // 提取请求体
                val headerEnd = String(rawPayload, Charsets.US_ASCII).indexOf("\r\n\r\n")
                val body = if (headerEnd > 0 && method == "POST") {
                    rawPayload.copyOfRange(headerEnd + 4, rawPayload.size)
                } else ByteArray(0)

                val reqBody = if (body.isNotEmpty()) {
                    okhttp3.RequestBody.create(null, body)
                } else null

                when (method) {
                    "GET" -> reqBuilder.get()
                    "POST" -> reqBuilder.post(reqBody ?: okhttp3.RequestBody.create(null, ByteArray(0)))
                    "HEAD" -> reqBuilder.head()
                }

                val resp = httpClient.newCall(reqBuilder.build()).execute()
                val respBody = resp.body?.bytes() ?: ByteArray(0)

                sendRawTcpResponse(
                    output, srcIp, srcPort, dstIp, dstPort,
                    respSeq, respAck, resp.code, resp.header("Content-Type", "text/plain") ?: "text/plain", respBody
                )
                resp.close()
            } catch (e: Exception) {
                Log.e(TAG, "Forward error: ${e.message}")
                sendRawTcpResponse(
                    output, srcIp, srcPort, dstIp, dstPort,
                    respSeq, respAck, 502, "text/plain", "Proxy Error".toByteArray()
                )
            }
        }
    }

    // ==================== TCP 响应构造 ====================

    /**
     * 构造假的 TCP 响应包并写入 VPN TUN 设备
     * 这样游戏收到的是我们构造的假响应，而不是真实服务器的响应
     */
    private fun sendFakeTcpResponse(
        output: FileOutputStream,
        srcIp: String, srcPort: Int,
        dstIp: String, dstPort: Int,
        seq: Int, ack: Int,
        body: ByteArray, contentType: String
    ) {
        sendRawTcpResponse(output, srcIp, srcPort, dstIp, dstPort, seq, ack, 200, contentType, body)
    }

    private fun sendRawTcpResponse(
        output: FileOutputStream,
        srcIp: String, srcPort: Int,
        dstIp: String, dstPort: Int,
        seq: Int, ack: Int,
        statusCode: Int, contentType: String, body: ByteArray
    ) {
        // 构造 HTTP 响应
        val statusText = when (statusCode) {
            200 -> "OK"
            404 -> "Not Found"
            502 -> "Bad Gateway"
            else -> "OK"
        }
        val httpResponse = buildString {
            append("HTTP/1.1 $statusCode $statusText\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(Charsets.US_ASCII) + body

        // 构造 IP + TCP 头部并发送
        val packet = buildTcpPacket(srcIp, srcPort, dstIp, dstPort, seq, ack, httpResponse, false)
        synchronized(output) {
            output.write(packet)
            output.flush()
        }
        // 发送 FIN+ACK 关闭连接
        val finPacket = buildTcpPacket(srcIp, srcPort, dstIp, dstPort, seq + httpResponse.size, ack, ByteArray(0), true)
        synchronized(output) {
            output.write(finPacket)
            output.flush()
        }
        synchronized(output) {
            output.write(packet)
            output.flush()
        }
    }

    /**
     * 构造完整的 IP + TCP 数据包
     */
    private fun buildTcpPacket(
        srcIp: String, srcPort: Int,
        dstIp: String, dstPort: Int,
        seqNum: Int, ackNum: Int,
        payload: ByteArray,
        fin: Boolean
    ): ByteArray {
        val srcAddr = InetAddress.getByName(srcIp).address
        val dstAddr = InetAddress.getByName(dstIp).address

        val tcpHeaderLen = 20
        val ipHeaderLen = 20
        val totalLen = ipHeaderLen + tcpHeaderLen + payload.size

        val buf = ByteBuffer.allocate(totalLen)
        buf.order(ByteOrder.BIG_ENDIAN)

        // === IP 头部 ===
        buf.put(0x45.toByte()) // Version 4, IHL 5
        buf.put(0x00) // TOS
        buf.putShort(totalLen.toShort()) // Total Length
        buf.putShort(0) // Identification
        buf.putShort(0x4000.toShort()) // Flags: DF
        buf.put(64.toByte()) // TTL
        buf.put(6.toByte()) // Protocol: TCP
        buf.putShort(0) // Checksum (placeholder)
        buf.put(srcAddr) // Source IP
        buf.put(dstAddr) // Destination IP

        // 计算 IP 头校验和
        val ipChecksum = calculateChecksum(buf.array(), 0, ipHeaderLen)
        buf.putShort(10, ipChecksum)

        // === TCP 头部 ===
        val tcpStart = ipHeaderLen
        buf.putShort(tcpStart, dstPort.toShort()) // 源端口 = 目标端口（模拟服务器响应）
        buf.putShort(tcpStart + 2, srcPort.toShort()) // 目标端口 = 源端口

        // 序列号和确认号（简化：使用固定值）
        buf.putInt(tcpStart + 4, seqNum) // Seq (来自收到的 ack)
        buf.putInt(tcpStart + 8, ackNum) // Ack (收到的 seq + payload长度)

        buf.put(tcpStart + 12, 0x50.toByte()) // Data Offset 5, Reserved
        val flags = if (fin) 0x11 else 0x18 // FIN+ACK or PSH+ACK
        buf.put(tcpStart + 13, flags.toByte())

        buf.putShort(tcpStart + 14, 65535.toShort()) // Window Size
        buf.putShort(tcpStart + 16, 0) // Checksum (placeholder)
        buf.putShort(tcpStart + 18, 0) // Urgent Pointer

        // TCP 伪头部用于校验和计算
        val tcpSegmentLen = tcpHeaderLen + payload.size
        val pseudoHeader = ByteBuffer.allocate(12)
        pseudoHeader.order(ByteOrder.BIG_ENDIAN)
        pseudoHeader.put(srcAddr)
        pseudoHeader.put(dstAddr)
        pseudoHeader.put(0) // Reserved
        pseudoHeader.put(6) // Protocol
        pseudoHeader.putShort(tcpSegmentLen.toShort())

        // TCP 校验和
        val tcpChecksum = calculateTcpChecksum(
            pseudoHeader.array(),
            buf.array(), tcpStart, tcpHeaderLen,
            payload
        )
        buf.putShort(tcpStart + 16, tcpChecksum)

        // 写入载荷
        buf.position(tcpStart + tcpHeaderLen)
        buf.put(payload)

        return buf.array()
    }

    private fun calculateChecksum(data: ByteArray, offset: Int, length: Int): Short {
        var sum = 0
        var i = offset
        while (i < offset + length - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (length % 2 != 0) {
            sum += (data[offset + length - 1].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv().toShort()
    }

    private fun calculateTcpChecksum(
        pseudoHeader: ByteArray,
        tcpHeader: ByteArray, tcpOffset: Int, tcpHeaderLen: Int,
        payload: ByteArray
    ): Short {
        var sum = 0

        // 伪头部
        for (i in pseudoHeader.indices step 2) {
            sum += ((pseudoHeader[i].toInt() and 0xFF) shl 8) or (pseudoHeader[i + 1].toInt() and 0xFF)
        }

        // TCP 头部
        for (i in 0 until tcpHeaderLen step 2) {
            if (i == 16) continue // 跳过校验和字段
            sum += ((tcpHeader[tcpOffset + i].toInt() and 0xFF) shl 8) or
                   (tcpHeader[tcpOffset + i + 1].toInt() and 0xFF)
        }

        // 载荷
        for (i in payload.indices step 2) {
            sum += ((payload[i].toInt() and 0xFF) shl 8) or
                   if (i + 1 < payload.size) (payload[i + 1].toInt() and 0xFF) else 0
        }

        while ((sum ushr 16) != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv().toShort()
    }
}
