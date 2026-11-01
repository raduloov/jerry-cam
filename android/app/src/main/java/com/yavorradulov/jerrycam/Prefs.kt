package com.yavorradulov.jerrycam

import android.content.Context

/** Whether the owner wants Jerry Cam running (they can press Stop on the setup screen). */
object Prefs {
    private const val FILE = "jerrycam"
    private const val ENABLED = "enabled"

    fun enabled(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, enabled).apply()
    }
}
