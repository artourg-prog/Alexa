package com.tailorshop.counter

import android.app.AlertDialog
import android.app.Dialog
import android.os.Build
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

// The on-screen navigation bar is hidden on the home test tablet only (SM-T820, owner 11-03): every other device, the
// Fold included, behaves exactly as before. A swipe from the edge brings the bar back for a moment, so Back still works.
object TabletBars {
    /** Pure: only this exact model. */
    fun applies(model: String) = model.equals("SM-T820", ignoreCase = true)

    private val active get() = applies(Build.MODEL)

    fun hide(window: Window?) {
        if (!active) return
        window?.insetsController?.run {
            hide(WindowInsets.Type.navigationBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** A dialog has its own window, and taking focus would bring the bar back: it is shown unfocusable, hidden, then focusable.
     *  One opened from another dialog gets focus only after that one closes, so it hides again whenever it gains focus. */
    fun show(dialog: Dialog) {
        if (!active) { dialog.show(); return }
        val flag = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        dialog.window?.addFlags(flag); dialog.show(); hide(dialog.window); dialog.window?.clearFlags(flag)
        dialog.window?.decorView?.viewTreeObserver?.addOnWindowFocusChangeListener { focused -> if (focused) hide(dialog.window) }
    }
}

/** AlertDialog.Builder.show(), with the tablet's bar rule. */
fun AlertDialog.Builder.showDialog(): AlertDialog = create().also(TabletBars::show)
