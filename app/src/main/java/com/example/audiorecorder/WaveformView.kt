package com.example.audiorecorder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val maxSamples = 60
    private val samples = ArrayDeque<Int>(maxSamples)
    private val samplesLock = Any()

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E94560")
        style = Paint.Style.FILL
    }

    private val backgroundPaint = Paint().apply {
        color = Color.parseColor("#1A1A2E")
    }

    private var lastDrawTimeMs = 0L
    private val minDrawIntervalMs = 33L

    fun pushAmplitude(level: Int) {
        val clamped = level.coerceIn(0, 100)
        synchronized(samplesLock) {
            samples.addLast(clamped)
            if (samples.size > maxSamples) samples.removeFirst()
        }
        val now = System.currentTimeMillis()
        if (now - lastDrawTimeMs >= minDrawIntervalMs) {
            lastDrawTimeMs = now
            invalidate()
        }
    }

    fun clear() {
        synchronized(samplesLock) {
            samples.clear()
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRoundRect(
            0f, 0f, width.toFloat(), height.toFloat(),
            16f, 16f, backgroundPaint
        )

        val snapshot: List<Int>
        synchronized(samplesLock) {
            snapshot = samples.toList()
        }
        if (snapshot.isEmpty()) return

        val barWidth = width.toFloat() / maxSamples
        val gap = barWidth * 0.25f
        val centerY = height / 2f
        val offset = maxSamples - snapshot.size

        snapshot.forEachIndexed { index, level ->
            val barHeight = (level / 100f) * (height * 0.85f)
            val left = (offset + index) * barWidth + gap / 2
            val right = left + barWidth - gap
            val top = centerY - barHeight / 2f
            val bottom = centerY + barHeight / 2f
            canvas.drawRoundRect(left, top, right, bottom, 6f, 6f, barPaint)
        }
    }
}
