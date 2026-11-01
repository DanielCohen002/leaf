package com.djcohen.offlinereader

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface

class ThemeColors(val name: String, val fg: Int, val bg: Int)

object Themes {
    val all = listOf(
        ThemeColors("Light", 0xFF1A1A1A.toInt(), 0xFFFAFAFA.toInt()),
        ThemeColors("Sepia", 0xFF4B3621.toInt(), 0xFFF4ECD8.toInt()),
        ThemeColors("Dark", 0xFFD0D0D0.toInt(), 0xFF000000.toInt()),
    )
}

object Fonts {
    /**
     * Display name, then the font files (regular, bold, italic, bold italic) every Android phone ships in /system/fonts,
     * then the old family name used if the files aren't there. The files are loaded directly because some phones remap the
     * family names ("serif", "sans-serif-condensed"...) onto the user's system font, which made every choice look the same.
     */
    private class Choice(val name: String, val files: List<String>, val family: String)

    private val choices = listOf(
        Choice("Serif", listOf("NotoSerif-Regular", "NotoSerif-Bold", "NotoSerif-Italic", "NotoSerif-BoldItalic"), "serif"),
        Choice("Sans", listOf("Roboto-Regular"), "sans-serif"),
        Choice("Source Sans", listOf("SourceSansPro-Regular", "SourceSansPro-Bold", "SourceSansPro-Italic", "SourceSansPro-BoldItalic"), "sans-serif-condensed"),
        Choice("Monospace", listOf("DroidSansMono"), "monospace"),
        Choice("Typewriter", listOf("CutiveMono"), "serif-monospace"),
        Choice("Handwriting", listOf("ComingSoon"), "casual"),
        Choice("Script", listOf("DancingScript-Regular"), "cursive"),
        Choice("Phone's own font", emptyList(), "sans-serif"),
    )

    val all: List<Pair<String, String>> = choices.map { it.name to it.family }
    private val cache = HashMap<Int, Typeface>()

    fun typeface(index: Int): Typeface {
        val i = index.coerceIn(choices.indices)
        return cache.getOrPut(i) { build(choices[i]) }
    }

    private fun build(c: Choice): Typeface {
        try {
            if (c.files.isEmpty()) return Typeface.create(c.family, Typeface.NORMAL)
            val fonts = c.files.mapNotNull { name ->
                val f = java.io.File("/system/fonts/$name.ttf")
                if (!f.exists()) return@mapNotNull null
                val bold = name.contains("Bold")
                val italic = name.contains("Italic")
                android.graphics.fonts.Font.Builder(f).setWeight(if (bold) 700 else 400)
                    .setSlant(if (italic) android.graphics.fonts.FontStyle.FONT_SLANT_ITALIC else android.graphics.fonts.FontStyle.FONT_SLANT_UPRIGHT).build()
            }
            if (fonts.isEmpty()) return Typeface.create(c.family, Typeface.NORMAL)
            val family = android.graphics.fonts.FontFamily.Builder(fonts[0]).also { b -> fonts.drop(1).forEach { b.addFont(it) } }.build()
            // Fall back to the system font for characters these files don't cover (accents, symbols, other scripts).
            val system = android.graphics.fonts.FontFamily.Builder(
                android.graphics.fonts.Font.Builder(java.io.File("/system/fonts/Roboto-Regular.ttf")).build()).build()
            return Typeface.CustomFallbackBuilder(family).addCustomFallback(system).setSystemFallback("sans-serif").build()
        } catch (_: Throwable) {
            return Typeface.create(c.family, Typeface.NORMAL)
        }
    }
}

/**
 * Reading settings, remembered per file format (all EPUBs share one set, all PDFs another, and so on), not per book.
 * A format that has no saved value yet starts from the old shared value, so earlier choices carry over.
 */
class Prefs(context: Context, ext: String = "") {
    private val sp: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val format = formatOf(ext)

    private fun key(name: String) = if (format.isEmpty()) name else "$format.$name"
    private fun int(name: String, def: Int) = sp.getInt(key(name), sp.getInt(name, def))
    private fun bool(name: String, def: Boolean) = sp.getBoolean(key(name), sp.getBoolean(name, def))
    private fun putInt(name: String, v: Int) = sp.edit().putInt(key(name), v).apply()
    private fun putBool(name: String, v: Boolean) = sp.edit().putBoolean(key(name), v).apply()

    var font: Int
        get() = int("font", 0)
        set(v) = putInt("font", v)
    /** First-line paragraph indent, in tenths of an em (0 = none). */
    var indent: Int
        get() = int("indent", 15)
        set(v) = putInt("indent", v)
    var textSizeSp: Int
        get() = int("size", 19)
        set(v) = putInt("size", v)
    /** Line spacing multiplier, stored in hundredths. */
    var spacing: Int
        get() = int("spacing", 140)
        set(v) = putInt("spacing", v)
    var theme: Int
        get() = int("theme", 2)
        set(v) = putInt("theme", v)
    var volumeKeys: Boolean
        get() = bool("volkeys", true)
        set(v) = putBool("volkeys", v)
    /** false: volume-down = next page. true: volume-up = next page. */
    var swapVolume: Boolean
        get() = bool("swapvol", false)
        set(v) = putBool("swapvol", v)
    /** Comics / PDF: right-to-left page order (manga). */
    var rightToLeft: Boolean
        get() = bool("rtl", false)
        set(v) = putBool("rtl", v)
    /** Comics / PDF: invert page colors (night reading). */
    var invertImages: Boolean
        get() = bool("invimg", false)
        set(v) = putBool("invimg", v)
    /** Comics / PDF layout: 0 = one page, 1 = two pages side by side (landscape), 2 = continuous vertical scroll. */
    var imageLayout: Int
        get() = int("imglayout", 0)
        set(v) = putInt("imglayout", v)
    /** Two-page mode: show the first page (the cover) on its own. */
    var coverAlone: Boolean
        get() = bool("coveralone", true)
        set(v) = putBool("coveralone", v)
    /** Text books: scroll continuously instead of turning pages. */
    var textScroll: Boolean
        get() = bool("textscroll", false)
        set(v) = putBool("textscroll", v)
    /** Text books: line up both edges of the text. */
    var justify: Boolean
        get() = bool("justify", false)
        set(v) = putBool("justify", v)
    var keepScreenOn: Boolean
        get() = bool("keepon", true)
        set(v) = putBool("keepon", v)

    companion object {
        /** Extensions of the same kind of file share settings (.htm/.html, .azw3/.mobi, ...). */
        fun formatOf(ext: String): String = when (val e = ext.lowercase().trim()) {
            "txt", "text" -> "txt"
            "html", "htm", "xhtml" -> "html"
            "md", "markdown" -> "md"
            "mobi", "azw", "azw3", "prc" -> "kindle"
            "cbz", "cbr" -> "cbz"
            else -> e
        }
    }
}
