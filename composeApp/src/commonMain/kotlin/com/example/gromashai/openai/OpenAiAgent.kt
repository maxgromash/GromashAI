package com.example.gromashai.openai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Сообщение чата.
 */
data class ChatMessage(
    val role: String,
    val content: String
)

/**
 * Агент — это отдельная сущность, инкапсулирующая логику общения с LLM.
 * Теперь он поддерживает системный промпт и контекст всей беседы.
 */
class OpenAiAgent(
    private val api: OpenAiApi,
    initialSystemPrompt: String = "Ты полезный и лаконичный помощник."
) {
    private val _systemPrompt = MutableStateFlow(initialSystemPrompt)
    val systemPrompt: StateFlow<String> = _systemPrompt.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun updateSystemPrompt(newPrompt: String) {
        _systemPrompt.value = newPrompt
    }

    suspend fun sendQuery(text: String) {
        if (text.isBlank()) return
        
        val userMsg = ChatMessage(role = "user", content = text)
        _messages.value = _messages.value + userMsg
        
        _isLoading.value = true
        try {
            // Формируем полный список сообщений для API: системный промпт + история
            val fullHistory = listOf(
                ChatMessage(role = "system", content = _systemPrompt.value)
            ) + _messages.value

            val responseText = api.chat(fullHistory)
            
            val assistantMsg = ChatMessage(role = "assistant", content = responseText)
            _messages.value = _messages.value + assistantMsg
        } catch (e: Exception) {
            val errorMsg = ChatMessage(role = "assistant", content = "Ошибка: ${e.message}")
            _messages.value = _messages.value + errorMsg
        } finally {
            _isLoading.value = false
        }
    }

    fun clearChat() {
        _messages.value = emptyList()
    }
}
