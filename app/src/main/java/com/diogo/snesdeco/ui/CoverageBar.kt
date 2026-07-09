package com.diogo.snesdeco.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Renders ROM code-coverage as a horizontal bar. The ROM is divided into N
 * buckets across the width; each bucket is lit green in proportion to how many
 * of its bytes the CDL has marked as executed. Gives an at-a-glance picture of
 * "how much of the game has run / been captured so far".
 */
class CoverageBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var cdl: ByteArray = ByteArray(0)
    private val paintBg = Paint().apply { color = Color.parseColor("#181B22") }
    private val paintCell = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setCdl(map: ByteArray) {
        cdl = map
        invalidate()
    }

    /** Fraction 0..1 of ROM bytes marked as code/operand. */
    fun coverageFraction(): Double {
        if (cdl.isEmpty()) return 0.0
        var coded = 0
        for (b in cdl) if ((b.toInt() and 0x03) != 0) coded++
        return coded.toDouble() / cdl.size
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, paintBg)
        if (cdl.isEmpty()) return

        val buckets = 120
        val bytesPerBucket = (cdl.size + buckets - 1) / buckets
        val cellW = w / buckets

        for (bkt in 0 until buckets) {
            val start = bkt * bytesPerBucket
            if (start >= cdl.size) break
            val end = minOf(start + bytesPerBucket, cdl.size)
            var coded = 0
            for (i in start until end) if ((cdl[i].toInt() and 0x03) != 0) coded++
            val frac = coded.toFloat() / (end - start)
            if (frac <= 0f) continue
            // Brighter green with more coverage in the bucket.
            val g = (120 + (135 * frac)).toInt().coerceIn(0, 255)
            paintCell.color = Color.rgb((40 * frac).toInt(), g, (80 * frac).toInt())
            val x = bkt * cellW
            canvas.drawRect(x, 0f, x + cellW + 1f, h, paintCell)
        }
    }
}
