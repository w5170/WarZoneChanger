package com.warzone.changer.vpn

import android.util.Log
import com.warzone.changer.model.SelectedLocation
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

class LocalHttpProxy(private val port: Int = 18080) {

    companion object {
        private const val TAG = "LocalHttpProxy"
        private const val TARGET_HOST = "apis.map.qq.com"
    }

    @Volatile
    var running = false
        private set

    var targetLocation: SelectedLocation? = null

    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null

    fun start() {
        if (running) return
        running = true
        serverThread = Thread({
            try {
                serverSocket = ServerSocket(port)
                Log.i(TAG, "Proxy listening on port $port")
                while (running) {
                    try {
                        val client = serverSocket!!.accept()
                        Thread { handleClient(client) }.start()
                    } catch (e: Exception) {
                        if (running) Log.e(TAG, "Accept error", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error", e)
            }
        }, "http-proxy")
        serverThread!!.start()
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverThread?.interrupt()
    }

    private fun handleClient(client: Socket) {
        try {
            val input = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
            val output = client.getOutputStream()

            // Read request line
            val reqLine = input.readLine() ?: return
            if (reqLine.isEmpty()) return

            // Read headers
            val headers = mutableListOf<String>()
            var host = ""
            var transferEncoding = ""
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                headers.add(line)
                if (line.startsWith("Host:", ignoreCase = true)) {
                    host = line.substring(5).trim()
                }
                if (line.startsWith("Transfer-Encoding:", ignoreCase = true)) {
                    transferEncoding = line.substring(18).trim()
                }
            }

            // Check if this is a target request
            if (host.contains(TARGET_HOST) && reqLine.contains("/ws/geocoder/")) {
                Log.i(TAG, "Intercepting: $reqLine")
                // Read and discard body if present
                if (transferEncoding.equals("chunked", ignoreCase = true)) {
                    readChunked(input)
                }
                // Return fake response
                val fakeBody = buildFakeResponse()
                val response = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: application/json; charset=utf-8\r\n")
                    append("Content-Length: ${fakeBody.toByteArray(StandardCharsets.UTF_8).size}\r\n")
                    append("Connection: close\r\n")
                    append("\r\n")
                    append(fakeBody)
                }
                output.write(response.toByteArray(StandardCharsets.UTF_8))
                output.flush()
                Log.i(TAG, "Sent fake response")
            } else {
                // Forward to real server
                forwardRequest(reqLine, headers, host, input, output, transferEncoding)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Client error: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun forwardRequest(
        reqLine: String,
        headers: List<String>,
        host: String,
        input: BufferedReader,
        output: OutputStream,
        transferEncoding: String
    ) {
        var remote: Socket? = null
        try {
            val parts = host.split(":")
            val rHost = parts[0]
            val rPort = if (parts.size > 1) parts[1].toInt() else 80

            remote = Socket(rHost, rPort)
            val rOut = remote.getOutputStream()
            val rIn = remote.getInputStream()

            // Forward request line and headers
            val reqBytes = buildString {
                append(reqLine).append("\r\n")
                headers.forEach { append(it).append("\r\n") }
                append("\r\n")
            }.toByteArray(StandardCharsets.UTF_8)
            rOut.write(reqBytes)
            rOut.flush()

            // Forward body
            if (transferEncoding.equals("chunked", ignoreCase = true)) {
                val body = readChunked(input)
                rOut.write(body.toByteArray(StandardCharsets.UTF_8))
                rOut.flush()
            }

            // Forward response back
            val buf = ByteArray(4096)
            while (true) {
                val len = rIn.read(buf)
                if (len == -1) break
                output.write(buf, 0, len)
                output.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Forward error: ${e.message}")
        } finally {
            try { remote?.close() } catch (_: Exception) {}
        }
    }

    private fun readChunked(input: BufferedReader): String {
        val result = StringBuilder()
        while (true) {
            val sizeLine = input.readLine() ?: break
            val size = sizeLine.trim().toIntOrNull(16) ?: break
            if (size == 0) {
                input.readLine() // trailing CRLF
                break
            }
            val buf = CharArray(size)
            var read = 0
            while (read < size) {
                val n = input.read(buf, read, size - read)
                if (n == -1) break
                read += n
            }
            result.append(buf, 0, read)
            input.readLine() // trailing CRLF
        }
        return result.toString()
    }

    private fun buildFakeResponse(): String {
        val loc = targetLocation ?: return """{"status":0,"message":"query ok","result":{"location":{"lat":39.9042,"lng":116.4074},"adcode":"110100","nation":"中国","province":"北京市","city":"北京市","district":"东城区"}}"""
        val json = JSONObject()
        json.put("status", 0)
        json.put("message", "query ok")
        val result = JSONObject()
        val location = JSONObject()
        location.put("lat", loc.latitude)
        location.put("lng", loc.longitude)
        result.put("location", location)
        result.put("adcode", loc.adcode)
        result.put("nation", "中国")
        result.put("province", loc.province)
        result.put("city", loc.city)
        result.put("district", loc.district)
        val addrComp = JSONObject()
        addrComp.put("nation", "中国")
        addrComp.put("province", loc.province)
        addrComp.put("city", loc.city)
        addrComp.put("district", loc.district)
        result.put("address_component", addrComp)
        result.put("formatted_address", loc.formattedAddress)
        json.put("result", result)
        return json.toString()
    }
}
