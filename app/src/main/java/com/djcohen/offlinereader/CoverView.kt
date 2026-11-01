package com.djcohen.offlinereader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import kotlin.math.max

/**
 * A book cover with rounded corners. Shows the cover picture (cropped to fill) or, for books that have none,
 * a leaf-style page with the title on it.
 */
class CoverView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val radius = 10 * density
    private val picturePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = density; color = 0x33FFFFFF }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("serif", Typeface.BOLD); color = 0xFF22301F.toInt()
    }
    private val tagPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); color = 0xFF5E7F61.toInt()
    }
    private val clip = Path()
    private val dest = RectF()

    private var picture: Bitmap? = null
    private var title = ""
    private var format = ""

    fun bind(title: String, format: String, picture: Bitmap?) {
        this.title = title
        this.format = format
        this.picture = picture
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        clip.reset()
        clip.addRoundRect(0f, 0f, w, h, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        val pic = picture
        if (pic != null) {
            val s = max(w / pic.width, h / pic.height)
            val dw = pic.width * s
            val dh = pic.height * s
            dest.set((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2)
            canvas.drawBitmap(pic, null, dest, picturePaint)
        } else {
            canvas.drawColor(0xFFFFF8E7.toInt())
            fill.color = 0xFF6CC070.toInt()
            canvas.drawRect(0f, 0f, w * 0.07f, h, fill)                      // leaf-green spine
            titlePaint.textSize = w * 0.115f
            val textW = (w * 0.78f).toInt().coerceAtLeast(1)
            val layout = StaticLayout.Builder.obtain(title, 0, title.length, titlePaint, textW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setMaxLines(6).setEllipsize(TextUtils.TruncateAt.END).build()
            canvas.save()
            canvas.translate(w * 0.14f, h * 0.1f)
            layout.draw(canvas)
            canvas.restore()
            tagPaint.textSize = w * 0.085f
            canvas.drawText(format.uppercase(), w * 0.14f, h - h * 0.06f, tagPaint)
        }
        canvas.restore()
        canvas.drawPath(clip, border)
    }
}
