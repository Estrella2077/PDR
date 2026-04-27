package com.example.imupdr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.max

class TripleAxisChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val seriesX = mutableListOf<Float>()
    private val seriesY = mutableListOf<Float>()
    private val seriesZ = mutableListOf<Float>()

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D9E0E8")
        strokeWidth = dp(1f)
    }

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#90A4AE")
        strokeWidth = dp(1.2f)
    }

    private val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D84315")
        strokeWidth = dp(2f)
        style = Paint.Style.STROKE
    }

    private val yPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2E7D32")
        strokeWidth = dp(2f)
        style = Paint.Style.STROKE
    }

    private val zPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1565C0")
        strokeWidth = dp(2f)
        style = Paint.Style.STROKE
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#455A64")
        textSize = dp(11f)
    }

    fun submitData(xValues: List<Float>, yValues: List<Float>, zValues: List<Float>) {
        seriesX.clear()
        seriesY.clear()
        seriesZ.clear()
        seriesX.addAll(xValues)
        seriesY.addAll(yValues)
        seriesZ.addAll(zValues)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val contentLeft = dp(12f)
        val contentTop = dp(10f)
        val contentRight = width - dp(12f)
        val contentBottom = height - dp(18f)
        val chartWidth = contentRight - contentLeft
        val chartHeight = contentBottom - contentTop

        if (chartWidth <= 0f || chartHeight <= 0f) {
            return
        }

        for (index in 0..4) {
            val y = contentTop + chartHeight * index / 4f
            canvas.drawLine(contentLeft, y, contentRight, y, gridPaint)
        }
        canvas.drawLine(contentLeft, contentTop + chartHeight / 2f, contentRight, contentTop + chartHeight / 2f, axisPaint)
        canvas.drawText("x", contentLeft, height - dp(4f), xPaint.apply { style = Paint.Style.FILL })
        canvas.drawText("y", contentLeft + dp(22f), height - dp(4f), yPaint.apply { style = Paint.Style.FILL })
        canvas.drawText("z", contentLeft + dp(44f), height - dp(4f), zPaint.apply { style = Paint.Style.FILL })
        xPaint.style = Paint.Style.STROKE
        yPaint.style = Paint.Style.STROKE
        zPaint.style = Paint.Style.STROKE

        val allValues = seriesX + seriesY + seriesZ
        val amplitude = max(0.5f, allValues.maxOfOrNull { abs(it) } ?: 1f) * 1.15f
        canvas.drawText(
            String.format("%.1f", amplitude),
            contentRight - dp(28f),
            contentTop + dp(10f),
            textPaint
        )
        canvas.drawText(
            String.format("%.1f", -amplitude),
            contentRight - dp(30f),
            contentBottom,
            textPaint
        )

        drawSeries(canvas, seriesX, amplitude, contentLeft, contentTop, chartWidth, chartHeight, xPaint)
        drawSeries(canvas, seriesY, amplitude, contentLeft, contentTop, chartWidth, chartHeight, yPaint)
        drawSeries(canvas, seriesZ, amplitude, contentLeft, contentTop, chartWidth, chartHeight, zPaint)
    }

    private fun drawSeries(
        canvas: Canvas,
        values: List<Float>,
        amplitude: Float,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        paint: Paint
    ) {
        if (values.size < 2) {
            return
        }
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = left + width * index / (values.size - 1).toFloat()
            val y = top + height * (0.5f - value / (2f * amplitude))
            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }
        canvas.drawPath(path, paint)
    }

    private fun dp(value: Float): Float {
        return value * resources.displayMetrics.density
    }
}
