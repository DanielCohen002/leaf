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
    /** Display name to Android system font family. Bundled fonts can be added later. */
    val all = listOf(
        "Serif" to "serif",
        "Sans" to "sans-serif",
        "Condensed" to "sans-serif-condensed",
        "Monospace" to "monospace",
        "Casual" to "casual",
        "Slab" to "serif-monospace",
    )

    fun typeface(index: Int): Typeface = Typeface.create(all[index.coerceIn(all.indices)].second, Typeface.NORMAL)
}

class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var font: Int
        get() = sp.getInt("font", 0)
        set(v) = sp.edit().putInt("font", v).apply()
    var textSizeSp: Int
        get() = sp.getInt("size", 19)
        set(v) = sp.edit().putInt("size", v).apply()
    /** Line spacing multiplier, stored in hundredths. */
    var spacing: Int
        get() = sp.getInt("spacing", 140)
        set(v) = sp.edit().putInt("spacing", v).apply()
    var theme: Int
        get() = sp.getInt("theme", 2)
        set(v) = sp.edit().putInt("theme", v).apply()
    var volumeKeys: Boolean
        get() = sp.getBoolean("volkeys", true)
        set(v) = sp.edit().putBoolean("volkeys", v).apply()
    /** false: volume-down = next page. true: volume-up = next page. */
    var swapVolume: Boolean
        get() = sp.getBoolean("swapvol", false)
        set(v) = sp.edit().putBoolean("swapvol", v).apply()
    /** Comics / PDF: right-to-left page order (manga). */
    var rightToLeft: Boolean
        get() = sp.getBoolean("rtl", false)
        set(v) = sp.edit().putBoolean("rtl", v).apply()
    /** Comics / PDF: invert page colors (night reading). */
    var invertImages: Boolean
        get() = sp.getBoolean("invimg", false)
        set(v) = sp.edit().putBoolean("invimg", v).apply()
    /** Comics / PDF layout: 0 = one page, 1 = two pages side by side (landscape), 2 = continuous vertical scroll. */
    var imageLayout: Int
        get() = sp.getInt("imglayout", 0)
        set(v) = sp.edit().putInt("imglayout", v).apply()
    /** Two-page mode: show the first page (the cover) on its own. */
    var coverAlone: Boolean
        get() = sp.getBoolean("coveralone", true)
        set(v) = sp.edit().putBoolean("coveralone", v).apply()
    /** Text books: scroll continuously instead of turning pages. */
    var textScroll: Boolean
        get() = sp.getBoolean("textscroll", false)
        set(v) = sp.edit().putBoolean("textscroll", v).apply()
    var keepScreenOn: Boolean
        get() = sp.getBoolean("keepon", true)
        set(v) = sp.edit().putBoolean("keepon", v).apply()
}
