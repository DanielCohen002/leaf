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
    var keepScreenOn: Boolean
        get() = sp.getBoolean("keepon", true)
        set(v) = sp.edit().putBoolean("keepon", v).apply()
}
