package com.djcohen.offlinereader

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable

private const val LEAF = 0xFF6CC070.toInt()
private const val LEAF_PRESSED = 0xFF55A85A.toInt()
private const val FOREST = 0xFF1E3A2A.toInt()

/**
 * Show a dialog with Leaflet-colored buttons. The system draws dialog buttons in its own accent (blue on this phone)
 * regardless of the app theme, so they are restyled here: a leaf-green pill for the main button, green text for the rest.
 */
fun AlertDialog.Builder.showLeaf(): AlertDialog {
    val dialog = create()
    dialog.setOnShowListener {
        // The system draws the title and list rows in its own dark text colour whatever the theme says.
        val titleId = dialog.context.resources.getIdentifier("alertTitle", "id", "android")
        dialog.findViewById<android.widget.TextView>(titleId)?.setTextColor(0xFFFFF8E7.toInt())
        dialog.listView?.let { lv ->
            lv.setOnHierarchyChangeListener(object : android.view.ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewAdded(parent: android.view.View?, child: android.view.View?) { tint(child) }
                override fun onChildViewRemoved(parent: android.view.View?, child: android.view.View?) {}
            })
            for (i in 0 until lv.childCount) tint(lv.getChildAt(i))
        }
        val density = dialog.context.resources.displayMetrics.density
        fun pill(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = 24 * density }
        val pressed = intArrayOf(android.R.attr.state_pressed)
        for (which in intArrayOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) {
            val button = dialog.getButton(which) ?: continue
            button.isAllCaps = false
            if (which == AlertDialog.BUTTON_POSITIVE) {
                button.setTextColor(FOREST)
                button.background = StateListDrawable().apply {
                    addState(pressed, pill(LEAF_PRESSED))
                    addState(intArrayOf(), pill(LEAF))
                }
            } else {
                button.setTextColor(LEAF)
                button.background = StateListDrawable().apply {
                    addState(pressed, pill(0x336CC070))
                    addState(intArrayOf(), pill(Color.TRANSPARENT))
                }
            }
        }
    }
    dialog.show()
    return dialog
}

private fun tint(v: android.view.View?) {
    if (v is android.widget.TextView) v.setTextColor(0xFFFFF8E7.toInt())
    else if (v is android.view.ViewGroup) for (i in 0 until v.childCount) tint(v.getChildAt(i))
}
