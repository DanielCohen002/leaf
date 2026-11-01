package com.djcohen.offlinereader

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/** Reader for page-image books: PDF and comic archives. */
class ImageReaderActivity : Activity() {
    private lateinit var library: Library
    private lateinit var prefs: Prefs
    private lateinit var entry: Entry
    private var book: ImageBook? = null
    private lateinit var view: ImagePageView
    private lateinit var topBar: TextView
    private lateinit var bottomBar: LinearLayout
    private lateinit var seek: SeekBar
    private lateinit var pageLabel: TextView
    private var page = 0
    private var overlayVisible = false
    private var renderW = 0
    private var renderH = 0
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<Int, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = Library(this)
        prefs = Prefs(this)
        val found = library.entries().firstOrNull { it.id == intent.getStringExtra(ReaderActivity.EXTRA_ID) }
        if (found == null) { finish(); return }
        entry = found

        val root = FrameLayout(this)
        view = ImagePageView(this).apply {
            onZone = { z ->
                when (z) {
                    PageView.Zone.MENU -> setOverlay(!overlayVisible)
                    // Tapping the left edge goes "forward" in right-to-left (manga) mode.
                    PageView.Zone.NEXT -> turn(!prefs.rightToLeft)
                    PageView.Zone.PREV -> turn(prefs.rightToLeft)
                }
            }
            onSwipe = { towardsLeft -> turn(towardsLeft != prefs.rightToLeft) }
        }
        root.addView(view, FrameLayout.LayoutParams(-1, -1))

        topBar = TextView(this).apply {
            setTextColor(Color.WHITE); setBackgroundColor(0xE6202020.toInt()); textSize = 16f
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(16), dp(12), dp(16), dp(12)); visibility = View.GONE
        }
        root.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xE6202020.toInt()); visibility = View.GONE
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        seek = SeekBar(this).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) pageLabel.text = "Page ${p + 1} / ${book?.pageCount ?: 0}"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { show(progress) }
            })
        }
        bottomBar.addView(seek)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        pageLabel = TextView(this).apply { setTextColor(Color.WHITE); textSize = 15f }
        row.addView(pageLabel, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(this).apply { text = "Settings"; isAllCaps = false; setOnClickListener { showSettings() } })
        bottomBar.addView(row)
        root.addView(bottomBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)

        root.setOnApplyWindowInsetsListener { _, insets ->
            val b = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            topBar.setPadding(dp(16) + b.left, dp(12) + b.top, dp(16) + b.right, dp(12))
            bottomBar.setPadding(dp(16) + b.left, dp(8), dp(16) + b.right, dp(8) + b.bottom)
            view.setBottomInset(b.bottom)
            insets
        }

        applyPrefs()
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // Re-render at the right resolution whenever the view size is known or changes (rotation).
        view.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            val w = r - l
            val h = b - t
            if (w > 0 && h > 0 && (w != orr - ol || h != ob - ot)) {
                renderW = (w * 1.5f).toInt(); renderH = (h * 1.5f).toInt()
                cache.evictAll()
                if (book != null) show(page) else openBook()
            }
        }
    }

    private fun openBook() {
        io.execute {
            try {
                val b = ImageBooks.open(library.file(entry), entry.ext)
                val start = library.position(entry.id).second.coerceIn(0, b.pageCount - 1)
                ui.post {
                    book = b
                    topBar.text = entry.title
                    seek.max = (b.pageCount - 1).coerceAtLeast(0)
                    show(start)
                }
            } catch (t: Throwable) {
                ui.post {
                    Toast.makeText(this, t.message ?: "Couldn't open book", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun applyPrefs() {
        view.setInvert(prefs.invertImages)
        if (prefs.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun setOverlay(show: Boolean) {
        overlayVisible = show
        val v = if (show) View.VISIBLE else View.GONE
        topBar.visibility = v
        bottomBar.visibility = v
    }

    // --- paging ----------------------------------------------------------------------------

    private fun show(index: Int) {
        val b = book ?: return
        page = index.coerceIn(0, b.pageCount - 1)
        val shown = page
        seek.progress = shown
        pageLabel.text = "Page ${shown + 1} / ${b.pageCount}"
        view.label = "${shown + 1} / ${b.pageCount}"
        val cached = cache.get(shown)
        if (cached != null) {
            view.setBitmap(cached)
            prefetch(shown)
            return
        }
        io.execute {
            val bmp = b.render(shown, renderW, renderH)
            if (bmp != null) cache.put(shown, bmp)
            ui.post {
                if (page == shown && !isDestroyed) {
                    if (bmp != null) view.setBitmap(bmp)
                    else Toast.makeText(this, "Couldn't render this page", Toast.LENGTH_SHORT).show()
                    prefetch(shown)
                }
            }
        }
    }

    private fun prefetch(from: Int) {
        val b = book ?: return
        for (i in intArrayOf(from + 1, from - 1)) {
            if (i !in 0 until b.pageCount || cache.get(i) != null) continue
            io.execute {
                if (!isDestroyed && cache.get(i) == null) b.render(i, renderW, renderH)?.let { cache.put(i, it) }
            }
        }
    }

    private fun turn(forward: Boolean) {
        val b = book ?: return
        if (overlayVisible) setOverlay(false)
        val next = page + if (forward) 1 else -1
        if (next !in 0 until b.pageCount) {
            Toast.makeText(this, if (forward) "End of book" else "First page", Toast.LENGTH_SHORT).show()
            return
        }
        show(next)
    }

    // --- settings --------------------------------------------------------------------------

    private fun showSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        fun check(text: String, value: Boolean, set: (Boolean) -> Unit) = CheckBox(this).apply {
            this.text = text; isChecked = value; setOnCheckedChangeListener { _, c -> set(c) }
        }.also { box.addView(it) }
        check("Right-to-left reading (manga)", prefs.rightToLeft) { prefs.rightToLeft = it }
        check("Invert colours", prefs.invertImages) { prefs.invertImages = it; applyPrefs() }
        check("Volume keys turn pages", prefs.volumeKeys) { prefs.volumeKeys = it }
        check("Swap volume keys (volume up = next)", prefs.swapVolume) { prefs.swapVolume = it }
        check("Keep screen on", prefs.keepScreenOn) { prefs.keepScreenOn = it; applyPrefs() }
        box.addView(TextView(this).apply {
            text = "Double-tap to zoom, pinch to zoom further, drag to pan."
            alpha = 0.6f; textSize = 13f; setPadding(0, dp(12), 0, 0)
        })
        AlertDialog.Builder(this).setTitle("Reading settings").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Done", null).show()
    }

    // --- hardware keys ---------------------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (prefs.volumeKeys && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            if (event.repeatCount == 0) turn((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) != prefs.swapVolume)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (prefs.volumeKeys && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) return true
        return super.onKeyUp(keyCode, event)
    }

    // --- lifecycle -------------------------------------------------------------------------

    override fun onPause() {
        if (book != null) library.savePosition(entry.id, 0, page)
        super.onPause()
    }

    override fun onDestroy() {
        io.execute { book?.close() }
        io.shutdown()
        super.onDestroy()
    }
}
