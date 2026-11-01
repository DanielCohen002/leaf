package com.djcohen.offlinereader

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
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
    private lateinit var empty: TextView
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
                orientation = LinearLayout.VERTICAL
                val p = dp(16)
                setPadding(p, dp(12), p, dp(12))
                addView(TextView(context).apply { textSize = 17f; maxLines = 2 })
                addView(TextView(context).apply { textSize = 13f; alpha = 0.6f })
            }
            (row.getChildAt(0) as TextView).text = e.title
            val (chapter, _) = library.position(e.id)
            val (_, offset) = library.position(e.id)
            val resume = when {
                ImageBooks.isImageFormat(e.ext) -> if (offset > 0) "  ·  continue from page ${offset + 1}" else ""
                chapter > 0 -> "  ·  continue from section ${chapter + 1}"
                else -> ""
            }
            (row.getChildAt(1) as TextView).text = e.ext.uppercase() + resume
            return row
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = Library(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(8), dp(8))
        }
        header.addView(TextView(this).apply { text = "Library"; textSize = 26f }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(Button(this).apply { text = "Add books"; setOnClickListener { pickFiles() } })
        root.addView(header)

        list = ListView(this)
        empty = TextView(this).apply {
            text = "No books yet.\nTap “Add books” and choose EPUB, MOBI, FB2, PDF, CBZ comics, TXT, HTML or Markdown files from your phone.\n\nThis app has no internet access at all."
            textSize = 15f; gravity = Gravity.CENTER; setPadding(dp(32), dp(32), dp(32), dp(32)); alpha = 0.7f
        }
        list.emptyView = empty
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> openBook(entries[pos]) }
        list.setOnItemLongClickListener { _, _, pos, _ -> confirmDelete(entries[pos]); true }
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
    }
}
