package com.example.gromashai.openai

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*

class OpenAiApi(
    private val http: HttpClient,
    private val apiKeyProvider: PlatformApiKeyProvider,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Универсальный метод для чата, принимающий список сообщений (включая системные и историю).
     */
    suspend fun chat(messages: List<com.example.gromashai.openai.ChatMessage>): String {
        val apiKey = apiKeyProvider.getOpenAiKey()
        
        val bodyJson = buildJsonObject {
            put("model", JsonPrimitive("gpt-4o"))
            put("messages", buildJsonArray {
                messages.forEach { msg ->
                    add(buildJsonObject {
                        put("role", JsonPrimitive(msg.role))
                        put("content", JsonPrimitive(msg.content))
                    })
                }
            })
        }

        val resp: JsonObject = http.post("https://api.openai.com/v1/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(bodyJson)
        }.body()

        return resp["choices"]?.jsonArray?.get(0)?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
            ?: "Ошибка: не удалось получить ответ"
    }

    /**
     * Day1: lucky number + horoscope через Structured Outputs.
     */
    suspend fun day1GenerateLuckyHoroscope(): String {
        val apiKey = apiKeyProvider.getOpenAiKey()

        val prompt = """
            Сгенерируй:
            1) случайное "счастливое число" от 1 до 999
            2) короткий гороскоп/пожелание на день (1-2 предложения), дружелюбно, без мистики, без пугающих предсказаний.
            Верни СТРОГО валидный JSON по схеме.
        """.trimIndent()

        val bodyJson = buildJsonObject {
            put("model", JsonPrimitive("gpt-4o"))
            put("store", JsonPrimitive(true))
            put("metadata", buildJsonObject {
                put("app", JsonPrimitive("GromashAI"))
                put("feature", JsonPrimitive("lucky_horoscope"))
            })

            put("input", buildJsonArray {
                add(buildJsonObject {
                    put("role", JsonPrimitive("system"))
                    put("content", JsonPrimitive("Ты дружелюбный ассистент. Отвечай строго JSON по схеме."))
                })
                add(buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", JsonPrimitive(prompt))
                })
            })

            put("text", buildJsonObject {
                put("format", buildJsonObject {
                    put("type", JsonPrimitive("json_schema"))
                    put("name", JsonPrimitive("lucky_horoscope"))
                    put("strict", JsonPrimitive(true))
                    put("schema", buildJsonObject {
                        put("type", JsonPrimitive("object"))
                        put("additionalProperties", JsonPrimitive(false))
                        put("properties", buildJsonObject {
                            put("luckyNumber", buildJsonObject {
                                put("type", JsonPrimitive("integer"))
                                put("minimum", JsonPrimitive(1))
                                put("maximum", JsonPrimitive(999))
                            })
                            put("horoscope", buildJsonObject {
                                put("type", JsonPrimitive("string"))
                            })
                        })
                        put("required", buildJsonArray {
                            add(JsonPrimitive("luckyNumber"))
                            add(JsonPrimitive("horoscope"))
                        })
                    })
                })
            })
        }

        val resp: JsonObject = http.post("https://api.openai.com/v1/responses") {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(bodyJson)
        }.body()

        return extractFirstOutputText(resp)
            ?: error("Пустой ответ: нет output.message.content.text. Ответ: ${resp.toString().take(700)}")
    }

    suspend fun day2Generate(
        userPrompt: String,
        formatHint: String,
        maxOutputTokens: Int?,
        stopSequence: String?,
        useLimits: Boolean,
        temperature: Double?,
    ): Day2ApiResult {
        val apiKey = apiKeyProvider.getOpenAiKey()

        val stop = stopSequence?.trim()?.takeIf { it.isNotEmpty() }

        val finalPrompt: String = if (!useLimits) {
            userPrompt.trim()
        } else {
            val stopInstruction = if (stop != null) {
                """
                УСЛОВИЕ ЗАВЕРШЕНИЯ:
                Заверши ответ строкой: $stop
                После неё не пиши ничего.
                """.trimIndent()
            } else ""

            buildString {
                appendLine(userPrompt.trim())
                appendLine()
                appendLine("Формат ответа (обязательно):")
                appendLine(formatHint.trim())
                appendLine()
                appendLine("Ограничение длины: отвечай максимально кратко, без воды.")
                if (stopInstruction.isNotEmpty()) {
                    appendLine()
                    appendLine(stopInstruction)
                }
            }
        }

        val bodyJson = buildJsonObject {
            put("model", JsonPrimitive("gpt-4o"))

            put("store", JsonPrimitive(true))
            put("metadata", buildJsonObject {
                put("app", JsonPrimitive("GromashAI"))
                put("feature", JsonPrimitive(if (useLimits) "day2_with_limits" else "day2_no_limits"))
                if (temperature != null) put("temperature", JsonPrimitive(temperature.toString()))
            })

            if (temperature != null) {
                put("temperature", JsonPrimitive(temperature))
            }

            if (useLimits) {
                put("reasoning", buildJsonObject {
                    put("effort", JsonPrimitive("minimal"))
                })
            }

            if (useLimits && maxOutputTokens != null) {
                put("max_output_tokens", JsonPrimitive(maxOutputTokens))
            }

            put("input", buildJsonArray {
                add(buildJsonObject {
                    put("role", JsonPrimitive("system"))
                    put("content", JsonPrimitive("Ты помощник. Следуй инструкциям пользователя."))
                })
                add(buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", JsonPrimitive(finalPrompt))
                })
            })
        }

        val resp: JsonObject = http.post("https://api.openai.com/v1/responses") {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(bodyJson)
        }.body()

        val status = resp["status"]?.jsonPrimitive?.contentOrNull
        val incompleteReason = resp["incomplete_details"]
            ?.takeIf { it !is JsonNull }
            ?.jsonObject
            ?.get("reason")
            ?.jsonPrimitive
            ?.contentOrNull

        val text = extractFirstOutputText(resp)

        if (status == "incomplete") {
            return Day2ApiResult(
                text = text.orEmpty(),
                isIncomplete = true,
                incompleteReason = incompleteReason ?: "unknown"
            )
        }

        if (text == null) {
            error("Пустой ответ: нет output.message.content.text. Ответ: ${resp.toString().take(700)}")
        }

        return Day2ApiResult(
            text = text,
            isIncomplete = false,
            incompleteReason = null
        )
    }

    data class Day2ApiResult(
        val text: String,
        val isIncomplete: Boolean,
        val incompleteReason: String?
    )

    private fun extractFirstOutputText(resp: JsonObject): String? {
        val output = resp["output"]?.jsonArray ?: return null

        for (item in output) {
            val obj = item.jsonObject
            if (obj["type"]?.jsonPrimitive?.contentOrNull != "message") continue
            val contentArr = obj["content"]?.jsonArray ?: continue
            for (c in contentArr) {
                val cObj = c.jsonObject
                val type = cObj["type"]?.jsonPrimitive?.contentOrNull
                if (type == "output_text" || type == "text") {
                    return cObj["text"]?.jsonPrimitive?.contentOrNull
                }
            }
        }

        for (item in output) {
            val obj = item.jsonObject
            if (obj["type"]?.jsonPrimitive?.contentOrNull != "reasoning") continue
            val summary = obj["summary"]?.jsonArray ?: continue
            val first = summary.firstOrNull()?.jsonObject ?: continue
            val type = first["type"]?.jsonPrimitive?.contentOrNull
            if (type == "summary_text") {
                return first["text"]?.jsonPrimitive?.contentOrNull
            }
        }

        return null
    }
}
