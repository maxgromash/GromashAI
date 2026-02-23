package com.example.gromashai.hf

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HfRequest(
    val inputs: String,
    val parameters: HfParameters = HfParameters()
)

@Serializable
data class HfParameters(
    @SerialName("return_full_text") val returnFullText: Boolean = false,
    val details: Boolean = true,
    @SerialName("wait_for_model") val waitForModel: Boolean = true
)

@Serializable
data class HfResponse(
    @SerialName("generated_text") val generatedText: String,
    val details: HfDetails? = null
)

@Serializable
data class HfDetails(
    @SerialName("generated_tokens") val generatedTokens: Int? = null
)

// OpenAI-compatible Chat models for the new HF Router
@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val max_tokens: Int = 500,
    val stream: Boolean = false
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String
)

@Serializable
data class ChatResponse(
    val choices: List<ChatChoice>,
    val usage: ChatUsage? = null
)

@Serializable
data class ChatChoice(
    val message: ChatMessage
)

@Serializable
data class ChatUsage(
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null
)
