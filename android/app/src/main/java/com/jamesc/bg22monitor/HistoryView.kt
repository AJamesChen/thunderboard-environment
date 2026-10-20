package com.jamesc.bg22monitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

/** A bounded, one-hour trace. Gaps and reconnects are never interpolated. */
class HistoryView(context: Context) : View(context) {
    private data class Sample(val time: Long, val value: Double, val gap: Long, val segment: Int)
    private val samples = ArrayDeque<Sample>()
    private var segment = 0
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trace = Path()
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val clockDate = Date()
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES; contentDescription = "Temperature history. Waiting for live readings." }
    fun clear() { samples.clear(); segment++; invalidate() }
    fun breakLine() { segment++ }
    fun add(time: Long, value: Double, gap: Long) {
        samples.addLast(Sample(time, value, gap, segment))
        prune(time)
        contentDescription = "Temperature history, ${samples.size} readings in the last hour. Latest ${String.format(Locale.getDefault(), "%.1f", value)} degrees Celsius."
        invalidate()
    }
    private fun prune(now: Long) {
        while (samples.isNotEmpty() && (samples.first().time < now - HOUR || samples.size > 3600)) samples.removeFirst()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.elapsedRealtime(); prune(now)
        paint.style = Paint.Style.FILL; paint.color = Color.rgb(94, 112, 100)
        paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
        if (samples.isEmpty()) {
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("Waiting for live readings", width / 2f, height / 2f, paint)
            return
        }
        val low = floor(samples.minOf { it.value } - .2)
        val high = maxOf(low + 1, ceil(samples.maxOf { it.value } + .2))
        val left = 48 * density; val right = width - 12 * density
        val top = 20 * density; val bottom = height - 30 * density
        if (right <= left || bottom <= top) return
        paint.strokeWidth = density
        for (i in 0..2) {
            val y = top + (bottom - top) * i / 2
            paint.color = Color.rgb(221, 229, 220)
            canvas.drawLine(left, y, right, y, paint)
            paint.color = Color.rgb(94, 112, 100); paint.textAlign = Paint.Align.LEFT
            canvas.drawText(String.format(Locale.getDefault(), "%.0f°C", high - (high - low) * i / 2), 0f, y + 4 * density, paint)
        }
        trace.reset()
        var previous: Sample? = null
        for (sample in samples) {
            val x = left + (sample.time - (now - HOUR)).toFloat() / HOUR * (right - left)
            val y = bottom - ((sample.value - low) / (high - low) * (bottom - top)).toFloat()
            val last = previous
            if (last == null || last.segment != sample.segment || sample.time - last.time > maxOf(last.gap, sample.gap)) trace.moveTo(x, y)
            else trace.lineTo(x, y)
            paint.color = Color.rgb(36, 107, 72); paint.style = Paint.Style.FILL
            canvas.drawCircle(x, y, 2 * density, paint)
            previous = sample
        }
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.5f * density; paint.strokeJoin = Paint.Join.ROUND
        canvas.drawPath(trace, paint)
        paint.style = Paint.Style.FILL; paint.color = Color.rgb(94, 112, 100)
        for (i in 0..2) {
            paint.textAlign = when (i) { 0 -> Paint.Align.LEFT; 2 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
            clockDate.time = System.currentTimeMillis() - HOUR + HOUR * i / 2
            canvas.drawText(clockFormat.format(clockDate), left + (right - left) * i / 2, height - 6 * density, paint)
        }
    }
    companion object { private const val HOUR = 3_600_000L }
}
