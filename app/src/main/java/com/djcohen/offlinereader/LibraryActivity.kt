package com.djcohen.offlinereader

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.widget.ImageView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class LibraryActivity : Activity() {
    private lateinit var library: Library
    private lateinit var list: ListView
    private lateinit var empty: View
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var entries: List<Entry> = emptyList()

    private val adapter = object : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val e = entries[position]
            val row = (convertView as? LinearLayout) ?: LinearLayout(this@LibraryActivity).apply {
                // A cream "page" card with a leaf-green tab on the left.
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), rounded(PAGE_PRESSED, 18))
                    addState(intArrayOf(), rounded(PAGE, 18))
                }
                setPadding(dp(14), dp(14), dp(16), dp(14))
                addView(View(context).apply { background = rounded(LEAF, 3) }, LinearLayout.LayoutParams(dp(5), dp(38)).apply { marginEnd = dp(14) })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(context).apply {
                        textSize = 17f; maxLines = 2; setTextColor(INK)
                        typeface = Typeface.create("serif", Typeface.BOLD)
                    })
                    addView(TextView(context).apply { textSize = 13f; setTextColor(MUTED) })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            val col = row.getChildAt(1) as LinearLayout
            (col.getChildAt(0) as TextView).text = e.title
            val (chapter, _) = library.position(e.id)
            val (_, offset) = library.position(e.id)
            val resume = when {
                ImageBooks.isImageFormat(e.ext) -> if (offset > 0) "  ·  continue from page ${offset + 1}" else ""
                chapter > 0 -> "  ·  continue from section ${chapter + 1}"
                else -> ""
            }
            (col.getChildAt(1) as TextView).text = e.ext.uppercase() + resume
            return row
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = radiusDp * resources.displayMetrics.density
    }

    /** The Leaf mark (half leaf, half page), drawn on the forest background. */
    private fun logo() = ImageView(this).apply {
        setImageResource(R.drawable.ic_launcher_foreground)
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = Library(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(FOREST) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(16), dp(8))
        }
        header.addView(logo(), LinearLayout.LayoutParams(dp(64), dp(64)))
        header.addView(TextView(this).apply {
            text = "Leaf"; textSize = 30f; setTextColor(PAGE)
            typeface = Typeface.create("serif", Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(Button(this).apply {
            text = "Add books"; isAllCaps = false; textSize = 15f; setTextColor(FOREST)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), rounded(LEAF_PRESSED, 24))
                addState(intArrayOf(), rounded(LEAF, 24))
            }
            stateListAnimator = null
            setPadding(dp(20), 0, dp(20), 0)
            setOnClickListener { pickFiles() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
        root.addView(header)

        list = ListView(this).apply {
            divider = ColorDrawable(Color.TRANSPARENT)
            dividerHeight = dp(10)
            selector = ColorDrawable(Color.TRANSPARENT)
            setPadding(dp(16), dp(8), dp(16), dp(16))
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        empty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(64))
            addView(logo(), LinearLayout.LayoutParams(dp(150), dp(150)))
            addView(TextView(context).apply {
                text = "No books yet.\nTap “Add books” and choose EPUB, MOBI, FB2, PDF, CBZ comics, TXT, HTML or Markdown files from your phone.\n\nLeaf has no internet access at all."
                textSize = 15f; gravity = Gravity.CENTER; setTextColor(PAGE); alpha = 0.8f
            })
        }
        list.emptyView = empty
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> openBook(entries[pos]) }
        list.setOnItemLongClickListener { _, _, pos, _ -> showOptions(entries[pos]); true }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(empty, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        root.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data?.let { importUris(listOf(it), openFirst = true) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        entries = library.entries()
        adapter.notifyDataSetChanged()
        empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun pickFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return
        val uris = mutableListOf<Uri>()
        data.data?.let { uris += it }
        data.clipData?.let { c -> for (k in 0 until c.itemCount) uris += c.getItemAt(k).uri }
        importUris(uris.distinct(), openFirst = false)
    }

    private fun importUris(uris: List<Uri>, openFirst: Boolean) {
        Toast.makeText(this, "Importing…", Toast.LENGTH_SHORT).show()
        io.execute {
            val errors = mutableListOf<String>()
            var first: Entry? = null
            for (u in uris) {
                try {
                    val e = library.import(u)
                    if (first == null) first = e
                } catch (t: Throwable) {
                    errors += t.message ?: "Import failed"
                }
            }
            ui.post {
                refresh()
                if (errors.isNotEmpty()) Toast.makeText(this, errors.joinToString("\n"), Toast.LENGTH_LONG).show()
                if (openFirst) first?.let { openBook(it) }
            }
        }
    }

    private fun openBook(e: Entry) {
        val target = if (ImageBooks.isImageFormat(e.ext)) ImageReaderActivity::class.java else ReaderActivity::class.java
        startActivity(Intent(this, target).putExtra(ReaderActivity.EXTRA_ID, e.id))
    }

    private fun showOptions(e: Entry) {
        AlertDialog.Builder(this)
            .setTitle(e.title)
            .setItems(arrayOf("Rename", "Remove")) { _, which -> if (which == 0) promptRename(e) else confirmDelete(e) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptRename(e: Entry) {
        val input = android.widget.EditText(this).apply {
            setText(e.title); setSelection(text.length); isSingleLine = false; maxLines = 3
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        AlertDialog.Builder(this)
            .setTitle("Rename")
            .setView(LinearLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input, LinearLayout.LayoutParams(-1, -2)) })
            .setPositiveButton("Save") { _, _ -> library.rename(e, input.text.toString()); refresh() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(e: Entry) {
        AlertDialog.Builder(this)
            .setTitle("Remove book?")
            .setMessage(e.title)
            .setPositiveButton("Remove") { _, _ -> library.remove(e); refresh() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        private const val REQ_PICK = 1
        // Leaf palette (matches the icon).
        private const val FOREST = 0xFF1E3A2A.toInt()
        private const val LEAF = 0xFF6CC070.toInt()
        private const val LEAF_PRESSED = 0xFF55A85A.toInt()
        private const val PAGE = 0xFFFFF8E7.toInt()
        private const val PAGE_PRESSED = 0xFFEADFC0.toInt()
        private const val INK = 0xFF22301F.toInt()
        private const val MUTED = 0xFF5E7F61.toInt()
    }
}
