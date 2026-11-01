package com.djcohen.offlinereader

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Entry(val id: String, val fileName: String, val title: String, val ext: String)

/** Books are copied into app-private storage; the index and reading positions live in SharedPreferences. */
class Library(private val context: Context) {
    private val sp = context.getSharedPreferences("library", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "books").apply { mkdirs() }

    fun entries(): List<Entry> {
        val arr = JSONArray(sp.getString("index", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Entry(o.getString("id"), o.getString("file"), o.getString("title"), o.getString("ext"))
        }
    }

    private fun save(list: List<Entry>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("file", it.fileName).put("title", it.title).put("ext", it.ext))
        }
        sp.edit().putString("index", arr.toString()).apply()
    }

    fun file(e: Entry) = File(dir, e.fileName)

    /** Copy [uri] in and validate it. Throws with a readable message if the format is unsupported or broken. */
    fun import(uri: Uri): Entry {
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
            val entry = Entry(id, dest.name, title, ext)
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

    fun remove(e: Entry) {
        file(e).delete()
        save(entries().filter { it.id != e.id })
        sp.edit().remove("pos_${e.id}").remove("postitle_${e.id}").apply()
    }

    /** Reading position: chapter index and character offset inside that chapter. */
    fun position(id: String): Pair<Int, Int> {
        val s = sp.getString("pos_$id", null) ?: return 0 to 0
        val parts = s.split(':')
        return (parts.getOrNull(0)?.toIntOrNull() ?: 0) to (parts.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    /** [title] is the chapter's name, kept so the library can say where you left off. */
    fun savePosition(id: String, chapter: Int, offset: Int, title: String? = null) {
        val ed = sp.edit().putString("pos_$id", "$chapter:$offset")
        if (title != null) ed.putString("postitle_$id", title)
        ed.apply()
    }

    fun positionTitle(id: String): String? = sp.getString("postitle_$id", null)

    fun setPositionTitle(id: String, title: String) {
        sp.edit().putString("postitle_$id", title).apply()
    }
}
