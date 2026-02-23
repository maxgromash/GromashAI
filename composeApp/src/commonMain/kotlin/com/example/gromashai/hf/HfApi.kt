package com.example.gromashai.hf

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

class HfApi(
    private val client: HttpClient,
    private val apiToken: String
) {
    // Correct base URL for the new Hugging Face Router's Chat Completion API
    private val baseUrl = "https://router.huggingface.co/v1/chat/completions"

    private val json = Json {
        ignoreUnknownKeys = true
    }

    suspend fun generateText(modelId: String, prompt: String): HfResponse {
        println("HF API: Requesting model $modelId via Chat API at $baseUrl")

        val requestBody = ChatRequest(
            model = modelId,
            messages = listOf(ChatMessage(role = "user", content = prompt))
        )

        val response = try {
            client.post(baseUrl) {
                header(HttpHeaders.Authorization, "Bearer $apiToken")
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }
        } catch (e: Exception) {
            throw Exception("Network error: ${e.message}")
        }

        val responseText = response.bodyAsText()
        println("HF API: Status ${response.status}")

        if (!response.status.isSuccess()) {
            val errorMessage = try {
                val element = json.parseToJsonElement(responseText)
                if (element is JsonObject && element.containsKey("error")) {
                    element["error"]?.jsonPrimitive?.content ?: responseText
                } else {
                    responseText
                }
            } catch (e: Exception) {
                responseText
            }
            throw Exception("API Error (${response.status}): $errorMessage")
        }

        return try {
            val chatResponse = json.decodeFromString<ChatResponse>(responseText)
            val text = chatResponse.choices.firstOrNull()?.message?.content ?: ""
            val tokens = chatResponse.usage?.completionTokens
            
            HfResponse(
                generatedText = text,
                details = HfDetails(generatedTokens = tokens)
            )
        } catch (e: Exception) {
            println("HF API: Parse error: ${e.message}")
            throw Exception("Failed to parse response: $responseText")
        }
    }
}
