package com.example.gromashai.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LuckyHoroscope(
    val luckyNumber: Int,
    val horoscope: String
)

/**
 * Минимально нужная часть ответа Responses API.
 * В Responses API есть поле output_text (удобно), но для надёжности парсим output -> message -> content.
 */
@Serializable
data class ResponsesApiResponse(
    @SerialName("output_text") val outputText: String? = null
)
