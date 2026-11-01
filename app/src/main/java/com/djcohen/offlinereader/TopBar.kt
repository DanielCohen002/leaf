package com.djcohen.offlinereader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** A little bookshelf: three books standing on a shelf. Tapping it goes back to the library. */
private class ShelfIcon(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val box = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val unit = minOf(w, h) / 24f
        val left = (w - 24 * unit) / 2f
        val top = (h - 24 * unit) / 2f
        fun rect(x1: Float, y1: Float, x2: Float, y2: Float, r: Float = 0.8f) {
            box.set(left + x1 * unit, top + y1 * unit, left + x2 * unit, top + y2 * unit)
            canvas.drawRoundRect(box, r * unit, r * unit, paint)
        }
        rect(3f, 5f, 8f, 19f)       // three books of different heights
        rect(9.5f, 3f, 14.5f, 19f)
        rect(16f, 7f, 21f, 19f)
        rect(1.5f, 19.5f, 22.5f, 21.5f, 1f)   // the shelf
    }
}

/** The bar shown at the top of a reader when the middle of the page is tapped: bookshelf button plus the book title. */
class TopBar(context: Context, onBack: () -> Unit) : LinearLayout(context) {
    private val label = TextView(context).apply {
        setTextColor(Color.WHITE); textSize = 16f
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
    }

    var text: CharSequence
        get() = label.text
        set(v) { label.text = v }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(0xE6202020.toInt())
        val d = resources.displayMetrics.density
        val icon = ShelfIcon(context).apply {
            contentDescription = "Back to bookshelf"
            setOnClickListener { onBack() }
            setPadding((8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
        }
        addView(icon, LayoutParams((44 * d).toInt(), (44 * d).toInt()))
        addView(label, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = (10 * d).toInt() })
    }
}
