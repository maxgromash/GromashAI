package com.example.gromashai.openai

import kotlinx.serialization.Serializable

@Serializable
data class TokenUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int
)

data class AgentResponse(
    val content: String,
    val usage: TokenUsage?
)

enum class ModelProvider {
    OPENAI,
    HUGGING_FACE
}

enum class AgentModel(val id: String, val displayName: String, val provider: ModelProvider) {
    GPT_4O("gpt-4o", "GPT-4o", ModelProvider.OPENAI),
    LLAMA_3_2_1B("meta-llama/Llama-3.2-1B-Instruct", "Llama 3.2 1B", ModelProvider.HUGGING_FACE),
    QWEN_2_5_CODER("Qwen/Qwen2.5-Coder-32B-Instruct", "Qwen 2.5 Coder 32B", ModelProvider.HUGGING_FACE),
    SMOL_LM("HuggingFaceTB/SmolLM2-1.7B-Instruct", "SmolLM2 1.7B", ModelProvider.HUGGING_FACE)
}
