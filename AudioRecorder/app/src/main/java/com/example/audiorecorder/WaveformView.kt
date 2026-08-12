package com.example.audiorecorder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.util.LinkedList

/**
 * Lightweight scrolling bar-waveform. Feed it amplitude values (0-100) via
 * [pushAmplitude]; it keeps the most recent N samples and redraws as bars.
 * No dependency on any audio library — pure Canvas drawing.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val maxSamples = 60
    private val samples = LinkedList<Int>()

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E94560")
        style = Paint.Style.FILL
    }

    private val backgroundPaint = Paint().apply {
        color = Color.parseColor("#1A1A2E")
    }

    fun pushAmplitude(level: Int) {
        samples.addLast(level.coerceIn(0, 100))
        if (samples.size > maxSamples) samples.removeFirst()
        invalidate()
    }

    fun clear() {
        samples.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 16f, 16f, backgroundPaint)

        if (samples.isEmpty()) return

        val barWidth = width.toFloat() / maxSamples
        val gap = barWidth * 0.25f
        val centerY = height / 2f

        // Right-align newest sample so the waveform scrolls left-to-right visually.
        val offset = maxSamples - samples.size
        samples.forEachIndexed { index, level ->
            val barHeight = (level / 100f) * (height * 0.85f)
            val left = (offset + index) * barWidth + gap / 2
            val right = left + barWidth - gap
            val top = centerY - barHeight / 2f
            val bottom = centerY + barHeight / 2f
            canvas.drawRoundRect(left, top, right, bottom, 6f, 6f, barPaint)
        }
    }
}
