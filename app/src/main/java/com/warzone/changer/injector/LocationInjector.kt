package com.warzone.changer.injector

import android.content.Context
import android.util.Log
import com.github.megatronking.netbare.http.HttpBody
import com.github.megatronking.netbare.http.HttpRawBody
import com.github.megatronking.netbare.http.HttpRequest
import com.github.megatronking.netbare.http.HttpRequestHeaderPart
import com.github.megatronking.netbare.http.HttpResponse
import com.github.megatronking.netbare.http.HttpResponseHeaderPart
import com.github.megatronking.netbare.injector.InjectorCallback
import com.github.megatronking.netbare.injector.SimpleHttpInjector
import com.warzone.changer.data.LocationStore
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset

/**
 * 腾讯地图API响应拦截器 (使用标准NetBare SimpleHttpInjector API)
 *
 * 核心原理：
 * 1. 王者荣耀请求 http://apis.map.qq.com/ws/geocoder/v1 走HTTP明文
 * 2. VPN拦截后，替换响应中的 adcode 为目标区域编码
 * 3. 游戏读到修改后的 adcode → 设置战区
 */
class LocationInjector(private val context: Context) : SimpleHttpInjector() {

    companion object {
        private const val TAG = "LocationInjector"
        private const val TARGET_HOST = "apis.map.qq.com"
        private const val TARGET_PATH = "/ws/geocoder/v1"
    }

    private val bodyAccumulator = ByteArrayOutputStream()
    private var isTarget = false
    private var contentLength = 0
    private var responseCallback: InjectorCallback? = null
    private var currentResponse: HttpResponse? = null

    override fun sniffRequest(request: HttpRequest): Boolean {
        val host = request.host()
        val url = request.url()
        val match = host != null && url != null &&
                host.contains(TARGET_HOST) && url.contains(TARGET_PATH)
        if (match) {
            Log.i(TAG, "✓ 识别到腾讯地图API请求: $host$url")
            isTarget = true
            bodyAccumulator.reset()
            contentLength = 0
        }
        return match
    }

    override fun sniffResponse(response: HttpResponse): Boolean {
        return isTarget
    }

    override fun onRequestInject(
        header: HttpRequestHeaderPart,
        callback: InjectorCallback
    ) {
        // 直接传递原始请求头
        callback.onFinished(header)
    }

    override fun onResponseInject(
        header: HttpResponseHeaderPart,
        callback: InjectorCallback
    ) {
        // 从响应头获取Content-Length
        val headers = header.headers()
        val clValues = headers["Content-Length"]
        if (clValues != null && clValues.isNotEmpty()) {
            contentLength = clValues[0].toIntOrNull() ?: 0
        }
        Log.i(TAG, "响应头: Content-Length=$contentLength, code=${header.code()}")
        // 传递原始响应头
        callback.onFinished(header)
    }

    override fun onResponseInject(
        response: HttpResponse,
        body: HttpBody,
        callback: InjectorCallback
    ) {
        if (!isTarget) {
            callback.onFinished(body)
            return
        }

        // 累积响应体
        val buffer = body.toBuffer()
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        bodyAccumulator.write(bytes)

        Log.i(TAG, "收到响应体: ${bytes.size} bytes, 累积: ${bodyAccumulator.size()} bytes")

        // 检查是否收到完整响应
        val complete = if (contentLength > 0) {
            bodyAccumulator.size() >= contentLength
        } else {
            // 没有Content-Length，尝试判断JSON是否完整
            val text = bodyAccumulator.toString("UTF-8")
            text.trimEnd().endsWith("}")
        }

        if (complete) {
            // 替换响应内容
            val location = LocationStore.getSelectedLocation(context)
            if (location != null) {
                val fakeBody = buildFakeResponse(location.adcode, location.name)
                val fakeBytes = fakeBody.toByteArray(Charset.forName("UTF-8"))
                Log.i(TAG, "→ 替换响应: adcode=${location.adcode}, 区域=${location.name}")

                callback.onFinished(HttpRawBody(ByteBuffer.wrap(fakeBytes)))
            } else {
                // 没有选择区域，返回原始响应
                callback.onFinished(HttpRawBody(ByteBuffer.wrap(bodyAccumulator.toByteArray())))
            }
            // 重置状态
            bodyAccumulator.reset()
            isTarget = false
            contentLength = 0
        } else {
            // 还没收完，发送空数据继续累积
            callback.onFinished(HttpRawBody(ByteBuffer.allocate(0)))
        }
    }

