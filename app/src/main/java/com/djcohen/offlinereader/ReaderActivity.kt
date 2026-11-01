package com.djcohen.offlinereader

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class ReaderActivity : Activity() {
    private lateinit var library: Library
    private lateinit var prefs: Prefs
    private lateinit var entry: Entry
    private var book: Book? = null
    private lateinit var page: PageView
    private lateinit var topBar: TextView
    private lateinit var bottomBar: LinearLayout
    private lateinit var selBar: LinearLayout
    private var selText: String? = null
    private var chapter = 0
    private var loading = true
    private var overlayVisible = false
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var barTop = 0
    private var barBottom = 0

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = Library(this)
        prefs = Prefs(this)
        val id = intent.getStringExtra(EXTRA_ID)
        val found = library.entries().firstOrNull { it.id == id }
        if (found == null) { finish(); return }
        entry = found

        val root = FrameLayout(this)
        page = PageView(this).apply {
            onZone = { z ->
                when (z) {
                    PageView.Zone.NEXT -> turn(true)
                    PageView.Zone.PREV -> turn(false)
                    PageView.Zone.MENU -> setOverlay(!overlayVisible)
                }
            }
        }
        root.addView(page, FrameLayout.LayoutParams(-1, -1))

        topBar = TextView(this).apply {
            setTextColor(Color.WHITE); setBackgroundColor(0xE6202020.toInt()); textSize = 16f
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(16), dp(12), dp(16), dp(12)); visibility = View.GONE
        }
        root.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xE6202020.toInt()); visibility = View.GONE
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        fun barButton(label: String, action: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; setOnClickListener { action() }
            bottomBar.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
        }
        barButton("‹ Chapter") { skipChapter(-1) }
        barButton("Chapters") { showChapters() }
        barButton("Aa") { showSettings() }
        barButton("Chapter ›") { skipChapter(1) }
        root.addView(bottomBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        selBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xF0202020.toInt()); visibility = View.GONE
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        fun selButton(label: String, action: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; setOnClickListener { action() }
            selBar.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
        }
        selButton("Copy") { copySelection() }
        selButton("Look up") { lookUpSelection() }
        selButton("Done") { page.clearSelection() }
        root.addView(selBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        page.onSelection = { t ->
            selText = t
            if (t == null) selBar.visibility = View.GONE
            else {
                setOverlay(false)
                (selBar.layoutParams as FrameLayout.LayoutParams).gravity =
                    if (page.selectionY() > page.height / 2f) Gravity.TOP else Gravity.BOTTOM
                selBar.requestLayout()
                selBar.visibility = View.VISIBLE
            }
        }
        setContentView(root)

        root.setOnApplyWindowInsetsListener { _, insets ->
            val b = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            page.setInsets(b.left, b.top, b.right, b.bottom)
            barTop = b.top; barBottom = b.bottom
            selBar.setPadding(dp(8) + b.left, dp(8) + b.top, dp(8) + b.right, dp(8) + b.bottom)
            topBar.setPadding(dp(16) + b.left, dp(12) + b.top, dp(16) + b.right, dp(12))
            bottomBar.setPadding(dp(8) + b.left, dp(8), dp(8) + b.right, dp(8) + b.bottom)
            insets
        }

        applyStyle()
        hideSystemBars()

        io.execute {
            try {
                val b = BookLoader.open(library.file(entry), entry.ext)
                val (ch, off) = library.position(entry.id)
                ui.post {
                    book = b
                    topBar.text = b.title
                    loadChapter(ch.coerceIn(0, b.chapterTitles.size - 1), off)
                }
            } catch (t: Throwable) {
                ui.post {
                    Toast.makeText(this, "Couldn't open book: ${t.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun applyStyle() {
        val t = Themes.all[prefs.theme.coerceIn(Themes.all.indices)]
        page.setStyle(Fonts.typeface(prefs.font), prefs.textSizeSp, prefs.spacing / 100f, t.fg, t.bg)
        window.decorView.setBackgroundColor(t.bg)
        if (prefs.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Light theme needs dark status icons if the bars are ever shown.
        window.insetsController?.setSystemBarsAppearance(
            if (prefs.theme == 2) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
        )
    }

    private fun hideSystemBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun setOverlay(show: Boolean) {
        overlayVisible = show
        val v = if (show) View.VISIBLE else View.GONE
        topBar.visibility = v
        bottomBar.visibility = v
    }

    // --- navigation ------------------------------------------------------------------------

    /** [offset] < 0 opens the chapter on its last page. */
    private fun loadChapter(index: Int, offset: Int) {
        val b = book ?: return
        loading = true
        val w = page.contentWidth
        val h = (page.contentHeight * 0.9f).toInt()
        io.execute {
            val text = try {
                b.load(index, w, h)
            } catch (t: Throwable) {
                android.text.SpannableString("Couldn't load this section:\n${t.message}")
            }
            ui.post {
                chapter = index
                page.footerText = b.chapterTitles[index]
                page.setContent(text, offset)
                loading = false
            }
        }
    }

    private fun turn(forward: Boolean) {
        val b = book ?: return
        if (loading) return
        if (overlayVisible) setOverlay(false)
        if (forward) {
            if (!page.nextPage()) {
                if (chapter + 1 < b.chapterTitles.size) loadChapter(chapter + 1, 0)
                else Toast.makeText(this, "End of book", Toast.LENGTH_SHORT).show()
            }
        } else {
            if (!page.prevPage() && chapter > 0) loadChapter(chapter - 1, -1)
        }
    }

    private fun skipChapter(delta: Int) {
        val b = book ?: return
        val target = chapter + delta
        if (loading || target !in b.chapterTitles.indices) return
        loadChapter(target, 0)
    }

    private fun showChapters() {
        val b = book ?: return
        AlertDialog.Builder(this)
            .setTitle("Chapters")
            .setSingleChoiceItems(b.chapterTitles.toTypedArray(), chapter) { d, which ->
                d.dismiss(); setOverlay(false); loadChapter(which, 0)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // --- selection actions -----------------------------------------------------------------

    private fun copySelection() {
        val t = selText ?: return
        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
            .setPrimaryClip(android.content.ClipData.newPlainText("Selected text", t))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        page.clearSelection()
    }

    /** Hands the text to any installed dictionary/lookup app. (A built-in offline dictionary is planned.) */
    private fun lookUpSelection() {
        val t = selText ?: return
        val i = android.content.Intent(android.content.Intent.ACTION_PROCESS_TEXT)
            .setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_PROCESS_TEXT, t)
            .putExtra(android.content.Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        try {
            startActivity(android.content.Intent.createChooser(i, "Look up “$t”"))
        } catch (e: Exception) {
            Toast.makeText(this, "No lookup app installed", Toast.LENGTH_SHORT).show()
        }
    }

    // --- settings --------------------------------------------------------------------------

    private fun showSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        fun label(t: String) = TextView(this).apply { text = t; setPadding(0, dp(12), 0, dp(4)) }.also { box.addView(it) }

        label("Font")
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Fonts.all.map { it.first })
        spinner.setSelection(prefs.font)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos != prefs.font) { prefs.font = pos; applyStyle() }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        box.addView(spinner)

        val sizeLabel = label("Text size: ${prefs.textSizeSp}")
        box.addView(SeekBar(this).apply {
            max = 28; progress = prefs.textSizeSp - 12
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { sizeLabel.text = "Text size: ${p + 12}" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { prefs.textSizeSp = progress + 12; applyStyle() }
            })
        })

        val spaceLabel = label("Line spacing: ${prefs.spacing / 100f}")
        box.addView(SeekBar(this).apply {
            max = 24; progress = (prefs.spacing - 100) / 5
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { spaceLabel.text = "Line spacing: ${(100 + p * 5) / 100f}" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { prefs.spacing = 100 + progress * 5; applyStyle() }
            })
        })

        label("Colours")
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        Themes.all.forEachIndexed { i, t ->
            group.addView(RadioButton(this).apply { text = if (i == 2) "Dark (inverted)" else t.name; id = 1000 + i })
        }
        group.check(1000 + prefs.theme)
        group.setOnCheckedChangeListener { _, checked -> prefs.theme = checked - 1000; applyStyle() }
        box.addView(group)

        fun check(text: String, value: Boolean, set: (Boolean) -> Unit) = CheckBox(this).apply {
            this.text = text; isChecked = value; setOnCheckedChangeListener { _, c -> set(c) }
        }.also { box.addView(it) }
        check("Volume keys turn pages", prefs.volumeKeys) { prefs.volumeKeys = it }
        check("Swap volume keys (volume up = next)", prefs.swapVolume) { prefs.swapVolume = it }
        check("Keep screen on", prefs.keepScreenOn) { prefs.keepScreenOn = it; applyStyle() }

        AlertDialog.Builder(this).setTitle("Reading settings").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Done", null).show()
    }

    // --- hardware keys ---------------------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (prefs.volumeKeys && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            if (event.repeatCount == 0) {
                val next = (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) != prefs.swapVolume
                turn(next)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (prefs.volumeKeys && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) return true
        return super.onKeyUp(keyCode, event)
    }

    // --- lifecycle -------------------------------------------------------------------------

    private fun savePosition() {
        if (book != null && !loading) library.savePosition(entry.id, chapter, page.currentOffset)
    }

    override fun onPause() {
        savePosition()
        super.onPause()
    }

    override fun onDestroy() {
        book?.close()
        io.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ID = "book_id"
    }
}
