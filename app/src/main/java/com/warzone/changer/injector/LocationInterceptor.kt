package com.warzone.changer.injector

import android.content.Context
import android.util.Log
import com.warzone.changer.data.LocationStore
import org.json.JSONObject

/**
 * 拦截响应构造器（替代 NetBare 的 HttpResponseBodyInterceptor）
 *
 * 当 SOCKS5 代理检测到目标请求时，调用本类生成假的腾讯地图 API 响应。
 */
object LocationInterceptor {

    private const val TAG = "LocationInterceptor"

    /**
     * 构造假的腾讯地图 API 响应，替换真实 adcode
     */
    fun buildFakeResponse(context: Context): String {
        val location = LocationStore.getSelectedLocation(context)
        if (location == null) {
            Log.w(TAG, "未选择战区，返回默认响应")
            return buildDefaultResponse()
        }

        val adcode = location.adcode
        val regionName = location.name
        val coords = getCoordinates(adcode)
        val province = getProvince(adcode)
        val city = getCity(adcode)

        Log.i(TAG, "★ 构造假响应: adcode=$adcode, 区域=$regionName")

        return JSONObject().apply {
            put("status", 0)
            put("message", "Success")
            put("request_id", generateRequestId())
            put("result", JSONObject().apply {
                put("address_components", JSONObject().apply {
                    put("nation", "中国")
                    put("province", province)
                    put("city", city)
                    put("district", regionName)
                    put("street", "")
                    put("street_number", "")
                })
                put("ad_info", JSONObject().apply {
                    put("nation_code", "156")
                    put("adcode", adcode)
                    put("city_code", adcode.take(4) + "00")
                    put("name", regionName)
                    put("location", JSONObject().apply {
                        put("lat", coords.first)
                        put("lng", coords.second)
                    })
                    put("nation", "中国")
                    put("province", province)
                    put("city", city)
                    put("district", regionName)
                })
                put("location", JSONObject().apply {
                    put("lat", coords.first)
                    put("lng", coords.second)
                })
                put("formatted_addresses", JSONObject().apply {
                    put("recommend", "")
                    put("rough", "")
                })
                put("address_reference", JSONObject())
            })
        }.toString()
    }

    private fun buildDefaultResponse(): String {
        return JSONObject().apply {
            put("status", 0)
            put("message", "Success")
            put("result", JSONObject().apply {
                put("ad_info", JSONObject().apply { put("adcode", "110100") })
            })
        }.toString()
    }

    private fun getCoordinates(adcode: String): Pair<Double, Double> = when {
        adcode.startsWith("11") -> 39.9042 to 116.4074   // 北京
        adcode.startsWith("31") -> 31.2304 to 121.4737   // 上海
        adcode.startsWith("44") -> 23.1291 to 113.2644   // 广东
        adcode.startsWith("33") -> 30.2741 to 120.1551   // 浙江
        adcode.startsWith("32") -> 32.0617 to 118.7778   // 江苏
        adcode.startsWith("51") -> 30.5728 to 104.0668   // 四川
        adcode.startsWith("50") -> 29.5630 to 106.5516   // 重庆
        adcode.startsWith("42") -> 30.5928 to 114.3055   // 湖北
        adcode.startsWith("43") -> 28.2282 to 112.9388   // 湖南
        adcode.startsWith("35") -> 26.0745 to 119.2965   // 福建
        adcode.startsWith("36") -> 28.6820 to 115.8579   // 江西
        adcode.startsWith("34") -> 31.8612 to 117.2830   // 安徽
        adcode.startsWith("37") -> 36.6683 to 116.9972   // 山东
        adcode.startsWith("41") -> 34.7472 to 113.6254   // 河南
        adcode.startsWith("13") -> 38.0428 to 114.5149   // 河北
        adcode.startsWith("14") -> 37.8706 to 112.5489   // 山西
        adcode.startsWith("21") -> 41.8057 to 123.4315   // 辽宁
        adcode.startsWith("22") -> 43.8868 to 125.3245   // 吉林
        adcode.startsWith("23") -> 45.8038 to 126.5350   // 黑龙江
        adcode.startsWith("15") -> 40.8183 to 111.7656   // 内蒙古
        adcode.startsWith("61") -> 34.2658 to 108.9541   // 陕西
        adcode.startsWith("62") -> 36.0594 to 103.8343   // 甘肃
        adcode.startsWith("63") -> 36.6171 to 101.7782   // 青海
        adcode.startsWith("64") -> 38.4872 to 106.2309   // 宁夏
        adcode.startsWith("65") -> 43.7930 to 87.6271    // 新疆
        adcode.startsWith("53") -> 25.0389 to 102.7183   // 云南
        adcode.startsWith("52") -> 26.6470 to 106.6302   // 贵州
        adcode.startsWith("45") -> 22.8170 to 108.3665   // 广西
        adcode.startsWith("46") -> 20.0174 to 110.3492   // 海南
        adcode.startsWith("54") -> 29.6500 to 91.1000    // 西藏
        else -> 39.9042 to 116.4074
    }

    private fun getProvince(adcode: String): String = when {
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

    private fun getCity(adcode: String): String = "城市"

    private fun generateRequestId(): String {
        val chars = "abcdef0123456789"
        return (1..16).map { chars.random() }.joinToString("")
    }
}
