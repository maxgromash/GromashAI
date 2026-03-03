package com.example.gromashai.openai

import com.example.gromashai.hf.HfApi
import com.example.gromashai.storage.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

enum class ContextStrategy {
    SLIDING_WINDOW,
    STICKY_FACTS,
    BRANCHING,
    MULTI_LAYER_MEMORY
}

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
 * Агент с многослойной моделью памяти:
 * 1. Краткосрочная (Short-term): Текущий список сообщений (sliding window).
 * 2. Рабочая (Working): Факты и данные текущей задачи.
 * 3. Долговременная (Long-term): Профиль пользователя, устойчивые предпочтения.
 */
class OpenAiAgent(
    private val openAiApi: OpenAiApi,
    private val hfApi: HfApi,
    private val storage: Settings,
    initialSystemPrompt: String = "Ты полезный и лаконичный помощник."
) {
    private val _systemPrompt = MutableStateFlow(initialSystemPrompt)
    val systemPrompt: StateFlow<String> = _systemPrompt.asStateFlow()

    // --- 1. Краткосрочная память (Short-term) ---
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    // --- 2. Рабочая память (Working) ---
    private val _workingMemory = MutableStateFlow("")
    val workingMemory: StateFlow<String> = _workingMemory.asStateFlow()

    // --- 3. Долговременная память (Long-term) ---
    private val _longTermMemory = MutableStateFlow("")
    val longTermMemory: StateFlow<String> = _longTermMemory.asStateFlow()

    // --- Strategy & State ---
    private val _strategy = MutableStateFlow(ContextStrategy.MULTI_LAYER_MEMORY)
    val strategy: StateFlow<ContextStrategy> = _strategy.asStateFlow()

    private val _branches = MutableStateFlow<Map<String, List<ChatMessage>>>(mapOf("Main" to emptyList()))
    val branches: StateFlow<Map<String, List<ChatMessage>>> = _branches.asStateFlow()
    
    private val _currentBranchId = MutableStateFlow("Main")
    val currentBranchId: StateFlow<String> = _currentBranchId.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _currentModel = MutableStateFlow(AgentModel.GPT_4O)
    val currentModel: StateFlow<AgentModel> = _currentModel.asStateFlow()

    private val _lastUsage = MutableStateFlow<TokenUsage?>(null)
    val lastUsage: StateFlow<TokenUsage?> = _lastUsage.asStateFlow()

    private val _totalUsage = MutableStateFlow(TokenUsage(0, 0, 0))
    val totalUsage: StateFlow<TokenUsage> = _totalUsage.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }
    private val HISTORY_KEY = "chat_history_agent_v4"
    
    private val SHORT_TERM_WINDOW = 4

    init {
        loadHistory()
    }
    
    fun setStrategy(newStrategy: ContextStrategy) {
        _strategy.value = newStrategy
        saveHistory()
    }

    private fun loadHistory() {
        val saved = storage.getString(HISTORY_KEY)
        if (!saved.isNullOrBlank()) {
            try {
                val data = json.decodeFromString<AgentPersistedData>(saved)
                _branches.value = data.branches
                _currentBranchId.value = data.currentBranchId
                _messages.value = data.branches[data.currentBranchId] ?: emptyList()
                _workingMemory.value = data.workingMemory
                _longTermMemory.value = data.longTermMemory
                _strategy.value = data.strategy
                _totalUsage.value = data.totalUsage
            } catch (e: Exception) {
                _messages.value = emptyList()
            }
        }
    }

    private fun saveHistory() {
        val currentMap = _branches.value.toMutableMap()
        currentMap[_currentBranchId.value] = _messages.value
        _branches.value = currentMap

        try {
            val data = AgentPersistedData(
                branches = _branches.value,
                currentBranchId = _currentBranchId.value,
                workingMemory = _workingMemory.value,
                longTermMemory = _longTermMemory.value,
                strategy = _strategy.value,
                totalUsage = _totalUsage.value
            )
            storage.putString(HISTORY_KEY, json.encodeToString(data))
        } catch (e: Exception) {
            // ignore
        }
    }

    fun updateSystemPrompt(newPrompt: String) {
        _systemPrompt.value = newPrompt
    }

    fun setModel(model: AgentModel) {
        _currentModel.value = model
    }
    
    fun createBranch() {
        val newId = "Branch ${_branches.value.size + 1}"
        val newMap = _branches.value.toMutableMap()
        newMap[newId] = _messages.value
        _branches.value = newMap
        switchBranch(newId)
    }
    
    fun switchBranch(branchId: String) {
        if (!_branches.value.containsKey(branchId)) return
        val map = _branches.value.toMutableMap()
        map[_currentBranchId.value] = _messages.value
        _branches.value = map
        _currentBranchId.value = branchId
        _messages.value = map[branchId] ?: emptyList()
        saveHistory()
    }

    suspend fun sendQuery(text: String) {
        if (text.isBlank()) return
        
        val userMsg = ChatMessage(role = "user", content = text)
        _messages.value = _messages.value + userMsg
        saveHistory()
        
        _isLoading.value = true
        
        // В стратегии Multi-Layer или Sticky Facts обновляем слои памяти
        if (_strategy.value == ContextStrategy.MULTI_LAYER_MEMORY || _strategy.value == ContextStrategy.STICKY_FACTS) {
            updateMemoryLayers(text)
        }
        
        try {
            val model = _currentModel.value
            val contextMessages = prepareContextMessages()

            val response = when (model.provider) {
                ModelProvider.OPENAI -> openAiApi.chat(contextMessages, model.id)
                ModelProvider.HUGGING_FACE -> hfApi.chat(contextMessages, model.id)
            }
            
            val assistantMsg = ChatMessage(role = "assistant", content = response.content, usage = response.usage)
            _messages.value = _messages.value + assistantMsg
            
            if (response.usage != null) {
                _lastUsage.value = response.usage
                updateTotalUsage(response.usage)
            }
            saveHistory()
        } catch (e: Exception) {
             _messages.value = _messages.value + ChatMessage(role = "assistant", content = "Ошибка: ${e.message}")
        } finally {
            _isLoading.value = false
        }
    }
    
    private fun prepareContextMessages(): List<ChatMessage> {
        val sysPrompt = _systemPrompt.value
        return when (_strategy.value) {
            ContextStrategy.SLIDING_WINDOW -> {
                listOf(ChatMessage("system", sysPrompt)) + _messages.value.takeLast(SHORT_TERM_WINDOW)
            }
            ContextStrategy.STICKY_FACTS, ContextStrategy.MULTI_LAYER_MEMORY -> {
                val working = _workingMemory.value.ifBlank { "Пусто." }
                val longTerm = _longTermMemory.value.ifBlank { "Пусто." }
                
                val augmentedSystem = """
                    $sysPrompt
                    
                    [ТЕКУЩАЯ РАБОЧАЯ ПАМЯТЬ (Контекст задачи)]:
                    $working
                    
                    [ДОЛГОВРЕМЕННАЯ ПАМЯТЬ (Профиль пользователя)]:
                    $longTerm
                """.trimIndent()
                
                listOf(ChatMessage("system", augmentedSystem)) + _messages.value.takeLast(SHORT_TERM_WINDOW)
            }
            ContextStrategy.BRANCHING -> {
                listOf(ChatMessage("system", sysPrompt)) + _messages.value
            }
        }
    }
    
    private suspend fun updateMemoryLayers(userText: String) {
        val memoryMessages = listOf(
            ChatMessage("system", """
                Ты — экспертная система управления контекстом и профилирования.
                Твоя задача — обновлять два слоя памяти на основе сообщений.
                
                ИНСТРУКЦИЯ ПО КЛАССИФИКАЦИИ:
                1. longTerm (Долговременная): Имя пользователя, его профессия, постоянные интересы, место жительства, личные факты. ВСЁ, ЧТО ХАРАКТЕРИЗУЕТ ЛИЧНОСТЬ. (Например: "Марк", "Маркетолог").
                2. working (Рабочая): Детали текущего обсуждения, название текущего проекта, временные цели сессии, параметры текущей задачи. (Например: "Проект SwiftRide", "План рекламной кампании").
                
                ТРЕБОВАНИЯ К ФОРМАТУ:
                - Отвечай ТОЛЬКО чистым JSON с полями "working" и "longTerm".
                - ЗНАЧЕНИЯ ПОЛЕЙ ДОЛЖНЫ БЫТЬ СТРОКАМИ. Не используй вложенные объекты.
                - Пиши на РУССКОМ языке.
            """.trimIndent()),
            ChatMessage("user", """
                Обнови слои памяти на основе сообщения пользователя: "$userText"
                
                Текущее состояние:
                Working: ${_workingMemory.value}
                Long-term: ${_longTermMemory.value}
                
                Верни ОБНОВЛЕННЫЙ JSON целиком (старая инфо + новые факты).
            """.trimIndent())
        )
        
        try {
            val response = openAiApi.chat(
                messages = memoryMessages,
                model = "gpt-4o",
                jsonMode = true
            )
            
            val jsonText = response.content
                .replace("```json", "")
                .replace("```", "")
                .trim()
            
            // Парсим гибко через JsonElement, чтобы не падать на объектах
            val root = json.parseToJsonElement(jsonText) as? JsonObject
            if (root != null) {
                val workingElement = root["working"]
                val longTermElement = root["longTerm"]
                
                _workingMemory.value = elementToString(workingElement) ?: _workingMemory.value
                _longTermMemory.value = elementToString(longTermElement) ?: _longTermMemory.value
                saveHistory()
            }
        } catch (e: Exception) {
            println("Extraction error: ${e.message}")
        }
    }
    
    private fun elementToString(el: JsonElement?): String? {
        return when (el) {
            is JsonPrimitive -> el.contentOrNull ?: el.toString()
            is JsonObject -> json.encodeToString(el) // Если модель всё же вернула объект, превратим его в текст
            else -> null
        }
    }

    private fun updateTotalUsage(newUsage: TokenUsage) {
        val current = _totalUsage.value
        _totalUsage.value = TokenUsage(
            promptTokens = current.promptTokens + newUsage.promptTokens,
            completionTokens = current.completionTokens + newUsage.completionTokens,
            totalTokens = current.totalTokens + newUsage.totalTokens
        )
    }

    fun clearChat() {
        _messages.value = emptyList()
        _workingMemory.value = ""
        _longTermMemory.value = ""
        _branches.value = mapOf("Main" to emptyList())
        _currentBranchId.value = "Main"
        _totalUsage.value = TokenUsage(0, 0, 0)
        saveHistory()
    }
}

@Serializable
data class AgentPersistedData(
    val branches: Map<String, List<ChatMessage>>,
    val currentBranchId: String,
    val workingMemory: String,
    val longTermMemory: String,
    val strategy: ContextStrategy,
    val totalUsage: TokenUsage
)
