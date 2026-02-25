package com.example.gromashai.storage

import android.content.Context
import android.content.SharedPreferences

actual class Settings actual constructor(context: Any?) {
    private val prefs: SharedPreferences = (context as Context).getSharedPreferences("gromash_ai_prefs", Context.MODE_PRIVATE)

    actual fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    actual fun getString(key: String): String? {
        return prefs.getString(key, null)
    }
}
