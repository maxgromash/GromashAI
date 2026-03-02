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

enum class ContextStrategy {
    SLIDING_WINDOW,
    STICKY_FACTS,
    BRANCHING
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
 * Агент — это отдельная сущность, инкапсулирующая логику общения с LLM.
 * Поддерживает 3 стратегии контекста:
 * 1. Sliding Window (последние N сообщений)
 * 2. Sticky Facts (факты + последние N сообщений)
 * 3. Branching (ветвление диалогов)
 */
class OpenAiAgent(
    private val openAiApi: OpenAiApi,
    private val hfApi: HfApi,
    private val storage: Settings,
    initialSystemPrompt: String = "Ты полезный и лаконичный помощник."
) {
    private val _systemPrompt = MutableStateFlow(initialSystemPrompt)
    val systemPrompt: StateFlow<String> = _systemPrompt.asStateFlow()

    // Вся история сообщений текущей ветки
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    // --- Strategy ---
    private val _strategy = MutableStateFlow(ContextStrategy.SLIDING_WINDOW)
    val strategy: StateFlow<ContextStrategy> = _strategy.asStateFlow()

    // --- Sticky Facts ---
    private val _facts = MutableStateFlow("")
    val facts: StateFlow<String> = _facts.asStateFlow()

    // --- Branching ---
    // Хранит историю для каждой ветки: Map<BranchID, List<ChatMessage>>
    private val _branches = MutableStateFlow<Map<String, List<ChatMessage>>>(mapOf("Main" to emptyList()))
    val branches: StateFlow<Map<String, List<ChatMessage>>> = _branches.asStateFlow()
    
    private val _currentBranchId = MutableStateFlow("Main")
    val currentBranchId: StateFlow<String> = _currentBranchId.asStateFlow()

    // --- Common ---
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _currentModel = MutableStateFlow(AgentModel.GPT_4O)
    val currentModel: StateFlow<AgentModel> = _currentModel.asStateFlow()

    private val _lastUsage = MutableStateFlow<TokenUsage?>(null)
    val lastUsage: StateFlow<TokenUsage?> = _lastUsage.asStateFlow()

    private val _totalUsage = MutableStateFlow(TokenUsage(0, 0, 0))
    val totalUsage: StateFlow<TokenUsage> = _totalUsage.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }
    private val HISTORY_KEY = "chat_history_agent_v3"
    
    // Config
    private val SLIDING_WINDOW_SIZE = 4
    private val FACTS_WINDOW_SIZE = 15

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
                _facts.value = data.facts
                _strategy.value = data.strategy
                _totalUsage.value = data.totalUsage
            } catch (e: Exception) {
                _messages.value = emptyList()
            }
        }
    }

    private fun saveHistory() {
        // Перед сохранением обновляем текущую ветку в мапе
        val currentMap = _branches.value.toMutableMap()
        currentMap[_currentBranchId.value] = _messages.value
        _branches.value = currentMap

        try {
            val data = AgentPersistedData(
                branches = _branches.value,
                currentBranchId = _currentBranchId.value,
                facts = _facts.value,
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
    
    // --- Branching Logic ---
    fun createBranch() {
        val newId = "Branch ${_branches.value.size + 1}"
        val currentHistory = _messages.value
        
        val newMap = _branches.value.toMutableMap()
        newMap[newId] = currentHistory 
        _branches.value = newMap
        
        switchBranch(newId)
    }
    
    fun switchBranch(branchId: String) {
        if (!_branches.value.containsKey(branchId)) return
        
        val oldId = _currentBranchId.value
        val map = _branches.value.toMutableMap()
        map[oldId] = _messages.value
        _branches.value = map
        
        _currentBranchId.value = branchId
        _messages.value = map[branchId] ?: emptyList()
        saveHistory()
    }

    suspend fun sendQuery(text: String) {
        if (text.isBlank()) return
        
        val userMsg = ChatMessage(role = "user", content = text)
        _messages.value = _messages.value + userMsg
        
        val map = _branches.value.toMutableMap()
        map[_currentBranchId.value] = _messages.value
        _branches.value = map
        
        saveHistory()
        
        _isLoading.value = true
        _lastUsage.value = null 
        
        if (_strategy.value == ContextStrategy.STICKY_FACTS) {
            updateFacts(text)
        }
        
        try {
            val model = _currentModel.value
            val contextMessages = prepareContextMessages()

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
            
            val mapAfter = _branches.value.toMutableMap()
            mapAfter[_currentBranchId.value] = _messages.value
            _branches.value = mapAfter
            
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
    
    private fun prepareContextMessages(): List<ChatMessage> {
        val allMessages = _messages.value
        val sysPrompt = _systemPrompt.value
        
        return when (_strategy.value) {
            ContextStrategy.SLIDING_WINDOW -> {
                val window = allMessages.takeLast(SLIDING_WINDOW_SIZE)
                listOf(ChatMessage("system", sysPrompt)) + window
            }
            ContextStrategy.STICKY_FACTS -> {
                val factsText = _facts.value.ifBlank { "Нет известных фактов." }
                val augmentedSystem = """
                    $sysPrompt
                    
                    [ВАЖНЫЕ ФАКТЫ И КОНТЕКСТ]:
                    $factsText
                """.trimIndent()
                
                val window = allMessages.takeLast(FACTS_WINDOW_SIZE)
                listOf(ChatMessage("system", augmentedSystem)) + window
            }
            ContextStrategy.BRANCHING -> {
                listOf(ChatMessage("system", sysPrompt)) + allMessages
            }
        }
    }
    
    private suspend fun updateFacts(userText: String) {
        val currentFacts = _facts.value
        val prompt = """
            Проанализируй сообщение пользователя и обнови список фактов.
            Факты - это важные данные: цели, ограничения, предпочтения, решения.
            
            Текущие факты:
            ${if (currentFacts.isBlank()) "(Нет)" else currentFacts}
            
            Сообщение:
            $userText
            
            Верни обновленный список фактов на РУССКОМ языке. Будь краток.
        """.trimIndent()
        
        try {
            val response = openAiApi.chat(listOf(ChatMessage("user", prompt)), "gpt-4o")
            _facts.value = response.content
            saveHistory()
        } catch (e: Exception) {
            // ignore
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
        _facts.value = ""
        _branches.value = mapOf("Main" to emptyList())
        _currentBranchId.value = "Main"
        _lastUsage.value = null
        _totalUsage.value = TokenUsage(0, 0, 0)
        saveHistory()
    }
}

@Serializable
data class AgentPersistedData(
    val branches: Map<String, List<ChatMessage>>,
    val currentBranchId: String,
    val facts: String,
    val strategy: ContextStrategy,
    val totalUsage: TokenUsage
)
