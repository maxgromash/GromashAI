package com.example.gromashai.openai

import com.example.gromashai.storage.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Сообщение чата.
 */
@Serializable
data class ChatMessage(
    val role: String,
    val content: String
)

/**
 * Агент — это отдельная сущность, инкапсулирующая логику общения с LLM.
 * Теперь он поддерживает системный промпт и контекст всей беседы.
 * Добавлено сохранение истории в Settings.
 */
class OpenAiAgent(
    private val api: OpenAiApi,
    private val storage: Settings,
    initialSystemPrompt: String = "Ты полезный и лаконичный помощник."
) {
    private val _systemPrompt = MutableStateFlow(initialSystemPrompt)
    val systemPrompt: StateFlow<String> = _systemPrompt.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }
    private val HISTORY_KEY = "chat_history_agent"

    init {
        loadHistory()
    }

    private fun loadHistory() {
        val saved = storage.getString(HISTORY_KEY)
        if (!saved.isNullOrBlank()) {
            try {
                _messages.value = json.decodeFromString<List<ChatMessage>>(saved)
            } catch (e: Exception) {
                // Если данные битые, просто начинаем с чистого листа
                _messages.value = emptyList()
            }
        }
    }

    private fun saveHistory() {
        try {
            val serialized = json.encodeToString(_messages.value)
            storage.putString(HISTORY_KEY, serialized)
        } catch (e: Exception) {
            // Ошибка сохранения не должна ломать UI
        }
    }

    fun updateSystemPrompt(newPrompt: String) {
        _systemPrompt.value = newPrompt
    }

    suspend fun sendQuery(text: String) {
        if (text.isBlank()) return
        
        val userMsg = ChatMessage(role = "user", content = text)
        _messages.value = _messages.value + userMsg
        saveHistory()
        
        _isLoading.value = true
        try {
            // Формируем полный список сообщений для API: системный промпт + история
            val fullHistory = listOf(
                ChatMessage(role = "system", content = _systemPrompt.value)
            ) + _messages.value

            val responseText = api.chat(fullHistory)
            
            val assistantMsg = ChatMessage(role = "assistant", content = responseText)
            _messages.value = _messages.value + assistantMsg
            saveHistory()
        } catch (e: Exception) {
            val errorMsg = ChatMessage(role = "assistant", content = "Ошибка: ${e.message}")
            _messages.value = _messages.value + errorMsg
        } finally {
            _isLoading.value = false
        }
    }

    fun clearChat() {
        _messages.value = emptyList()
        saveHistory()
    }
}
