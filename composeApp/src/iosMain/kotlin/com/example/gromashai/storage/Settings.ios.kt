package com.example.gromashai.storage

import platform.Foundation.NSUserDefaults

actual class Settings actual constructor(context: Any?) {
    actual fun putString(key: String, value: String) {
        NSUserDefaults.standardUserDefaults.setObject(value, key)
    }

    actual fun getString(key: String): String? {
        return NSUserDefaults.standardUserDefaults.stringForKey(key)
    }
}
