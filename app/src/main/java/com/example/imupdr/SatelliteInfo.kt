package com.example.imupdr

data class SatelliteInfo(
    val constellationType: Int,
    val elevationDegrees: Float,
    val azimuthDegrees: Float,
    val usedInFix: Boolean
)
