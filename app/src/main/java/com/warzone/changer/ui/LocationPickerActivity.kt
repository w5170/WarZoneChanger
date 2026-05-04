package com.warzone.changer.ui

import android.app.Activity
import android.os.Bundle
import android.widget.*
import com.warzone.changer.R
import com.warzone.changer.data.LocationStore
import com.warzone.changer.model.SelectedLocation
import org.json.JSONArray

/**
 * 省→市→区 三级战区选择器
 */
class LocationPickerActivity : Activity() {

    private lateinit var listView: ListView
    private lateinit var tvBreadcrumb: TextView
    private lateinit var btnBack: Button

    private var allData: JSONArray? = null
    private var currentLevel = 0 // 0=省, 1=市, 2=区

    private var selectedProvince: String = ""
    private var selectedProvinceAdcode: String = ""
    private var selectedCity: String = ""
    private var selectedCityAdcode: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_location_picker)

        listView = findViewById(R.id.list_locations)
        tvBreadcrumb = findViewById(R.id.tv_breadcrumb)
        btnBack = findViewById(R.id.btn_back)

        btnBack.setOnClickListener { handleBack() }

        // 加载战区数据
        loadWarzoneData()

        // 显示省份列表
        showProvinces()
    }

    private fun loadWarzoneData() {
        try {
            val json = assets.open("warzone.json").bufferedReader().use { it.readText() }
            allData = JSONArray(json)
        } catch (e: Exception) {
            Toast.makeText(this, "加载战区数据失败", e).length.let { Toast.makeText(this, "加载战区数据失败", Toast.LENGTH_SHORT).show() }
            finish()
        }
    }

    private fun showProvinces() {
        currentLevel = 0
        tvBreadcrumb.text = "选择省份"

        val data = allData ?: return
        val names = mutableListOf<String>()
        for (i in 0 until data.length()) {
            names.add(data.getJSONObject(i).getString("province"))
        }

        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
        listView.setOnItemClickListener { _, _, position, _ ->
            val obj = data.getJSONObject(position)
            selectedProvince = obj.getString("province")
            selectedProvinceAdcode = obj.getString("adcode")
            showCities(obj)
        }
    }

    private fun showCities(provinceObj: org.json.JSONObject) {
        currentLevel = 1
        tvBreadcrumb.text = "$selectedProvince > 选择城市"

        val cities = provinceObj.getJSONArray("cities")
        val names = mutableListOf<String>()
        for (i in 0 until cities.length()) {
            names.add(cities.getJSONObject(i).getString("city"))
        }

        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
        listView.setOnItemClickListener { _, _, position, _ ->
            val cityObj = cities.getJSONObject(position)
            selectedCity = cityObj.getString("city")
            selectedCityAdcode = cityObj.getString("adcode")

            val districts = cityObj.optJSONArray("districts")
            if (districts != null && districts.length() > 0) {
                showDistricts(cityObj)
            } else {
                // 没有区级数据，直接使用市级
                saveAndFinish(selectedCity, selectedCityAdcode)
            }
        }
    }

    private fun showDistricts(cityObj: org.json.JSONObject) {
        currentLevel = 2
        tvBreadcrumb.text = "$selectedProvince > $selectedCity > 选择区"

        val districts = cityObj.getJSONArray("districts")
        val names = mutableListOf<String>()
        for (i in 0 until districts.length()) {
            names.add(districts.getJSONObject(i).getString("name"))
        }

        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
        listView.setOnItemClickListener { _, _, position, _ ->
            val dist = districts.getJSONObject(position)
            saveAndFinish(dist.getString("name"), dist.getString("adcode"))
        }
    }

    private fun saveAndFinish(district: String, adcode: String) {
        val location = SelectedLocation(
            province = selectedProvince,
            city = selectedCity,
            district = district,
            adcode = adcode
        )
        LocationStore.saveLocation(this, location)
        Toast.makeText(this, "已选择: ${location.displayName}", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    private fun handleBack() {
        when (currentLevel) {
            0 -> finish()
            1 -> showProvinces()
            2 -> {
                val data = allData ?: return
                for (i in 0 until data.length()) {
                    val obj = data.getJSONObject(i)
                    if (obj.getString("province") == selectedProvince) {
                        showCities(obj)
                        return
                    }
                }
                showProvinces()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        handleBack()
    }
}
