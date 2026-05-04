package com.warzone.changer.data

import android.content.Context
import com.warzone.changer.App
import com.warzone.changer.model.SelectedLocation

object LocationStore {
    private const val PREFS = "location_prefs"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(ctx: Context, loc: SelectedLocation) {
        prefs(ctx).edit()
            .putString("province", loc.province)
            .putString("city", loc.city)
            .putString("district", loc.district)
            .putString("adcode", loc.adcode)
            .putDouble("lat", loc.latitude)
            .putDouble("lng", loc.longitude)
            .putString("addr", loc.formattedAddress)
            .apply()
    }

    fun get(ctx: Context): SelectedLocation? {
        val p = prefs(ctx)
        val adcode = p.getString("adcode", "") ?: ""
        if (adcode.isEmpty()) return null
        return SelectedLocation(
            province = p.getString("province", "") ?: "",
            city = p.getString("city", "") ?: "",
            district = p.getString("district", "") ?: "",
            adcode = adcode,
            latitude = p.getDouble("lat", 0.0),
            longitude = p.getDouble("lng", 0.0),
            formattedAddress = p.getString("addr", "") ?: ""
        )
    }

    fun has(ctx: Context): Boolean = prefs(ctx).contains("adcode")

    fun clear(ctx: Context) { prefs(ctx).edit().clear().apply() }
}

private fun android.content.SharedPreferences.Editor.putDouble(key: String, value: Double): android.content.SharedPreferences.Editor {
    putLong(key, java.lang.Double.doubleToRawLongBits(value))
    return this
}

private fun android.content.SharedPreferences.getDouble(key: String, default: Double): Double {
    return java.lang.Double.longBitsToDouble(getLong(key, java.lang.Double.doubleToRawLongBits(default)))
}
