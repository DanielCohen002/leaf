package com.djcohen.offlinereader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs

/** Shows one page image, fitted to the screen, with pinch / double-tap zoom and panning. */
class ImagePageView(context: Context) : View(context) {
    var onZone: ((PageView.Zone) -> Unit)? = null
    /** Swipe direction callback: true = swiped towards the left edge. */
    var onSwipe: ((Boolean) -> Unit)? = null
    var label: String = ""
        set(v) { field = v; invalidate() }

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAAFFFFFF.toInt(); textSize = 13 * density; textAlign = Paint.Align.RIGHT
        setShadowLayer(3 * density, 0f, 0f, 0xFF000000.toInt())
    }
    private val matrix = Matrix()
    private val values = FloatArray(9)
    private var bitmap: Bitmap? = null
    private var fitScale = 1f
    private var insetB = 0

    var pageBackground = 0xFF000000.toInt()
        set(v) { field = v; invalidate() }

    fun setInvert(invert: Boolean) {
        paint.colorFilter = if (invert) ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        ))) else null
        invalidate()
    }

    fun setBottomInset(px: Int) { insetB = px; invalidate() }

    fun setBitmap(bmp: Bitmap?) {
        bitmap = bmp
        fit()
        invalidate()
    }

    private fun scale(): Float { matrix.getValues(values); return values[Matrix.MSCALE_X] }

    private fun fit() {
        val b = bitmap ?: return
        if (width == 0 || height == 0) return
        fitScale = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
        matrix.reset()
        matrix.postScale(fitScale, fitScale)
        matrix.postTranslate((width - b.width * fitScale) / 2f, (height - b.height * fitScale) / 2f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit()
    }

    /** Keep the picture covering the view when zoomed in, centred when smaller than the view. */
    private fun clamp() {
        val b = bitmap ?: return
        val r = RectF(0f, 0f, b.width.toFloat(), b.height.toFloat())
        matrix.mapRect(r)
        var dx = 0f
        var dy = 0f
        dx = if (r.width() <= width) (width - r.width()) / 2f - r.left
        else if (r.left > 0) -r.left else if (r.right < width) width - r.right else 0f
        dy = if (r.height() <= height) (height - r.height()) / 2f - r.top
        else if (r.top > 0) -r.top else if (r.bottom < height) height - r.bottom else 0f
        matrix.postTranslate(dx, dy)
    }

    private fun zoomed() = scale() > fitScale * 1.05f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val target = (scale() * d.scaleFactor).coerceIn(fitScale, fitScale * 6f)
            val f = target / scale()
            matrix.postScale(f, f, d.focusX, d.focusY)
            clamp(); invalidate()
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (!zoomed()) return false
            matrix.postTranslate(-dx, -dy)
            clamp(); invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (e1 == null || zoomed()) return false
            val dx = e2.x - e1.x
            val dy = e2.y - e1.y
            if (abs(dx) > 70 * density && abs(dx) > abs(dy) * 1.5f) { onSwipe?.invoke(dx < 0); return true }
            return false
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val b = bitmap ?: return true
            if (zoomed()) {
                fit()
            } else {
                // Zoom to fill the width (good for tall comic pages / text PDFs), at least 2x.
                val target = maxOf(width.toFloat() / b.width, fitScale * 2f)
                val f = target / scale()
                matrix.postScale(f, f, e.x, e.y)
                clamp()
            }
            invalidate()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val f = e.x / width
            onZone?.invoke(if (f < 0.3f) PageView.Zone.PREV else if (f > 0.7f) PageView.Zone.NEXT else PageView.Zone.MENU)
            return true
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        if (!scaleDetector.isInProgress) gestures.onTouchEvent(e)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(pageBackground)
        val b = bitmap
        if (b != null) canvas.drawBitmap(b, matrix, paint)
        if (label.isNotEmpty()) canvas.drawText(label, width - 12 * density, height - insetB - 10 * density, labelPaint)
    }
}
