package com.warzone.changer.injector

import android.content.Context
import android.util.Log
import com.github.megatronking.netbare.http.HttpRequest
import com.github.megatronking.netbare.http.HttpResponse
import com.github.megatronking.netbare.http.HttpResponseBodyInterceptor
import com.warzone.changer.data.LocationStore
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset

class LocationInjector(private val context: Context) : HttpResponseBodyInterceptor() {

    companion object {
        private const val TAG = "LocationInjector"
        private const val TARGET_HOST = "apis.map.qq.com"
    }

    private val bodyBuf = ByteArrayOutputStream()
    private var isTarget = false

    override fun intercept(request: HttpRequest, response: HttpResponse, buffer: ByteBuffer, chain: Chain) {
        val host = request.host()
        val url = request.url()

        if (!isTarget && host.contains(TARGET_HOST)) {
            isTarget = true
            Log.i(TAG, "目标请求: $host$url")
        }

        if (isTarget) {
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            bodyBuf.write(bytes)

            val contentLen = response.getHeader("Content-Length")?.toIntOrNull() ?: 0
            val complete = (contentLen > 0 && bodyBuf.size() >= contentLen) ||
                           (contentLen == 0 && bodyBuf.size() > 0 && bodyBuf.toString("UTF-8").trimEnd().endsWith("}"))

            if (complete) {
                val loc = LocationStore.getSelectedLocation(context)
                if (loc != null) {
                    val fake = buildFake(loc.adcode, loc.displayName)
                    val fakeBytes = fake.toByteArray(Charset.forName("UTF-8"))
                    Log.i(TAG, "替换: adcode=${loc.adcode} ${loc.displayName}")
                    response.setHeader("Content-Length", fakeBytes.size.toString())
                    chain.process(request, response, ByteBuffer.wrap(fakeBytes))
                    bodyBuf.reset(); isTarget = false; return
                }
            }
            chain.process(request, response, ByteBuffer.wrap(ByteArray(0)))
        } else {
            chain.process(request, response, buffer)
        }
    }

    private fun buildFake(adcode: String, name: String): String {
        return JSONObject().apply {
            put("status", 0); put("message", "query ok"); put("request_id", "f${System.currentTimeMillis()}")
            put("result", JSONObject().apply {
                put("ad_info", JSONObject().apply {
                    put("adcode", adcode); put("nation", "中国")
                    put("province", ""); put("city", ""); put("district", "")
                })
                put("location", JSONObject().apply { put("lat", 39.9); put("lng", 116.4) })
                put("formatted_addresses", JSONObject().apply { put("recommend", ""); put("rough", "") })
            })
        }.toString()
    }
}
