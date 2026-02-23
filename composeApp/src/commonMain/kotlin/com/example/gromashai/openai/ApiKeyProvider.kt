package com.example.gromashai.openai

interface ApiKeyProvider {
    fun getOpenAiKey(): String
    fun getHfToken(): String
}

expect class PlatformApiKeyProvider() {
    fun getOpenAiKey(): String
    fun getHfToken(): String
}
