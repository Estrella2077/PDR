package com.example.imupdr

import kotlin.math.PI

object AngleUtils {
    fun normalizeRadians(angle: Float): Float {
        var normalized = angle
        while (normalized <= -PI) {
            normalized += (2.0 * PI).toFloat()
        }
        while (normalized > PI) {
            normalized -= (2.0 * PI).toFloat()
        }
        return normalized
    }

    fun shortestDelta(from: Float, to: Float): Float {
        return normalizeRadians(to - from)
    }

    fun smoothAngle(previous: Float, measured: Float, weight: Float): Float {
        val clampedWeight = weight.coerceIn(0.0f, 1.0f)
        val delta = shortestDelta(previous, measured)
        return normalizeRadians(previous + delta * clampedWeight)
    }
}
