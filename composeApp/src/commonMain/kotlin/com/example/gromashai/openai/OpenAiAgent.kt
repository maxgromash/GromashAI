package com.example.gromashai.openai

import com.example.gromashai.hf.HfApi
import com.example.gromashai.storage.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
 * Добавлено сжатие контекста.
 */
class OpenAiAgent(
    private val openAiApi: OpenAiApi,
    private val hfApi: HfApi,
    private val storage: Settings,
    initialSystemPrompt: String = "Ты полезный и лаконичный помощник."
) {
    private val _systemPrompt = MutableStateFlow(initialSystemPrompt)
    val systemPrompt: StateFlow<String> = _systemPrompt.asStateFlow()

    // Вся история сообщений (для UI)
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    // Текущая саммари (сжатый контекст)
    private val _summary = MutableStateFlow("")
    val summary: StateFlow<String> = _summary.asStateFlow()

    // Индекс в _messages, до которого сообщения уже включены в саммари
    private val _summarizedToIndex = MutableStateFlow(0)

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    // Флаг, идет ли сейчас процесс сжатия
    private val _isCompressing = MutableStateFlow(false)
    val isCompressing: StateFlow<Boolean> = _isCompressing.asStateFlow()

    private val _currentModel = MutableStateFlow(AgentModel.GPT_4O)
    val currentModel: StateFlow<AgentModel> = _currentModel.asStateFlow()

    private val _lastUsage = MutableStateFlow<TokenUsage?>(null)
    val lastUsage: StateFlow<TokenUsage?> = _lastUsage.asStateFlow()
    
    // Aggregate usage for the session
    private val _totalUsage = MutableStateFlow(TokenUsage(0, 0, 0))
    val totalUsage: StateFlow<TokenUsage> = _totalUsage.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }
    private val HISTORY_KEY = "chat_history_agent"
    private val SUMMARY_KEY = "chat_summary_agent"
    private val SUMMARY_IDX_KEY = "chat_summary_idx_agent"

    // Константы для сжатия
    private val KEEP_LAST_MESSAGES = 6 // Сколько сообщений храним "как есть" (не сжимаем)
    private val COMPRESS_THRESHOLD = 5 // Начинаем сжимать, если незасжатых больше чем это число

    init {
        loadHistory()
    }

    private fun loadHistory() {
        val savedMsgs = storage.getString(HISTORY_KEY)
        val savedSummary = storage.getString(SUMMARY_KEY)
        val savedIdx = storage.getString(SUMMARY_IDX_KEY)

        if (!savedMsgs.isNullOrBlank()) {
            try {
                _messages.value = json.decodeFromString<List<ChatMessage>>(savedMsgs)
            } catch (e: Exception) {
                _messages.value = emptyList()
            }
        }
        
        _summary.value = savedSummary ?: ""
        _summarizedToIndex.value = savedIdx?.toIntOrNull() ?: 0
        
        // Safety check: index shouldn't be out of bounds if history was cleared externally
        if (_summarizedToIndex.value > _messages.value.size) {
            _summarizedToIndex.value = 0
            _summary.value = ""
        }
    }

    private fun saveHistory() {
        try {
            val serializedMsgs = json.encodeToString(_messages.value)
            storage.putString(HISTORY_KEY, serializedMsgs)
            storage.putString(SUMMARY_KEY, _summary.value)
            storage.putString(SUMMARY_IDX_KEY, _summarizedToIndex.value.toString())
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
            
            // --- Формирование контекста ---
            // 1. Системный промпт
            val contextMessages = mutableListOf<ChatMessage>()
            
            // Если есть саммари, добавляем его в системный промпт или как первое сообщение
            val sysContent = if (_summary.value.isNotBlank()) {
                "${_systemPrompt.value}\n\n[CONTEXT SUMMARY]:\n${_summary.value}"
            } else {
                _systemPrompt.value
            }
            contextMessages.add(ChatMessage(role = "system", content = sysContent))

            // 2. Добавляем сообщения, которые еще не были сжаты (начиная с _summarizedToIndex)
            val currentIdx = _summarizedToIndex.value
            val unsummarizedPart = if (currentIdx < _messages.value.size) {
                _messages.value.subList(currentIdx, _messages.value.size)
            } else {
                emptyList()
            }
            
            contextMessages.addAll(unsummarizedPart)

            val response = when (model.provider) {
                ModelProvider.OPENAI -> openAiApi.chat(contextMessages, model.id)
                ModelProvider.HUGGING_FACE -> hfApi.chat(contextMessages, model.id)
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

            // --- Проверка на необходимость сжатия ---
            // Количество незасжатых сообщений
            val unsummarizedCount = _messages.value.size - _summarizedToIndex.value
            if (unsummarizedCount > COMPRESS_THRESHOLD) {
                // Запускаем сжатие
                 compressContext() 
            }

        } catch (e: Exception) {
            val errorMsg = ChatMessage(role = "assistant", content = "Ошибка: ${e.message}")
            _messages.value = _messages.value + errorMsg
        } finally {
            _isLoading.value = false
        }
    }
    
    private suspend fun compressContext() {
        if (_isCompressing.value) return
        _isCompressing.value = true
        
        try {
            val totalMessages = _messages.value.size
            val currentIdx = _summarizedToIndex.value
            
            // Мы хотим оставить "как есть" последние KEEP_LAST_MESSAGES
            // Значит, сжимаем всё от currentIdx до (totalMessages - KEEP_LAST_MESSAGES)
            val endIndexToCompress = totalMessages - KEEP_LAST_MESSAGES
            
            if (endIndexToCompress <= currentIdx) {
                return // Нечего сжимать
            }
            
            val chunkToCompress = _messages.value.subList(currentIdx, endIndexToCompress)
            if (chunkToCompress.isEmpty()) return

            val prevSummary = _summary.value
            val contentToCompress = chunkToCompress.joinToString("\n") { "${it.role}: ${it.content}" }
            
            val compressionPrompt = """
                Сделай ОЧЕНЬ краткую выжимку диалога. Сохраняй только самые важные факты и контекст.
                
                Текущая выжимка:
                ${if (prevSummary.isBlank()) "(Нет)" else prevSummary}
                
                Новые сообщения:
                $contentToCompress
                
                Обнови выжимку, включив новые данные. Отвечай ТОЛЬКО текстом новой выжимки на РУССКОМ языке. Максимально сжато.
            """.trimIndent()
            
            // Для сжатия используем GPT-4o (стабильнее следует инструкции)
            val msgs = listOf(ChatMessage("user", content = compressionPrompt))
            val response = openAiApi.chat(msgs, "gpt-4o")
            
            val newSummary = response.content
            
            // Обновляем состояние
            _summary.value = newSummary
            _summarizedToIndex.value = endIndexToCompress
            
            // Учитываем токены, потраченные на сжатие (опционально, но полезно для статистики)
            if (response.usage != null) {
                updateTotalUsage(response.usage)
            }
            
            saveHistory()
            
        } catch (e: Exception) {
            println("Compression failed: ${e.message}")
        } finally {
            _isCompressing.value = false
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
        _summary.value = ""
        _summarizedToIndex.value = 0
        _lastUsage.value = null
        _totalUsage.value = TokenUsage(0, 0, 0)
        saveHistory()
    }
}
