package com.djcohen.offlinereader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** [src] is the folder document this book was synced from (empty if it was added by hand). */
data class Entry(val id: String, val fileName: String, val title: String, val ext: String, val src: String = "")

/** Books are copied into app-private storage; the index and reading positions live in SharedPreferences. */
class Library(private val context: Context) {
    private val sp = context.getSharedPreferences("library", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "books").apply { mkdirs() }
    private val coverDir = File(context.filesDir, "covers").apply { mkdirs() }

    /** Library view: false = list, true = grid of covers. Remembered between launches. */
    var gridMode: Boolean
        get() = sp.getBoolean("grid", false)
        set(v) = sp.edit().putBoolean("grid", v).apply()

    // --- covers ---------------------------------------------------------------------------

    private fun coverFile(id: String) = File(coverDir, "$id.jpg")
    private fun noCoverMarker(id: String) = File(coverDir, "$id.none")

    /** True if a cover has not been made yet (and we haven't already found that the book has none). */
    fun needsCover(e: Entry) = !coverFile(e.id).exists() && !noCoverMarker(e.id).exists()

    fun loadCover(e: Entry): Bitmap? = coverFile(e.id).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    /** Make and save a small cover picture for [e], or remember that it has none. Slow: call off the UI thread. */
    fun makeCover(e: Entry) {
        try {
            val bmp: Bitmap? = if (ImageBooks.isImageFormat(e.ext)) {
                val book = ImageBooks.open(file(e), e.ext)
                try { book.render(0, 480, 720) } finally { book.close() }
            } else {
                val book = BookLoader.open(file(e), e.ext)
                try { book.cover(480, 720) } finally { book.close() }
            }
            if (bmp == null) {
                noCoverMarker(e.id).createNewFile()
                return
            }
            val scale = minOf(360f / bmp.width, 540f / bmp.height, 1f)
            val small = if (scale < 1f) {
                Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
            } else bmp
            coverFile(e.id).outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        } catch (_: Throwable) {
            try { noCoverMarker(e.id).createNewFile() } catch (_: Throwable) { }
        }
    }

    private val collator = java.text.Collator.getInstance().apply { strength = java.text.Collator.PRIMARY }

    /**
     * Books in display order: anything you've read comes first, most recently read at the top;
     * everything not read yet follows in alphabetical order.
     */
    fun entries(): List<Entry> {
        val arr = JSONArray(sp.getString("index", "[]"))
        val all = (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Entry(o.getString("id"), o.getString("file"), o.getString("title"), o.getString("ext"), o.optString("src", ""))
        }
        val read = HashMap<String, Long>()
        for (e in all) read[e.id] = lastRead(e.id)
        return all.sortedWith { a, b ->
            val ta = read[a.id] ?: 0L
            val tb = read[b.id] ?: 0L
            when {
                ta > 0 && tb > 0 -> if (ta != tb) tb.compareTo(ta) else collator.compare(a.title, b.title)
                ta > 0 -> -1
                tb > 0 -> 1
                else -> collator.compare(a.title, b.title)
            }
        }
    }

    /** The book read most recently, or null if nothing has been read yet. */
    fun lastOpened(): Entry? = entries().firstOrNull()?.takeIf { lastRead(it.id) > 0 && file(it).exists() }

    /** When the book was last read (0 = never). Books read before timestamps existed count as read, long ago. */
    private fun lastRead(id: String): Long {
        val t = sp.getLong("read_$id", 0L)
        if (t > 0) return t
        val (chapter, offset) = position(id)
        return if (chapter > 0 || offset > 0) 1L else 0L
    }

