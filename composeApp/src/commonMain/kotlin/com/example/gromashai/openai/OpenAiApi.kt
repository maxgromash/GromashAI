package com.example.gromashai.openai

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*

class OpenAiApi(
    private val http: HttpClient,
    private val apiKeyProvider: PlatformApiKeyProvider, // лучше интерфейс
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun generateLuckyHoroscope(): LuckyHoroscope {
        val apiKey = apiKeyProvider.get()

        val prompt = """
            Сгенерируй:
            1) случайное "счастливое число" от 1 до 999
            2) короткий гороскоп/пожелание на день (1-2 предложения), дружелюбно, без мистики, без пугающих предсказаний.
            Верни СТРОГО валидный JSON по схеме.
        """.trimIndent()

        val bodyJson = buildJsonObject {
            put("model", "gpt-5-mini")

            // Чтобы найти запрос в логах:
            put("store", true)
            put("metadata", buildJsonObject {
                put("app", "GromashAI")
                put("feature", "lucky_horoscope")
            })

            put("input", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", "Ты дружелюбный ассистент. Отвечай строго JSON по схеме.")
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", prompt)
                })
            })

            // ✅ Structured Outputs для Responses API — через text.format
            put("text", buildJsonObject {
                put("format", buildJsonObject {
                    put("type", "json_schema")
                    put("name", "lucky_horoscope")
                    put("strict", true)
                    put("schema", buildJsonObject {
                        put("type", "object")
                        put("additionalProperties", false)
                        put("properties", buildJsonObject {
                            put("luckyNumber", buildJsonObject {
                                put("type", "integer")
                                put("minimum", 1)
                                put("maximum", 999)
                            })
                            put("horoscope", buildJsonObject {
                                put("type", "string")
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

        // ✅ Достаём текст из output[...].content[...].text
        val outputText = extractFirstOutputText(resp)
            ?: error("Пустой ответ: нет output.message.content.text. Ответ: ${resp.toString().take(500)}")

        // outputText будет строкой JSON по нашей схеме
        return json.decodeFromString(LuckyHoroscope.serializer(), outputText)
    }

    private fun extractFirstOutputText(resp: JsonObject): String? {
        val output = resp["output"]?.jsonArray ?: return null
        for (item in output) {
            val obj = item.jsonObject
            if (obj["type"]?.jsonPrimitive?.content != "message") continue
            val contentArr = obj["content"]?.jsonArray ?: continue
            for (c in contentArr) {
                val cObj = c.jsonObject
                val type = cObj["type"]?.jsonPrimitive?.content
                if (type == "output_text" || type == "text") {
                    return cObj["text"]?.jsonPrimitive?.content
                }
            }
        }
        return null
    }
}
