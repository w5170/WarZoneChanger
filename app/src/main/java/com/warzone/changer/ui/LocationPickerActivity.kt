package com.warzone.changer.ui

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.warzone.changer.R
import com.warzone.changer.data.LocationStore
import com.warzone.changer.model.SelectedLocation
import org.json.JSONObject
import java.io.InputStream

class LocationPickerActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var breadcrumb: TextView
    private lateinit var searchBox: EditText

    private val allRegions = mutableListOf<Region>()
    private val displayList = mutableListOf<Region>()
    private var currentLevel = 0 // 0=province, 1=city, 2=district
    private var selectedProvince: Region? = null
    private var selectedCity: Region? = null

    data class Region(
        val adcode: Int,
        val name: String,
        val fullName: String,
        val lat: Double = 0.0,
        val lng: Double = 0.0,
        val children: List<Region> = emptyList()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_location_picker)

        listView = findViewById(R.id.lvRegions)
        breadcrumb = findViewById(R.id.tvBreadcrumb)
        searchBox = findViewById(R.id.etSearch)

        loadRegions()
        showList(0, allRegions)

        searchBox.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                filterByKeyword(s?.toString() ?: "")
            }
        })
    }

    private fun loadRegions() {
        try {
            val input: InputStream = assets.open("warzone.json")
            val json = JSONObject(input.bufferedReader().readText())
            val provinces = json.getJSONArray("provinces")
            for (i in 0 until provinces.length()) {
                val p = provinces.getJSONObject(i)
                val cities = mutableListOf<Region>()
                val cityArr = p.optJSONArray("cities") ?: continue
                for (j in 0 until cityArr.length()) {
                    val c = cityArr.getJSONObject(j)
                    val districts = mutableListOf<Region>()
                    val distArr = c.optJSONArray("districts")
                    if (distArr != null) {
                        for (k in 0 until distArr.length()) {
                            val d = distArr.getJSONObject(k)
                            districts.add(Region(
                                adcode = d.getInt("adcode"),
                                name = d.getString("name"),
                                fullName = d.optString("name", ""),
                                lat = d.optDouble("lat", 0.0),
                                lng = d.optDouble("lng", 0.0)
                            ))
                        }
                    }
                    cities.add(Region(
                        adcode = c.getInt("adcode"),
                        name = c.getString("name"),
                        fullName = c.optString("name", ""),
                        lat = c.optDouble("lat", 0.0),
                        lng = c.optDouble("lng", 0.0),
                        children = districts
                    ))
                }
                allRegions.add(Region(
                    adcode = p.getInt("adcode"),
                    name = p.getString("name"),
                    fullName = p.optString("name", ""),
                    lat = p.optDouble("lat", 0.0),
                    lng = p.optDouble("lng", 0.0),
                    children = cities
                ))
            }
        } catch (e: Exception) {
            Toast.makeText(this, "加载战区数据失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showList(level: Int, list: List<Region>) {
        currentLevel = level
        displayList.clear()
        displayList.addAll(list)

        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1) {
            override fun getCount() = displayList.size
            override fun getItem(position: Int) = displayList[position].name
        }
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val region = displayList[position]
            when (level) {
                0 -> {
                    selectedProvince = region
                    breadcrumb.text = region.name
                    if (region.children.isEmpty()) {
                        saveAndFinish(region)
                    } else {
                        showList(1, region.children)
                    }
                }
                1 -> {
                    selectedCity = region
                    breadcrumb.text = "${selectedProvince?.name} > ${region.name}"
                    if (region.children.isEmpty()) {
                        saveAndFinish(region)
                    } else {
                        showList(2, region.children)
                    }
                }
                2 -> {
                    saveAndFinish(region)
                }
            }
        }
    }

    private fun filterByKeyword(keyword: String) {
        if (keyword.isEmpty()) {
            showList(currentLevel, when (currentLevel) {
                0 -> allRegions
                1 -> selectedProvince?.children ?: emptyList()
                2 -> selectedCity?.children ?: emptyList()
                else -> emptyList()
            })
            return
        }
        val filtered = when (currentLevel) {
            0 -> allRegions.filter { it.name.contains(keyword) }
            1 -> (selectedProvince?.children ?: emptyList()).filter { it.name.contains(keyword) }
            2 -> (selectedCity?.children ?: emptyList()).filter { it.name.contains(keyword) }
            else -> emptyList()
        }
        displayList.clear()
        displayList.addAll(filtered)
        (listView.adapter as? ArrayAdapter<*>)?.notifyDataSetChanged()
    }

    private fun saveAndFinish(region: Region) {
        val province = selectedProvince?.name ?: region.name
        val city = selectedCity?.name ?: region.name
        val district = if (currentLevel == 2) region.name else ""
        val loc = SelectedLocation(
            province = province,
            city = city,
            district = district,
            adcode = region.adcode.toString(),
            latitude = region.lat,
            longitude = region.lng,
            formattedAddress = "$province$city$district"
        )
        LocationStore.save(this, loc)
        Toast.makeText(this, "已选择：${loc.province} ${loc.city} ${loc.district}", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun updateBreadcrumb() {
        val parts = mutableListOf<String>()
        selectedProvince?.let { parts.add(it.name) }
        selectedCity?.let { parts.add(it.name) }
        breadcrumb.text = parts.joinToString(" > ")
    }
}
