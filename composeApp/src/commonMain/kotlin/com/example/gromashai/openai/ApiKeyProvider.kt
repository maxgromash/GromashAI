package com.example.gromashai.openai

interface ApiKeyProvider {
    fun get(): String
}

// ВАЖНО: expect class не имплементит интерфейс напрямую
expect class PlatformApiKeyProvider() {
    fun get(): String
}
