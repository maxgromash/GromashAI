package com.example.gromashai.openai

import com.example.gromashai.hf.HfApi
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
    val content: String,
    val usage: TokenUsage? = null
)

/**
 * Агент — это отдельная сущность, инкапсулирующая логику общения с LLM.
 * Теперь он поддерживает системный промпт и контекст всей беседы.
 * Добавлено сохранение истории в Settings.
 * Добавлена поддержка выбора модели и подсчет токенов.
 */
class OpenAiAgent(
    private val openAiApi: OpenAiApi,
    private val hfApi: HfApi,
    private val storage: Settings,
    initialSystemPrompt: String = "Ты полезный и лаконичный помощник."
) {
    private val _systemPrompt = MutableStateFlow(initialSystemPrompt)
    val systemPrompt: StateFlow<String> = _systemPrompt.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _currentModel = MutableStateFlow(AgentModel.GPT_4O)
    val currentModel: StateFlow<AgentModel> = _currentModel.asStateFlow()

    private val _lastUsage = MutableStateFlow<TokenUsage?>(null)
    val lastUsage: StateFlow<TokenUsage?> = _lastUsage.asStateFlow()
    
    // Aggregate usage for the session
    private val _totalUsage = MutableStateFlow(TokenUsage(0, 0, 0))
    val totalUsage: StateFlow<TokenUsage> = _totalUsage.asStateFlow()

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

    fun setModel(model: AgentModel) {
        _currentModel.value = model
    }

    suspend fun sendQuery(text: String) {
        if (text.isBlank()) return
        
        val userMsg = ChatMessage(role = "user", content = text)
        _messages.value = _messages.value + userMsg
        saveHistory()
        
        _isLoading.value = true
        _lastUsage.value = null // Reset last usage while loading
        
        try {
            val model = _currentModel.value
            
            // Формируем полный список сообщений для API: системный промпт + история
            val fullHistory = listOf(
                ChatMessage(role = "system", content = _systemPrompt.value)
            ) + _messages.value

            val response = when (model.provider) {
                ModelProvider.OPENAI -> openAiApi.chat(fullHistory, model.id)
                ModelProvider.HUGGING_FACE -> hfApi.chat(fullHistory, model.id)
            }
            
            val assistantMsg = ChatMessage(
                role = "assistant", 
                content = response.content,
                usage = response.usage
            )
            _messages.value = _messages.value + assistantMsg
            
            if (response.usage != null) {
                _lastUsage.value = response.usage
                updateTotalUsage(response.usage)
            }
            
            saveHistory()
        } catch (e: Exception) {
            val errorMsg = ChatMessage(role = "assistant", content = "Ошибка: ${e.message}")
            _messages.value = _messages.value + errorMsg
        } finally {
            _isLoading.value = false
        }
    }
    
    private fun updateTotalUsage(newUsage: TokenUsage) {
        val current = _totalUsage.value
        val newTotal = TokenUsage(
            promptTokens = current.promptTokens + newUsage.promptTokens,
            completionTokens = current.completionTokens + newUsage.completionTokens,
            totalTokens = current.totalTokens + newUsage.totalTokens
        )
        _totalUsage.value = newTotal
    }

    fun clearChat() {
        _messages.value = emptyList()
        _lastUsage.value = null
        _totalUsage.value = TokenUsage(0, 0, 0)
        saveHistory()
    }
}
