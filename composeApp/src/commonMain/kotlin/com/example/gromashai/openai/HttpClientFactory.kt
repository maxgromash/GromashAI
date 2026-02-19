package com.example.gromashai.openai

import io.ktor.client.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

fun createHttpClient(): HttpClient = HttpClient {
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
        )
    }
    install(HttpTimeout) {
        // сколько ждём установления соединения
        connectTimeoutMillis = 15_000

        // сколько ждём ответа целиком (можно 60-120с)
        requestTimeoutMillis = 90_000

        // сколько ждём байтов в сокете (часто именно это падает)
        socketTimeoutMillis = 90_000
    }

}

