package com.djcohen.offlinereader

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

class Sense(val pos: String, val gloss: String) {
    /** Definition without the quoted usage examples. */
    val definition: String get() = gloss.substringBefore(";").trim()
    val examples: List<String>
        get() = Regex("\"([^\"]+)\"").findAll(gloss.substringAfter(";", "")).map { it.groupValues[1].trim() }.toList()
}

/** Bump when assets/dict.db is rebuilt so installed copies refresh. */
private const val DICT_VERSION = "1"

class DictEntry(val word: String, val senses: List<Sense>)

/** Offline English dictionary backed by WordNet 3.0 (assets/dict.db, copied to private storage on first use). */
class Dictionary(private val context: Context) {
    private var db: SQLiteDatabase? = null

    @Synchronized
    private fun open(): SQLiteDatabase {
        db?.let { return it }
        val dest = File(context.filesDir, "dict.db")
        val stamp = File(context.filesDir, "dict.version")
        val current = DICT_VERSION
        if (!dest.exists() || !stamp.exists() || stamp.readText() != current) {
            val tmp = File(context.filesDir, "dict.db.tmp")
            context.assets.open("dict.db").use { i -> tmp.outputStream().use { i.copyTo(it) } }
            tmp.renameTo(dest)
            stamp.writeText(current)
        }
        return SQLiteDatabase.openDatabase(dest.path, null, SQLiteDatabase.OPEN_READONLY).also { db = it }
    }

    /** Look up [raw] (a word or phrase as selected in the text). Returns every matching headword, best first. */
    fun lookup(raw: String): List<DictEntry> {
        val database = open()
        val cleaned = raw.lowercase().replace('’', '\'').replace('‘', '\'')
            .trim { !it.isLetterOrDigit() }
            .replace(Regex("\\s+"), " ")
        if (cleaned.isEmpty()) return emptyList()
        val key = cleaned.replace(' ', '_')

        val candidates = LinkedHashSet<String>()
        candidates += key
        if (key.endsWith("'s")) candidates += key.dropLast(2)
        if (key.endsWith("s'")) candidates += key.dropLast(1)
        candidates += key.replace("'", "")

        val bases = LinkedHashSet<String>()
        for (c in candidates.toList()) {
            database.rawQuery("SELECT base FROM x WHERE form = ?", arrayOf(c)).use { if (it.moveToFirst()) bases += it.getString(0) }
            bases += morphy(c)
        }
        candidates += bases

        val out = ArrayList<DictEntry>()
        for (c in candidates) {
            database.rawQuery("SELECT def FROM w WHERE word = ?", arrayOf(c)).use {
                if (it.moveToFirst()) {
                    val senses = it.getString(0).split('\n').mapNotNull { line ->
                        val p = line.indexOf('\t')
                        if (p < 0) null else Sense(line.substring(0, p), line.substring(p + 1))
                    }
                    out += DictEntry(c.replace('_', ' '), senses)
                }
            }
            if (out.size >= 3) break
        }
        return out
    }

    /** WordNet-style detachment rules plus doubled-consonant handling ("stopped" -> "stop"). */
    private fun morphy(w: String): List<String> {
        val rules = listOf(
            "s" to "", "ses" to "s", "xes" to "x", "zes" to "z", "ches" to "ch", "shes" to "sh", "men" to "man", "ies" to "y",
            "es" to "e", "es" to "", "ed" to "e", "ed" to "", "ing" to "e", "ing" to "",
            "er" to "", "est" to "", "er" to "e", "est" to "e", "ly" to "", "ness" to "",
        )
        val out = LinkedHashSet<String>()
        for ((suffix, repl) in rules) {
            if (w.length > suffix.length + 1 && w.endsWith(suffix)) {
                val stem = w.dropLast(suffix.length) + repl
                out += stem
                if (repl.isEmpty() && stem.length > 2 && stem[stem.length - 1] == stem[stem.length - 2] && stem.last() !in "aeiou") {
                    out += stem.dropLast(1)
                }
            }
        }
        return out.toList()
    }

    fun close() { db?.close(); db = null }
}
