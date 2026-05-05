package com.warzone.changer.injector

import android.content.Context
import android.util.Log
import com.github.megatronking.netbare.http.HttpBody
import com.github.megatronking.netbare.http.HttpResponse
import com.github.megatronking.netbare.http.HttpResponseHeaderPart
import com.github.megatronking.netbare.injector.InjectorCallback
import com.github.megatronking.netbare.injector.SimpleHttpInjector
import com.github.megatronking.netbare.stream.ByteStream
import com.warzone.changer.data.LocationStore
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

/**
 * 拦截王者荣耀战区定位请求
 * 
 * 拦截 apis.map.qq.com 的坐标转换响应，将经纬度修改为选定战区的坐标。
 */
class LocationInjector(private val context: Context) : SimpleHttpInjector() {

    companion object {
        private const val TAG = "LocationInjector"
    }

    private var mHoldResponseHeader: HttpResponseHeaderPart? = null

    override fun sniffResponse(response: HttpResponse): Boolean {
        val url = response.url()
        val shouldInject = url.contains("apis.map.qq.com") || url.contains("lbs.map.qq.com")
        if (shouldInject) {
            Log.i(TAG, "匹配到战区API: $url")
        }
        return shouldInject
    }

    override fun onResponseInject(header: HttpResponseHeaderPart, callback: InjectorCallback) {
        // 先 hold 住 header，等 body 修改完后再一起发
        mHoldResponseHeader = header
        Log.i(TAG, "响应头已接收")
    }

    override fun onResponseInject(response: HttpResponse, body: HttpBody, callback: InjectorCallback) {
        if (mHoldResponseHeader == null) {
            Log.w(TAG, "没有hold的header，跳过")
            return
        }

        val location = LocationStore.getSelectedLocation(context)
        if (location == null) {
            Log.w(TAG, "未选择战区，跳过注入")
            callback.onFinished(mHoldResponseHeader)
            callback.onFinished(body)
            mHoldResponseHeader = null
            return
        }

        try {
            // 读取响应体
            val bodyBytes = readBody(body)
            val bodyStr = String(bodyBytes, Charsets.UTF_8)
            Log.i(TAG, "原始响应: ${bodyStr.take(500)}")

            // 修改 JSON 中的坐标
            val modified = modifyLocation(bodyStr, location.latitude, location.longitude)
            if (modified != null) {
                val modifiedBytes = modified.toByteArray(Charsets.UTF_8)

                // 更新 Content-Length
                val newHeader = mHoldResponseHeader!!
                    .newBuilder()
                    .replaceHeader("Content-Length", modifiedBytes.size.toString())
                    .build()

                callback.onFinished(newHeader)
                callback.onFinished(ByteStream(modifiedBytes))
                Log.i(TAG, "注入完成! 坐标: ${location.latitude}, ${location.longitude}")
            } else {
                // 修改失败，透传原始数据
                Log.w(TAG, "JSON修改失败，透传原始响应")
                callback.onFinished(mHoldResponseHeader)
                callback.onFinished(ByteStream(bodyBytes))
            }
        } catch (e: Exception) {
            Log.e(TAG, "注入异常", e)
            try {
                callback.onFinished(mHoldResponseHeader)
                callback.onFinished(body)
            } catch (ignored: Exception) {}
        } finally {
            mHoldResponseHeader = null
        }
    }

    private fun readBody(body: HttpBody): ByteArray {
        val inputStream = com.github.megatronking.netbare.io.HttpBodyInputStream(body)
        // 尝试 gzip 解压
        return try {
            val gzip = GZIPInputStream(inputStream)
            val buffer = ByteArrayOutputStream()
            val data = ByteArray(4096)
            var len: Int
            while (gzip.read(data).also { len = it } != -1) {
                buffer.write(data, 0, len)
            }
            buffer.toByteArray()
        } catch (e: Exception) {
            // 非 gzip，直接读取
            val buffer = ByteArrayOutputStream()
            val data = ByteArray(4096)
            var len: Int
            while (inputStream.read(data).also { len = it } != -1) {
                buffer.write(data, 0, len)
            }
            buffer.toByteArray()
        }
    }

    private fun modifyLocation(jsonStr: String, lat: Double, lng: Double): String? {
        return try {
            val json = JSONObject(jsonStr)

            // 修改各种可能的坐标字段
            modifyLatLng(json, lat, lng)

            // 处理嵌套的 result 对象
            json.optJSONObject("result")?.let { result ->
                modifyLatLng(result, lat, lng)
                result.optJSONObject("location")?.let { modifyLatLng(it, lat, lng) }
                result.optJSONObject("ad_info")?.let { adInfo ->
                    modifyLatLng(adInfo, lat, lng)
                }
            }

            // 处理 location 字段
            json.optJSONObject("location")?.let { modifyLatLng(it, lat, lng) }

            // 处理 ad_info 字段
            json.optJSONObject("ad_info")?.let { modifyLatLng(it, lat, lng) }

            // 处理数组格式
            json.optJSONArray("results")?.let { results ->
                for (i in 0 until results.length()) {
                    results.optJSONObject(i)?.let { item ->
                        modifyLatLng(item, lat, lng)
                        item.optJSONObject("location")?.let { modifyLatLng(it, lat, lng) }
                    }
                }
            }

            json.toString()
        } catch (e: Exception) {
            Log.e(TAG, "JSON解析失败", e)
            null
        }
    }

    private fun modifyLatLng(obj: JSONObject, lat: Double, lng: Double) {
        if (obj.has("lat") || obj.has("lng")) {
            obj.put("lat", lat)
            obj.put("lng", lng)
        }
        if (obj.has("latitude") || obj.has("longitude")) {
            obj.put("latitude", lat)
            obj.put("longitude", lng)
        }
    }
}
