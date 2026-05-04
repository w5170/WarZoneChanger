package com.warzone.changer.data

import android.content.Context
import com.warzone.changer.model.SelectedLocation

/**
 * 战区位置存储
 */
object LocationStore {

    private const val PREF_NAME = "warzone_prefs"
    private const val KEY_PROVINCE = "province"
    private const val KEY_CITY = "city"
    private const val KEY_DISTRICT = "district"
    private const val KEY_ADCODE = "adcode"

    fun saveLocation(context: Context, location: SelectedLocation) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROVINCE, location.province)
            .putString(KEY_CITY, location.city)
            .putString(KEY_DISTRICT, location.district)
            .putString(KEY_ADCODE, location.adcode)
            .apply()
    }

    fun getSelectedLocation(context: Context): SelectedLocation? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val adcode = prefs.getString(KEY_ADCODE, null) ?: return null
        return SelectedLocation(
            province = prefs.getString(KEY_PROVINCE, "") ?: "",
            city = prefs.getString(KEY_CITY, "") ?: "",
            district = prefs.getString(KEY_DISTRICT, "") ?: "",
            adcode = adcode
        )
    }

    fun clearLocation(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}
