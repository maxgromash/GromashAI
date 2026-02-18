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

    // -------------------------
    // Day1
    // -------------------------
    suspend fun day1GenerateLuckyHoroscope(): String {
        val apiKey = apiKeyProvider.get()

        val prompt = """
            Сгенерируй:
            1) случайное "счастливое число" от 1 до 999
            2) короткий гороскоп/пожелание на день (1-2 предложения), дружелюбно, без мистики, без пугающих предсказаний.
            Верни СТРОГО валидный JSON по схеме.
        """.trimIndent()

        val bodyJson = buildJsonObject {
            put("model", JsonPrimitive("gpt-5-mini"))

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

        // если пришла ошибка от API — покажем её
        throwIfApiError(resp)

        val outputText = extractFirstOutputText(resp)
            ?: error("Пустой ответ: нет output.message.content.text. Ответ: ${resp.toString().take(700)}")

        return outputText
    }

    // -------------------------
    // Day2
    // -------------------------
    suspend fun day2Generate(
        userPrompt: String,
        formatHint: String,
        maxOutputTokens: Int?,
        stopSequence: String?,
        useLimits: Boolean,
    ): Day2ApiResult {
        val apiKey = apiKeyProvider.get()

        val stop = stopSequence?.trim()?.takeIf { it.isNotEmpty() }

        val stopInstruction = if (useLimits && stop != null) {
            """
            УСЛОВИЕ ЗАВЕРШЕНИЯ:
            Заверши ответ строкой: $stop
            После неё не пиши ничего.
            """.trimIndent()
        } else ""

        // ✅ no-limits: только запрос
        val finalPrompt = if (!useLimits) {
            userPrompt.trim()
        } else {
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
            put("model", JsonPrimitive("gpt-5-mini"))

            put("store", JsonPrimitive(true))
            put("metadata", buildJsonObject {
                put("app", JsonPrimitive("GromashAI"))
                put("feature", JsonPrimitive(if (useLimits) "day2_with_limits" else "day2_no_limits"))
            })

            // ✅ чтобы при малых max_output_tokens шанс "incomplete" был меньше
            if (useLimits) {
                put("reasoning", buildJsonObject {
                    put("effort", JsonPrimitive("minimal")) // НЕ "none"
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

        // ✅ если пришла ошибка от API — покажем её (и не полезем в output / jsonObject)
        throwIfApiError(resp)

        val status = resp["status"]?.jsonPrimitive?.contentOrNull

        // ✅ FIX: incomplete_details может быть JsonNull → safe-cast
        val incompleteReason = (resp["incomplete_details"] as? JsonObject)
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

    // -------------------------
    // Helpers
    // -------------------------

    private fun throwIfApiError(resp: JsonObject) {
        val errObj = resp["error"] as? JsonObject ?: return
        val msg = errObj["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown API error"
        val code = errObj["code"]?.jsonPrimitive?.contentOrNull
        val param = errObj["param"]?.jsonPrimitive?.contentOrNull
        error(buildString {
            append("OpenAI API error: $msg")
            if (code != null) append(" (code=$code)")
            if (param != null) append(" (param=$param)")
        })
    }

    /**
     * Достаём текст из output[...]{type:"message"}.content[...]{type:"output_text"|"text"}.text
     * НИЧЕГО не парсим через .jsonObject без safe-cast.
     */
    private fun extractFirstOutputText(resp: JsonObject): String? {
        val output = resp["output"] as? JsonArray ?: return null

        for (item in output) {
            val obj = item as? JsonObject ?: continue
            if (obj["type"]?.jsonPrimitive?.contentOrNull != "message") continue

            val contentArr = obj["content"] as? JsonArray ?: continue
            for (c in contentArr) {
                val cObj = c as? JsonObject ?: continue
                val type = cObj["type"]?.jsonPrimitive?.contentOrNull
                if (type == "output_text" || type == "text") {
                    return cObj["text"]?.jsonPrimitive?.contentOrNull
                }
            }
        }

        return null
    }
}
