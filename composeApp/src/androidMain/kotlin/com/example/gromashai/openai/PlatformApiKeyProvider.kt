package com.example.gromashai.openai


actual class PlatformApiKeyProvider actual constructor() : ApiKeyProvider {
    actual override fun get(): String = com.example.gromashai.BuildConfig.OPENAI_API_KEY

}
