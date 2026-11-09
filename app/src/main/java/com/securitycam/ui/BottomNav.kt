package com.securitycam.ui

import android.app.Activity
import android.content.Intent
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.securitycam.R

/**
 * Wires the Live / History / Settings bar shared by the three top-level screens.
 * The live screen stays at the bottom of the back stack; the other two sit on top of it.
 */
object BottomNav {

    fun setup(activity: Activity, nav: BottomNavigationView, selectedItemId: Int) {
        nav.selectedItemId = selectedItemId
        nav.setOnItemSelectedListener { item ->
            if (item.itemId != selectedItemId) {
                open(activity, item.itemId)
            }
            // The bar on this screen keeps showing this screen; the target shows its own.
            item.itemId == selectedItemId
        }
    }

    private fun open(activity: Activity, itemId: Int) {
        val target = when (itemId) {
            R.id.nav_live -> MainActivity::class.java
            R.id.nav_history -> EventHistoryActivity::class.java
            R.id.nav_settings -> SettingsActivity::class.java
            else -> return
        }
        val intent = Intent(activity, target).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        if (target == MainActivity::class.java) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        activity.startActivity(intent)
        if (activity !is MainActivity) {
            activity.finish()
        }
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(0, 0)
    }
}

/** Adds the status bar / display cutout height to this view's top padding (edge-to-edge). */
fun View.padTopForSystemBars() {
    val initialTop = paddingTop
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        view.updatePadding(top = initialTop + bars.top)
        insets
    }
}
