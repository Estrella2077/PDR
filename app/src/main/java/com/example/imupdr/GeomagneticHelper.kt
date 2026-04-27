package com.example.imupdr

import android.hardware.GeomagneticField

object GeomagneticHelper {
    fun declinationRadians(
        latitude: Double,
        longitude: Double,
        altitudeMeters: Double = 0.0,
        timeMillis: Long = System.currentTimeMillis()
    ): Float {
        val field = GeomagneticField(
            latitude.toFloat(),
            longitude.toFloat(),
            altitudeMeters.toFloat(),
            timeMillis
        )
        return Math.toRadians(field.declination.toDouble()).toFloat()
    }
}
