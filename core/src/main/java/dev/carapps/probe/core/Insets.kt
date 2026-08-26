package dev.carapps.probe.core

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Pads a root view clear of the system bars.
 *
 * Targeting SDK 35 opts into edge-to-edge on Android 15 whether or not the layout
 * is ready for it, so an unpadded root starts at y=0 and puts its first row under
 * the status bar. For a screen whose top row is its only controls, that renders
 * them unreachable.
 */
fun View.padForSystemBars() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, windowInsets ->
        val bars = windowInsets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        WindowInsetsCompat.CONSUMED
    }
}
