package com.djcohen.offlinereader

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Finds books inside a folder the user picked with the system folder picker. This needs no storage permission:
 * Android grants Leaf access to that one folder only.
 */
object FolderSync {
    class Doc(val uri: Uri, val name: String, val size: Long)

    /** Every book-like file under [tree]. Hidden folders (".MoonReader" and the like) are skipped, and so are repeats. */
    fun scan(context: Context, tree: Uri): List<Doc> {
        val found = ArrayList<Doc>()
        val seen = HashSet<String>()
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )

        fun walk(docId: String, depth: Int) {
            if (depth > 12) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            // A listing that fails part-way must not look like "those books were deleted", so it aborts the whole scan.
            val cursor = context.contentResolver.query(children, columns, null, null, null)
                ?: throw IllegalStateException("The folder could not be listed")
            cursor.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2)
                    val size = if (c.isNull(3)) -1L else c.getLong(3)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (!name.startsWith(".")) walk(id, depth + 1)
                    } else if (!name.startsWith(".") && name.substringAfterLast('.', "").lowercase() in BookLoader.supportedExtensions) {
                        // The same book copied into two folders (a cloud cache, say) is listed once.
                        if (seen.add("${name.lowercase()}|$size")) {
                            found += Doc(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, size)
                        }
                    }
                }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(tree), 0)
        return found
    }

    /** RAR / 7z / tar comics are stored as .cbz once imported. */
    fun storedExt(ext: String) = if (ext in ComicConverter.archiveExtensions) "cbz" else ext

    /** A short name for the folder, for menus ("primary:Books" becomes "Books"). */
    fun label(tree: String): String =
        Uri.decode(Uri.parse(tree).lastPathSegment ?: "").substringAfterLast(':').substringAfterLast('/').ifEmpty { "folder" }
}
