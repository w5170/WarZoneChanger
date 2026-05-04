package com.warzone.changer.ui

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.warzone.changer.R
import com.warzone.changer.data.LocationStore
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 三级地区选择器
 */
class LocationPickerActivity : Activity() {

    data class Region(val name: String, val adcode: String, val children: List<Region> = emptyList()) {
        override fun toString() = name
    }

    private lateinit var tvTitle: TextView
    private lateinit var tvCurrent: TextView
    private lateinit var listView: ListView
    private lateinit var progress: ProgressBar
    private var allData: List<Region> = emptyList()
    private var stack = mutableListOf<List<Region>>()
    private var selectedPath = mutableListOf<String>()
    private var selectedAdcode = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_location_picker)
        tvTitle = findViewById(R.id.tvTitle)
        tvCurrent = findViewById(R.id.tvCurrent)
        listView = findViewById(R.id.listLocations)
        progress = findViewById(R.id.progressLoad)

        findViewById<View>(R.id.btnBack).setOnClickListener { onBackPressed() }

        progress.visibility = View.VISIBLE
        Thread {
            try {
                val data = loadWarzoneData()
                runOnUiThread {
                    allData = data
                    showList(data, "请选择省份")
                    progress.visibility = View.GONE
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "加载战区数据失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    progress.visibility = View.GONE
                }
            }
        }.start()
    }

    private fun showList(items: List<Region>, hint: String) {
        val adapter = object : ArrayAdapter<Region>(this, R.layout.item_list, R.id.tv_name, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_list, parent, false)
                view.findViewById<TextView>(R.id.tv_name).text = items[position].name
                return view
            }
        }
        listView.adapter = adapter
        tvTitle.text = hint
        tvCurrent.text = if (selectedPath.isEmpty()) "当前: 未选择" else "当前: ${selectedPath.joinToString(" → ")}"
        listView.setOnItemClickListener { _, _, position, _ ->
            val item = items[position]
            if (item.children.isNotEmpty()) {
                stack.add(items)
                selectedPath.add(item.name)
                val hint = when (stack.size) {
                    1 -> "请选择城市"
                    2 -> "请选择区县"
                    else -> "请选择"
                }
                showList(item.children, hint)
            } else {
                selectedAdcode = item.adcode
                selectedPath.add(item.name)
                LocationStore.saveSelectedLocation(this, item.name, selectedAdcode, selectedPath.toList())
                Toast.makeText(this, "已选择: ${selectedPath.joinToString(" → ")}", Toast.LENGTH_LONG).show()
                setResult(RESULT_OK)
                finish()
            }
        }
    }

    override fun onBackPressed() {
        if (stack.isNotEmpty()) {
            val prev = stack.removeAt(stack.size - 1)
            selectedPath.removeAt(selectedPath.size - 1)
            showList(prev, if (stack.isEmpty()) "请选择省份" else if (stack.size == 1) "请选择城市" else "请选择区县")
        } else {
            super.onBackPressed()
        }
    }

    private fun loadWarzoneData(): List<Region> {
        val stream = assets.open("warzone.json")
        val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        val arr = JSONArray(text)
        val result = mutableListOf<Region>()
        for (i in 0 until arr.length()) {
            val prov = arr.getJSONObject(i)
            val provName = prov.getString("province")
            val provCode = prov.getString("adcode")
            val cities = mutableListOf<Region>()
            val cityArr = prov.getJSONArray("cities")
            for (j in 0 until cityArr.length()) {
                val city = cityArr.getJSONObject(j)
                val cityName = city.getString("city")
                val cityCode = city.getString("adcode")
                val districts = mutableListOf<Region>()
                val distArr = city.getJSONArray("districts")
                for (k in 0 until distArr.length()) {
                    val dist = distArr.getJSONObject(k)
                    districts.add(Region(dist.getString("name"), dist.getString("adcode")))
                }
                cities.add(Region(cityName, cityCode, districts))
            }
            result.add(Region(provName, provCode, cities))
        }
        return result
    }
}
