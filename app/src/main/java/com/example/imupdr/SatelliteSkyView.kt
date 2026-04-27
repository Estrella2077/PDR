package com.example.imupdr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class SatelliteSkyView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val satellites = mutableListOf<SatelliteInfo>()

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C6D1DE")
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#738395")
        strokeWidth = dp(1.2f)
    }

    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#263238")
        textSize = dp(12f)
    }

    fun submitSatellites(newSatellites: List<SatelliteInfo>) {
        satellites.clear()
        satellites.addAll(newSatellites)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = min(width, height) * 0.37f

        canvas.drawCircle(centerX, centerY, radius, gridPaint)
        canvas.drawCircle(centerX, centerY, radius * 2f / 3f, gridPaint)
        canvas.drawCircle(centerX, centerY, radius / 3f, gridPaint)
        canvas.drawLine(centerX - radius, centerY, centerX + radius, centerY, axisPaint)
        canvas.drawLine(centerX, centerY - radius, centerX, centerY + radius, axisPaint)
        canvas.drawLine(
            centerX - radius * 0.7f,
            centerY - radius * 0.7f,
            centerX + radius * 0.7f,
            centerY + radius * 0.7f,
            axisPaint
        )
        canvas.drawLine(
            centerX - radius * 0.7f,
            centerY + radius * 0.7f,
            centerX + radius * 0.7f,
            centerY - radius * 0.7f,
            axisPaint
        )

        canvas.drawText("N", centerX - dp(5f), centerY - radius - dp(8f), textPaint)
        canvas.drawText("E", centerX + radius + dp(6f), centerY + dp(4f), textPaint)
        canvas.drawText("S", centerX - dp(5f), centerY + radius + dp(18f), textPaint)
        canvas.drawText("W", centerX - radius - dp(16f), centerY + dp(4f), textPaint)
        canvas.drawText("30°", centerX + dp(8f), centerY - radius / 3f + dp(4f), textPaint)
        canvas.drawText("60°", centerX + dp(8f), centerY - radius * 2f / 3f + dp(4f), textPaint)

        satellites.forEach { satellite ->
            val distanceRatio = (90f - satellite.elevationDegrees.coerceIn(0f, 90f)) / 90f
            val pointRadius = radius * distanceRatio
            val azimuthRadians = Math.toRadians(satellite.azimuthDegrees.toDouble())
            val x = centerX + (pointRadius * sin(azimuthRadians)).toFloat()
            val y = centerY - (pointRadius * cos(azimuthRadians)).toFloat()
            pointPaint.color = colorForConstellation(satellite.constellationType, satellite.usedInFix)
            canvas.drawCircle(x, y, if (satellite.usedInFix) dp(7f) else dp(5.5f), pointPaint)
        }
    }

    private fun colorForConstellation(type: Int, usedInFix: Boolean): Int {
        val baseColor = when (type) {
            android.location.GnssStatus.CONSTELLATION_BEIDOU -> Color.parseColor("#D84315")
            android.location.GnssStatus.CONSTELLATION_GPS -> Color.parseColor("#1565C0")
            android.location.GnssStatus.CONSTELLATION_GLONASS -> Color.parseColor("#2E7D32")
            android.location.GnssStatus.CONSTELLATION_GALILEO -> Color.parseColor("#6A1B9A")
            android.location.GnssStatus.CONSTELLATION_QZSS -> Color.parseColor("#00838F")
            android.location.GnssStatus.CONSTELLATION_SBAS -> Color.parseColor("#F9A825")
            android.location.GnssStatus.CONSTELLATION_IRNSS -> Color.parseColor("#5D4037")
            else -> Color.parseColor("#546E7A")
        }
        val alpha = if (usedInFix) 255 else 132
        return Color.argb(alpha, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
    }

    private fun dp(value: Float): Float {
        return value * resources.displayMetrics.density
    }
}