    private fun save(list: List<Entry>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("file", it.fileName).put("title", it.title).put("ext", it.ext).put("src", it.src))
        }
        sp.edit().putString("index", arr.toString()).apply()
    }

    fun file(e: Entry) = File(dir, e.fileName)

    // --- synced folder ----------------------------------------------------------------------

    /** The folder (a tree URI chosen with the system picker) whose books are added automatically, or null. */
    var syncFolder: String?
        get() = sp.getString("syncfolder", null)
        set(v) { if (v == null) sp.edit().remove("syncfolder").apply() else sp.edit().putString("syncfolder", v).apply() }

    /** Folder files the user removed from Leaf: never added back automatically. */
    fun isIgnored(src: String) = sp.getStringSet("ignored_src", emptySet())?.contains(src) == true

    /** Forget which folder files were removed, so a manual sync can bring every missing book back. */
    fun clearIgnored() {
        sp.edit().remove("ignored_src").apply()
    }

    fun ignore(src: String) {
        if (src.isEmpty()) return
        val set = HashSet(sp.getStringSet("ignored_src", emptySet()) ?: emptySet())
        set.add(src)
        sp.edit().putStringSet("ignored_src", set).apply()
    }

    /** Remember where an already-imported book came from, so a later folder scan doesn't add it twice. */
    fun setSource(e: Entry, src: String) {
        save(entries().map { if (it.id == e.id) it.copy(src = src) else it })
    }

    /** Size of the stored copy, used to recognize a folder file that was already added by hand. */
    fun storedSize(e: Entry): Long = file(e).length()

    /** Copy [uri] in and validate it. Throws with a readable message if the format is unsupported or broken. */
    fun import(uri: Uri, source: String = ""): Entry {
        var display = "book"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) display = it.getString(0) ?: display
        }
        val originalExt = display.substringAfterLast('.', "").lowercase()
        if (originalExt !in BookLoader.supportedExtensions) throw IllegalArgumentException("Unsupported file type: $display")
        val id = UUID.randomUUID().toString()
        var dest = File(dir, "$id.$originalExt")
        var ext = originalExt
        context.contentResolver.openInputStream(uri)!!.use { input -> dest.outputStream().use { input.copyTo(it) } }
        try {
            // RAR / 7z / tar comics are unpacked once into a plain .cbz, which the comic reader opens directly.
            // (Many ".cbr" files are really zips; those are simply renamed.)
            if (originalExt in ComicConverter.archiveExtensions) {
                val converted = File(dir, "$id.cbz")
                try {
                    if (ComicConverter.isZip(dest)) {
                        if (!dest.renameTo(converted)) { dest.copyTo(converted); dest.delete() }
                    } else {
                        ComicConverter.toCbz(dest, converted)
                        dest.delete()
                    }
                } catch (t: Throwable) {
                    converted.delete()
                    throw t
                }
                dest = converted
                ext = "cbz"
            }
            // Books that carry their own title (EPUB, MOBI, FB2...) use it; the rest are named after the original
            // file. The stored copy is called "<id>.<ext>", so a title equal to the id means "no real title".
            val fromName = display.substringBeforeLast('.').replace('_', ' ').trim()
            fun pick(bookTitle: String) = bookTitle.trim().takeIf { it.isNotBlank() && it != id && it != id.replace('_', ' ') } ?: fromName
            val title: String
            if (ImageBooks.isImageFormat(ext)) {
                val book = ImageBooks.open(dest, ext)
                title = pick(book.title)
                book.close()
            } else {
                val book = BookLoader.open(dest, ext)
                title = pick(book.title)
                book.close()
            }
            val entry = Entry(id, dest.name, title, ext, source)
            save(entries() + entry)
            return entry
        } catch (t: Throwable) {
            File(dir, "$id.$originalExt").delete()
            File(dir, "$id.cbz").delete()
            throw IllegalArgumentException("Couldn't read $display: ${t.message}")
        }
    }

    fun rename(e: Entry, newTitle: String) {
        val t = newTitle.trim()
        if (t.isEmpty()) return
        save(entries().map { if (it.id == e.id) it.copy(title = t) else it })
    }

    /** [ignoreSource] = true when the user removed the book (it must not come back by itself); false when its file left the folder. */
    fun remove(e: Entry, ignoreSource: Boolean = true) {
        file(e).delete()
        coverFile(e.id).delete()
        noCoverMarker(e.id).delete()
        if (ignoreSource) ignore(e.src)   // removed on purpose: don't bring it back from the synced folder
        save(entries().filter { it.id != e.id })
        sp.edit().remove("pos_${e.id}").remove("postitle_${e.id}").remove("read_${e.id}").apply()
    }

    /** Reading position: chapter index and character offset inside that chapter. */
    fun position(id: String): Pair<Int, Int> {
        val s = sp.getString("pos_$id", null) ?: return 0 to 0
        val parts = s.split(':')
        return (parts.getOrNull(0)?.toIntOrNull() ?: 0) to (parts.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    /** [title] is the chapter's name, kept so the library can say where you left off. */
    fun savePosition(id: String, chapter: Int, offset: Int, title: String? = null) {
        val ed = sp.edit().putString("pos_$id", "$chapter:$offset").putLong("read_$id", System.currentTimeMillis())
        if (title != null) ed.putString("postitle_$id", title)
        ed.apply()
    }

    fun positionTitle(id: String): String? = sp.getString("postitle_$id", null)

    fun setPositionTitle(id: String, title: String) {
        sp.edit().putString("postitle_$id", title).apply()
    }
}
