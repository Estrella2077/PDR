package com.example.imupdr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class TrajectoryView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val pathPoints = mutableListOf<PointF>()

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F7F8FB")
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D8DEE9")
        strokeWidth = dp(1.0f)
    }

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#546E7A")
        strokeWidth = dp(1.6f)
    }

    private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1565C0")
        strokeWidth = dp(3.0f)
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val originPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2E7D32")
        style = Paint.Style.FILL
    }

    private val latestPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C62828")
        style = Paint.Style.FILL
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#263238")
        textSize = dp(12.0f)
    }

    fun setPath(points: List<PointF>) {
        pathPoints.clear()
        pathPoints.addAll(points)
        invalidate()
    }

    fun reset() {
        pathPoints.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0.0f, 0.0f, width.toFloat(), height.toFloat(), backgroundPaint)

        val centerX = width / 2.0f
        val centerY = height / 2.0f
        val drawingRadius = min(width, height) * 0.38f
        val maxRangeMeters = max(1.5f, computeMaxRangeMeters())
        val pixelsPerMeter = drawingRadius / maxRangeMeters
        val gridStepMeters = chooseGridStep(maxRangeMeters)
        val gridStepPixels = gridStepMeters * pixelsPerMeter

        drawGrid(canvas, centerX, centerY, gridStepPixels)
        canvas.drawLine(0.0f, centerY, width.toFloat(), centerY, axisPaint)
        canvas.drawLine(centerX, 0.0f, centerX, height.toFloat(), axisPaint)
        canvas.drawText("N", centerX + dp(6.0f), centerY - drawingRadius - dp(6.0f), labelPaint)
        canvas.drawText(
            String.format("scale %.1f m", gridStepMeters),
            dp(12.0f),
            height - dp(12.0f),
            labelPaint
        )

        canvas.drawCircle(centerX, centerY, dp(5.0f), originPaint)

        if (pathPoints.isEmpty()) {
            return
        }

        val path = Path()
        pathPoints.forEachIndexed { index, point ->
            val screenX = centerX + point.x * pixelsPerMeter
            val screenY = centerY - point.y * pixelsPerMeter
            if (index == 0) {
                path.moveTo(screenX, screenY)
            } else {
                path.lineTo(screenX, screenY)
            }
        }
        canvas.drawPath(path, pathPaint)

        val latestPoint = pathPoints.last()
        canvas.drawCircle(
            centerX + latestPoint.x * pixelsPerMeter,
            centerY - latestPoint.y * pixelsPerMeter,
            dp(6.0f),
            latestPaint
        )
    }

    private fun drawGrid(canvas: Canvas, centerX: Float, centerY: Float, stepPixels: Float) {
        if (stepPixels <= 0.0f) {
            return
        }
        var offset = stepPixels
        while (offset < max(width, height)) {
            canvas.drawLine(centerX + offset, 0.0f, centerX + offset, height.toFloat(), gridPaint)
            canvas.drawLine(centerX - offset, 0.0f, centerX - offset, height.toFloat(), gridPaint)
            canvas.drawLine(0.0f, centerY + offset, width.toFloat(), centerY + offset, gridPaint)
            canvas.drawLine(0.0f, centerY - offset, width.toFloat(), centerY - offset, gridPaint)
            offset += stepPixels
        }
    }

    private fun computeMaxRangeMeters(): Float {
        var maxCoordinate = 0.0f
        pathPoints.forEach { point ->
            maxCoordinate = max(maxCoordinate, abs(point.x))
            maxCoordinate = max(maxCoordinate, abs(point.y))
        }
        return maxCoordinate + 1.0f
    }

    private fun chooseGridStep(maxRangeMeters: Float): Float {
        return when {
            maxRangeMeters < 4.0f -> 0.5f
            maxRangeMeters < 8.0f -> 1.0f
            maxRangeMeters < 20.0f -> 2.0f
            else -> 5.0f
        }
    }

    private fun dp(value: Float): Float {
        return value * resources.displayMetrics.density
    }
}
