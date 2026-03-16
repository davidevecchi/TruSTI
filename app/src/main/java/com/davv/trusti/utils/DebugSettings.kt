package com.davv.trusti.utils

import android.content.Context

object DebugSettings {
    private const val PREFS = "trusti_debug"
    private const val KEY_LOG_MESSAGES = "log_messages"

    fun isMessageLoggingEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_LOG_MESSAGES, false)

    fun setMessageLoggingEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_LOG_MESSAGES, enabled).apply()
    }
}
