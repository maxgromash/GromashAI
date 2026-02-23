package com.example.gromashai.openai

actual class PlatformApiKeyProvider actual constructor() {
    actual fun getOpenAiKey(): String = ""
    actual fun getHfToken(): String = ""
}
