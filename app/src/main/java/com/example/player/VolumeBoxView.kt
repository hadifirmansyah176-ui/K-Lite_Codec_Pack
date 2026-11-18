package com.example.player

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/** Kotak volume hijau dengan teks persen. Sentuh/geser untuk mengatur 0-100%. */
class VolumeBoxView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    var level = 100
        set(v) { field = v.coerceIn(0, 100); invalidate() }
    var onChange: ((Int) -> Unit)? = null
    var multiplier = 1
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val bg = Paint().apply { color = Color.rgb(240, 240, 240) }
    private val fill = Paint().apply { color = Color.rgb(144, 214, 148) }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.BLACK; strokeWidth = 1.5f * d
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(31, 58, 42); textSize = 15f * d; textAlign = Paint.Align.CENTER
    }

    override fun onDraw(c: Canvas) {
        val r = RectF(1f * d, 1f * d, width - 1f * d, height - 1f * d)
        c.drawRect(r, bg)
        c.drawRect(r.left, r.top, r.left + r.width() * level / 100f, r.bottom, fill)
        c.drawRect(r, border)
        c.drawText("${level * multiplier}%", width / 2f, height / 2f + text.textSize / 3f, text)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                level = (e.x / width * 100).toInt()
                onChange?.invoke(level)
                if (e.action == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
