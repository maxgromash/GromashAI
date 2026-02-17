package com.example.gromashai.openai

actual class PlatformApiKeyProvider actual constructor() : ApiKeyProvider {
    actual override fun get(): String = error("OPENAI key not configured for iOS yet")
}