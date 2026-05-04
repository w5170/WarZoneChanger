package com.warzone.changer.model

data class SelectedLocation(
    val province: String = "",
    val city: String = "",
    val district: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val adcode: String = "",
    val formattedAddress: String = ""
) {
    fun isValid(): Boolean = adcode.isNotEmpty()

    fun locationParam(): String = "$latitude,$longitude"
}