    override fun onResponseFinished(response: HttpResponse) {
        // 响应结束时重置状态
        if (isTarget) {
            Log.w(TAG, "响应结束但未完整替换，重置状态")
            bodyAccumulator.reset()
            isTarget = false
            contentLength = 0
        }
    }

    /**
     * 构造假的腾讯地图API响应
     * 格式与真实API一致，只修改 adcode 字段
     */
    private fun buildFakeResponse(adcode: String, regionName: String): String {
        val province = getProvinceByAdcode(adcode)
        val city = getCityByAdcode(adcode)
        val lat = getLatByAdcode(adcode)
        val lng = getLngByAdcode(adcode)

        val response = JSONObject().apply {
            put("status", 0)
            put("message", "query ok")
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
                        put("lat", lat)
                        put("lng", lng)
                    })
                    put("nation", "中国")
                    put("province", province)
                    put("city", city)
                    put("district", regionName)
                })

                put("location", JSONObject().apply {
                    put("lat", lat)
                    put("lng", lng)
                })
                put("formatted_addresses", JSONObject().apply {
                    put("recommend", "")
                    put("rough", "")
                })
                put("address_reference", JSONObject())
            })
        }

        return response.toString()
    }

    private fun generateRequestId(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..16).map { chars.random() }.joinToString("")
    }

    private fun getProvinceByAdcode(adcode: String): String {
        val provinceCode = adcode.take(2)
        return when (provinceCode) {
            "11" -> "北京市"
            "12" -> "天津市"
            "13" -> "河北省"
            "14" -> "山西省"
            "15" -> "内蒙古自治区"
            "21" -> "辽宁省"
            "22" -> "吉林省"
            "23" -> "黑龙江省"
            "31" -> "上海市"
            "32" -> "江苏省"
            "33" -> "浙江省"
            "34" -> "安徽省"
            "35" -> "福建省"
            "36" -> "江西省"
            "37" -> "山东省"
            "41" -> "河南省"
            "42" -> "湖北省"
            "43" -> "湖南省"
            "44" -> "广东省"
            "45" -> "广西壮族自治区"
            "46" -> "海南省"
            "50" -> "重庆市"
            "51" -> "四川省"
            "52" -> "贵州省"
            "53" -> "云南省"
            "54" -> "西藏自治区"
            "61" -> "陕西省"
            "62" -> "甘肃省"
            "63" -> "青海省"
            "64" -> "宁夏回族自治区"
            "65" -> "新疆维吾尔自治区"
            "71" -> "台湾省"
            "81" -> "香港特别行政区"
            "82" -> "澳门特别行政区"
            else -> "未知"
        }
    }

    private fun getCityByAdcode(adcode: String): String {
        val cityCode = adcode.take(4)
        return when (cityCode) {
            "1101" -> "北京市"
            "1201" -> "天津市"
            "1301" -> "石家庄市"
            "1302" -> "唐山市"
            "1303" -> "秦皇岛市"
            "1304" -> "邯郸市"
            "1305" -> "邢台市"
            "1306" -> "保定市"
            "1307" -> "张家口市"
            "1308" -> "承德市"
            "1309" -> "沧州市"
            "1310" -> "廊坊市"
            "1311" -> "衡水市"
            "1401" -> "太原市"
            "1402" -> "大同市"
            "1403" -> "阳泉市"
            "1404" -> "长治市"
            "1405" -> "晋城市"
            "1406" -> "朔州市"
            "1407" -> "晋中市"
            "1408" -> "运城市"
            "1409" -> "忻州市"
            "1410" -> "临汾市"
            "1411" -> "吕梁市"
            "1501" -> "呼和浩特市"
            "1502" -> "包头市"
            "1503" -> "乌海市"
            "1504" -> "赤峰市"
            "1505" -> "通辽市"
            "1506" -> "鄂尔多斯市"
            "1507" -> "呼伦贝尔市"
            "1508" -> "巴彦淖尔市"
            "1509" -> "乌兰察布市"
            "2101" -> "沈阳市"
            "2102" -> "大连市"
            "2103" -> "鞍山市"
            "2104" -> "抚顺市"
            "2105" -> "本溪市"
            "2106" -> "丹东市"
            "2107" -> "锦州市"
            "2108" -> "营口市"
            "2109" -> "阜新市"
            "2110" -> "辽阳市"
            "2111" -> "盘锦市"
            "2112" -> "铁岭市"
            "2113" -> "朝阳市"
            "2114" -> "葫芦岛市"
            "2201" -> "长春市"
            "2202" -> "吉林市"
            "2203" -> "四平市"
            "2204" -> "辽源市"
            "2205" -> "通化市"
            "2206" -> "白山市"
            "2207" -> "松原市"
            "2208" -> "白城市"
            "2301" -> "哈尔滨市"
            "2302" -> "齐齐哈尔市"
            "2303" -> "鸡西市"
            "2304" -> "鹤岗市"
            "2305" -> "双鸭山市"
            "2306" -> "大庆市"
            "2307" -> "伊春市"
            "2308" -> "佳木斯市"
            "2309" -> "七台河市"
            "2310" -> "牡丹江市"
            "2311" -> "黑河市"
            "2312" -> "绥化市"
            "3101" -> "上海市"
            "3201" -> "南京市"
            "3202" -> "无锡市"
            "3203" -> "徐州市"
            "3204" -> "常州市"
            "3205" -> "苏州市"
            "3206" -> "南通市"
            "3207" -> "连云港市"
            "3208" -> "淮安市"
            "3209" -> "盐城市"
            "3210" -> "扬州市"
            "3211" -> "镇江市"
            "3212" -> "泰州市"
            "3213" -> "宿迁市"
            "3301" -> "杭州市"
            "3302" -> "宁波市"
            "3303" -> "温州市"
            "3304" -> "嘉兴市"
            "3305" -> "湖州市"
            "3306" -> "绍兴市"
            "3307" -> "金华市"
            "3308" -> "衢州市"
            "3309" -> "舟山市"
            "3310" -> "台州市"
            "3311" -> "丽水市"
            "3401" -> "合肥市"
            "3402" -> "芜湖市"
            "3403" -> "蚌埠市"
            "3404" -> "淮南市"
            "3405" -> "马鞍山市"
            "3406" -> "淮北市"
            "3407" -> "铜陵市"
            "3408" -> "安庆市"
            "3410" -> "黄山市"
            "3411" -> "滁州市"
            "3412" -> "阜阳市"
            "3413" -> "宿州市"
            "3415" -> "六安市"
            "3416" -> "亳州市"
            "3417" -> "池州市"
            "3418" -> "宣城市"
            "3501" -> "福州市"
            "3502" -> "厦门市"
            "3503" -> "莆田市"
            "3504" -> "三明市"
            "3505" -> "泉州市"
            "3506" -> "漳州市"
            "3507" -> "南平市"
            "3508" -> "龙岩市"
            "3509" -> "宁德市"
            "3601" -> "南昌市"
            "3602" -> "景德镇市"
            "3603" -> "萍乡市"
            "3604" -> "九江市"
            "3605" -> "新余市"
            "3606" -> "鹰潭市"
            "3607" -> "赣州市"
            "3608" -> "吉安市"
            "3609" -> "宜春市"
            "3610" -> "抚州市"
            "3611" -> "上饶市"
            "3701" -> "济南市"
            "3702" -> "青岛市"
            "3703" -> "淄博市"
            "3704" -> "枣庄市"
            "3705" -> "东营市"
            "3706" -> "烟台市"
            "3707" -> "潍坊市"
            "3708" -> "济宁市"
            "3709" -> "泰安市"
            "3710" -> "威海市"
            "3711" -> "日照市"
            "3713" -> "临沂市"
            "3714" -> "德州市"
            "3715" -> "聊城市"
            "3716" -> "滨州市"
            "3717" -> "菏泽市"
            "4101" -> "郑州市"
            "4102" -> "开封市"
            "4103" -> "洛阳市"
            "4104" -> "平顶山市"
            "4105" -> "安阳市"
            "4106" -> "鹤壁市"
            "4107" -> "新乡市"
            "4108" -> "焦作市"
            "4109" -> "濮阳市"
            "4110" -> "许昌市"
            "4111" -> "漯河市"
            "4112" -> "三门峡市"
            "4113" -> "南阳市"
            "4114" -> "商丘市"
            "4115" -> "信阳市"
            "4116" -> "周口市"
            "4117" -> "驻马店市"
            "4201" -> "武汉市"
            "4202" -> "黄石市"
            "4203" -> "十堰市"
            "4205" -> "宜昌市"
            "4206" -> "襄阳市"
            "4207" -> "鄂州市"
            "4208" -> "荆门市"
            "4209" -> "孝感市"
            "4210" -> "荆州市"
            "4211" -> "黄冈市"
            "4212" -> "咸宁市"
            "4213" -> "随州市"
            "4301" -> "长沙市"
            "4302" -> "株洲市"
            "4303" -> "湘潭市"
            "4304" -> "衡阳市"
            "4305" -> "邵阳市"
            "4306" -> "岳阳市"
            "4307" -> "常德市"
            "4308" -> "张家界市"
            "4309" -> "益阳市"
            "4310" -> "郴州市"
            "4311" -> "永州市"
            "4312" -> "怀化市"
            "4313" -> "娄底市"
            "4401" -> "广州市"
            "4402" -> "韶关市"
            "4403" -> "深圳市"
            "4404" -> "珠海市"
            "4405" -> "汕头市"
            "4406" -> "佛山市"
            "4407" -> "江门市"
            "4408" -> "湛江市"
            "4409" -> "茂名市"
            "4412" -> "肇庆市"
            "4413" -> "惠州市"
            "4414" -> "梅州市"
            "4415" -> "汕尾市"
            "4416" -> "河源市"
            "4417" -> "阳江市"
            "4418" -> "清远市"
            "4419" -> "东莞市"
            "4420" -> "中山市"
            "4451" -> "潮州市"
            "4452" -> "揭阳市"
            "4453" -> "云浮市"
            "4501" -> "南宁市"
            "4502" -> "柳州市"
            "4503" -> "桂林市"
            "4504" -> "梧州市"
            "4505" -> "北海市"
            "4506" -> "防城港市"
            "4507" -> "钦州市"
            "4508" -> "贵港市"
            "4509" -> "玉林市"
            "4510" -> "百色市"
            "4511" -> "贺州市"
            "4512" -> "河池市"
            "4513" -> "来宾市"
            "4514" -> "崇左市"
            "4601" -> "海口市"
            "4602" -> "三亚市"
            "4603" -> "三沙市"
            "4604" -> "儋州市"
            "5001" -> "重庆市"
            "5101" -> "成都市"
            "5103" -> "自贡市"
            "5104" -> "攀枝花市"
            "5105" -> "泸州市"
            "5106" -> "德阳市"
            "5107" -> "绵阳市"
            "5108" -> "广元市"
            "5109" -> "遂宁市"
            "5110" -> "内江市"
            "5111" -> "乐山市"
            "5113" -> "南充市"
            "5114" -> "眉山市"
            "5115" -> "宜宾市"
            "5116" -> "广安市"
            "5117" -> "达州市"
            "5118" -> "雅安市"
            "5119" -> "巴中市"
            "5120" -> "资阳市"
            "5201" -> "贵阳市"
            "5202" -> "六盘水市"
            "5203" -> "遵义市"
            "5204" -> "安顺市"
            "5205" -> "毕节市"
            "5206" -> "铜仁市"
            "5301" -> "昆明市"
            "5303" -> "曲靖市"
            "5304" -> "玉溪市"
            "5305" -> "保山市"
            "5306" -> "昭通市"
            "5307" -> "丽江市"
            "5308" -> "普洱市"
            "5309" -> "临沧市"
            "5401" -> "拉萨市"
            "5402" -> "日喀则市"
            "5403" -> "昌都市"
            "5404" -> "林芝市"
            "5405" -> "山南市"
            "5406" -> "那曲市"
            "6101" -> "西安市"
            "6102" -> "铜川市"
            "6103" -> "宝鸡市"
            "6104" -> "咸阳市"
            "6105" -> "渭南市"
            "6106" -> "延安市"
            "6107" -> "汉中市"
            "6108" -> "榆林市"
            "6109" -> "安康市"
            "6110" -> "商洛市"
            "6201" -> "兰州市"
            "6202" -> "嘉峪关市"
            "6203" -> "金昌市"
            "6204" -> "白银市"
            "6205" -> "天水市"
            "6206" -> "武威市"
            "6207" -> "张掖市"
            "6208" -> "平凉市"
            "6209" -> "酒泉市"
            "6210" -> "庆阳市"
            "6211" -> "定西市"
            "6212" -> "陇南市"
            "6301" -> "西宁市"
            "6302" -> "海东市"
            "6401" -> "银川市"
            "6402" -> "石嘴山市"
            "6403" -> "吴忠市"
            "6404" -> "固原市"
            "6405" -> "中卫市"
            "6501" -> "乌鲁木齐市"
            "6502" -> "克拉玛依市"
            "6504" -> "吐鲁番市"
            "6505" -> "哈密市"
            else -> "未知城市"
        }
    }

    private fun getLatByAdcode(adcode: String): Double {
        val provinceCode = adcode.take(2)
        return when (provinceCode) {
            "11" -> 39.9042
            "12" -> 39.1255
            "13" -> 38.0428
            "14" -> 37.8706
            "15" -> 40.8183
            "21" -> 41.8057
            "22" -> 43.8868
            "23" -> 45.7420
            "31" -> 31.2304
            "32" -> 32.0617
            "33" -> 30.2741
            "34" -> 31.8612
            "35" -> 26.0745
            "36" -> 28.6820
            "37" -> 36.6683
            "41" -> 34.7657
            "42" -> 30.5928
            "43" -> 28.2282
            "44" -> 23.1317
            "45" -> 22.8170
            "46" -> 20.0174
            "50" -> 29.5630
            "51" -> 30.5723
            "52" -> 26.6470
            "53" -> 25.0389
            "54" -> 29.6500
            "61" -> 34.2658
            "62" -> 36.0594
            "63" -> 36.6171
            "64" -> 38.4872
            "65" -> 43.8256
            else -> 39.9042
        }
    }

    private fun getLngByAdcode(adcode: String): Double {
        val provinceCode = adcode.take(2)
        return when (provinceCode) {
            "11" -> 116.4074
            "12" -> 117.1905
            "13" -> 114.5149
            "14" -> 112.5489
            "15" -> 111.7655
            "21" -> 123.4315
            "22" -> 125.3245
            "23" -> 126.6424
            "31" -> 121.4737
            "32" -> 118.7969
            "33" -> 120.1551
            "34" -> 117.2840
            "35" -> 119.2965
            "36" -> 115.9100
            "37" -> 117.0204
            "41" -> 113.6254
            "42" -> 114.3419
            "43" -> 112.9388
            "44" -> 113.2664
            "45" -> 108.3275
            "46" -> 110.3492
            "50" -> 106.5516
            "51" -> 104.0668
            "52" -> 106.7135
            "53" -> 102.7103
            "54" -> 91.1322
            "61" -> 108.9541
            "62" -> 103.8343
            "63" -> 101.7782
            "64" -> 106.2586
            "65" -> 87.6168
            else -> 116.4074
        }
    }
}
