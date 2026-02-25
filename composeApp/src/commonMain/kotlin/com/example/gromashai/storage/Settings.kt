package com.example.gromashai.storage

/**
 * Простое хранилище ключ-значение.
 */
expect class Settings(context: Any? = null) {
    fun putString(key: String, value: String)
    fun getString(key: String): String?
}
