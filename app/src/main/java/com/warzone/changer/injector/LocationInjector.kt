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

/**
 * 拦截王者荣耀战区定位请求
 * 
 * 核心原理：
 * 1. 王者荣耀请求 http://apis.map.qq.com 走HTTP明文
 * 2. VPN拦截后，构建假响应，替换 adcode 为目标区域编码
 * 3. 游戏读到修改后的 adcode → 设置战区
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
            Log.i(TAG, "✓ 匹配到腾讯地图API: $url")
        }
        return shouldInject
    }

    override fun onResponseInject(header: HttpResponseHeaderPart, callback: InjectorCallback) {
        mHoldResponseHeader = header
    }

    override fun onResponseInject(response: HttpResponse, body: HttpBody, callback: InjectorCallback) {
        if (mHoldResponseHeader == null) return

        val location = LocationStore.getSelectedLocation(context)
        if (location == null) {
            Log.w(TAG, "未选择战区，透传原始响应")
            callback.onFinished(mHoldResponseHeader)
            callback.onFinished(body)
            mHoldResponseHeader = null
            return
        }

        try {
            // 构造假响应
            val fakeBody = buildFakeResponse(location.adcode, location.district)
            val fakeBytes = fakeBody.toByteArray(Charsets.UTF_8)

            // 更新 Content-Length
            val newHeader = mHoldResponseHeader!!
                .newBuilder()
                .replaceHeader("Content-Length", fakeBytes.size.toString())
                .build()

            callback.onFinished(newHeader)
            callback.onFinished(ByteStream(fakeBytes))

            Log.i(TAG, "→ 替换响应: adcode=${location.adcode}, 区域=${location.district}")
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

    /**
     * 构造假的腾讯地图API响应
     * 格式与真实API一致，修改 adcode 和坐标字段
     */
    private fun buildFakeResponse(adcode: String, regionName: String): String {
        val latLng = getLocationByAdcode(adcode)
        val province = getProvinceByAdcode(adcode)

        val response = JSONObject().apply {
            put("status", 0)
            put("message", "Success")
            put("request_id", generateRequestId())

            put("result", JSONObject().apply {
                put("address_components", JSONObject().apply {
                    put("nation", "中国")
                    put("province", province)
                    put("city", regionName)
                    put("district", regionName)
                    put("street", "")
                    put("street_number", "")
                })

                put("ad_info", JSONObject().apply {
                    put("nation_code", "156")
                    put("adcode", adcode)           // ★ 核心字段
                    put("city_code", adcode.take(4) + "00")
                    put("name", regionName)
                    put("location", latLng)
                    put("nation", "中国")
                    put("province", province)
                    put("city", regionName)
                    put("district", regionName)
                })

                put("location", latLng)
                put("formatted_addresses", JSONObject().apply {
                    put("recommend", "")
                    put("rough", "")
                })
                put("address_reference", JSONObject())
            })
        }

        return response.toString()
    }

    private fun getLocationByAdcode(adcode: String): JSONObject {
        val latLng = when {
            adcode.startsWith("11") -> Pair(39.9042, 116.4074)   // 北京
            adcode.startsWith("31") -> Pair(31.2304, 121.4737)   // 上海
            adcode.startsWith("44") -> Pair(23.1291, 113.2644)   // 广东
            adcode.startsWith("33") -> Pair(30.2741, 120.1551)   // 浙江
            adcode.startsWith("32") -> Pair(32.0617, 118.7778)   // 江苏
            adcode.startsWith("51") -> Pair(30.5728, 104.0668)   // 四川
            adcode.startsWith("50") -> Pair(29.5630, 106.5516)   // 重庆
            adcode.startsWith("42") -> Pair(30.5928, 114.3055)   // 湖北
            adcode.startsWith("43") -> Pair(28.2282, 112.9388)   // 湖南
            adcode.startsWith("35") -> Pair(26.0745, 119.2965)   // 福建
            adcode.startsWith("36") -> Pair(28.6820, 115.8579)   // 江西
            adcode.startsWith("34") -> Pair(31.8612, 117.2830)   // 安徽
            adcode.startsWith("37") -> Pair(36.6683, 116.9972)   // 山东
            adcode.startsWith("41") -> Pair(34.7472, 113.6254)   // 河南
            adcode.startsWith("13") -> Pair(38.0428, 114.5149)   // 河北
            adcode.startsWith("14") -> Pair(37.8706, 112.5489)   // 山西
            adcode.startsWith("21") -> Pair(41.8057, 123.4315)   // 辽宁
            adcode.startsWith("22") -> Pair(43.8868, 125.3245)   // 吉林
            adcode.startsWith("23") -> Pair(45.8038, 126.5350)   // 黑龙江
            adcode.startsWith("15") -> Pair(40.8183, 111.7656)   // 内蒙古
            adcode.startsWith("61") -> Pair(34.2658, 108.9541)   // 陕西
            adcode.startsWith("62") -> Pair(36.0594, 103.8343)   // 甘肃
            adcode.startsWith("63") -> Pair(36.6171, 101.7782)   // 青海
            adcode.startsWith("64") -> Pair(38.4872, 106.2309)   // 宁夏
            adcode.startsWith("65") -> Pair(43.7930, 87.6271)    // 新疆
            adcode.startsWith("53") -> Pair(25.0389, 102.7183)   // 云南
            adcode.startsWith("52") -> Pair(26.6470, 106.6302)   // 贵州
            adcode.startsWith("45") -> Pair(22.8170, 108.3665)   // 广西
            adcode.startsWith("46") -> Pair(20.0174, 110.3492)   // 海南
            adcode.startsWith("54") -> Pair(29.6500, 91.1000)    // 西藏
            else -> Pair(39.9042, 116.4074)
        }
        return JSONObject().apply {
            put("lat", latLng.first)
            put("lng", latLng.second)
        }
    }

    private fun getProvinceByAdcode(adcode: String): String {
        return when {
            adcode.startsWith("11") -> "北京市"
            adcode.startsWith("31") -> "上海市"
            adcode.startsWith("44") -> "广东省"
            adcode.startsWith("33") -> "浙江省"
            adcode.startsWith("32") -> "江苏省"
            adcode.startsWith("51") -> "四川省"
            adcode.startsWith("50") -> "重庆市"
            adcode.startsWith("42") -> "湖北省"
            adcode.startsWith("43") -> "湖南省"
            adcode.startsWith("35") -> "福建省"
            adcode.startsWith("36") -> "江西省"
            adcode.startsWith("34") -> "安徽省"
            adcode.startsWith("37") -> "山东省"
            adcode.startsWith("41") -> "河南省"
            adcode.startsWith("13") -> "河北省"
            adcode.startsWith("14") -> "山西省"
            adcode.startsWith("21") -> "辽宁省"
            adcode.startsWith("22") -> "吉林省"
            adcode.startsWith("23") -> "黑龙江省"
            adcode.startsWith("15") -> "内蒙古自治区"
            adcode.startsWith("61") -> "陕西省"
            adcode.startsWith("62") -> "甘肃省"
            adcode.startsWith("63") -> "青海省"
            adcode.startsWith("64") -> "宁夏回族自治区"
            adcode.startsWith("65") -> "新疆维吾尔自治区"
            adcode.startsWith("53") -> "云南省"
            adcode.startsWith("52") -> "贵州省"
            adcode.startsWith("45") -> "广西壮族自治区"
            adcode.startsWith("46") -> "海南省"
            adcode.startsWith("54") -> "西藏自治区"
            else -> "未知"
        }
    }

    private fun generateRequestId(): String {
        val chars = "abcdef0123456789"
        return (1..16).map { chars.random() }.joinToString("")
    }
}
