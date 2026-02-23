package com.example.gromashai.openai

import com.example.gromashai.BuildConfig

actual class PlatformApiKeyProvider actual constructor() {
    actual fun getOpenAiKey(): String = BuildConfig.OPENAI_API_KEY
    actual fun getHfToken(): String = BuildConfig.HF_API_TOKEN
}
