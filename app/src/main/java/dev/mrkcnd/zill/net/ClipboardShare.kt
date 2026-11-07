package dev.mrkcnd.zill.net

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * This device's Share Clipboard switch, as in Sill's iPhone app (StreamClient+Clipboard.swift): on
 * unless turned off, never sent to the Mac. On, the session subscribes to the Mac's clipboard (an
 * empty kind 32) and Paste sends text; off, it sends `{"stop":true}`, ignores the Mac's text and
 * Paste sends nothing.
 */
object ClipboardShare {
    private const val PREFS = "device"
    private const val KEY = "shareClipboard"
    private val _on = MutableStateFlow(true)
    val on: StateFlow<Boolean> = _on

    fun load(ctx: Context) {
        _on.value = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)
    }

    fun set(ctx: Context, value: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply()
        _on.value = value
    }
}
