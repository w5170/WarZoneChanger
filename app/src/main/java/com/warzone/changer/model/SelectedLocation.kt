package com.warzone.changer.model

/**
 * 选中的战区位置
 */
data class SelectedLocation(
    val province: String,
    val city: String,
    val district: String,
    val adcode: String
) {
    val displayName: String
        get() = if (district.isNotEmpty()) "$province $city $district"
                else if (city.isNotEmpty()) "$province $city"
                else province
}
