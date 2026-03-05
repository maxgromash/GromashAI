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
 * Этапы выполнения задачи.
 */
enum class TaskStage {
    IDLE,       // Ожидание задачи
    PLANNING,   // Планирование
    EXECUTION,  // Выполнение
    VALIDATION, // Проверка
    DONE        // Завершено
}

/**
 * Состояние текущей задачи.
 */
@Serializable
data class TaskState(
    val stage: TaskStage = TaskStage.IDLE,
    val step: String = "Нет активного шага",
    val expectedAction: String = "Ожидание ввода пользователя"
)

/**
 * Профиль пользователя для персонализации.
 */
@Serializable
data class UserProfile(
    val style: String = "Дружелюбный",
    val format: String = "Лаконичный текст",
    val constraints: String = "Нет"
)

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
 * Агент с многослойной моделью памяти, персонализацией и машиной состояний задачи.
 */
class OpenAiAgent(
    private val openAiApi: OpenAiApi,
    private val hfApi: HfApi,
    private val storage: Settings,
    initialSystemPrompt: String = "Ты полезный и лаконичный помощник."
) {
    private val _systemPrompt = MutableStateFlow(initialSystemPrompt)
    val systemPrompt: StateFlow<String> = _systemPrompt.asStateFlow()

    // --- Состояние задачи (TSM) ---
    private val _taskState = MutableStateFlow(TaskState())
    val taskState: StateFlow<TaskState> = _taskState.asStateFlow()

    // --- Персонализация ---
    private val _userProfile = MutableStateFlow(UserProfile())
    val userProfile: StateFlow<UserProfile> = _userProfile.asStateFlow()

    // --- Краткосрочная память ---
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    // --- Рабочая память ---
    private val _workingMemory = MutableStateFlow("")
    val workingMemory: StateFlow<String> = _workingMemory.asStateFlow()

    // --- Долговременная память ---
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
    private val HISTORY_KEY = "chat_history_agent_v6"
    
    private val SHORT_TERM_WINDOW = 4

    init {
        loadHistory()
    }
    
    fun setStrategy(newStrategy: ContextStrategy) {
        _strategy.value = newStrategy
        saveHistory()
    }

    fun updateUserProfile(profile: UserProfile) {
        _userProfile.value = profile
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
                _userProfile.value = data.userProfile ?: UserProfile()
                _taskState.value = data.taskState ?: TaskState()
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
                totalUsage = _totalUsage.value,
                userProfile = _userProfile.value,
                taskState = _taskState.value
            )
            storage.putString(HISTORY_KEY, json.encodeToString(data))
        } catch (e: Exception) {
            // ignore
        }
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
        
        // В режиме MULTI_LAYER_MEMORY обновляем всё: память и состояние задачи
        if (_strategy.value == ContextStrategy.MULTI_LAYER_MEMORY) {
            updateContext(text)
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
        val profile = _userProfile.value
        val task = _taskState.value
        
        // Добавляем текущее состояние задачи в системный промпт
        val statePrompt = """
            [ТЕКУЩЕЕ СОСТОЯНИЕ ЗАДАЧИ]:
            - Этап: ${task.stage}
            - Текущий шаг: ${task.step}
            - Ожидаемое действие: ${task.expectedAction}
        """.trimIndent()

        val personalizedPrompt = """
            $sysPrompt
            
            [ПРОФИЛЬ ПОЛЬЗОВАТЕЛЯ]:
            - Стиль: ${profile.style}, Формат: ${profile.format}, Ограничения: ${profile.constraints}
            
            $statePrompt
        """.trimIndent()

        return when (_strategy.value) {
            ContextStrategy.SLIDING_WINDOW -> {
                listOf(ChatMessage("system", personalizedPrompt)) + _messages.value.takeLast(SHORT_TERM_WINDOW)
            }
            ContextStrategy.STICKY_FACTS, ContextStrategy.MULTI_LAYER_MEMORY -> {
                val working = _workingMemory.value.ifBlank { "Пусто." }
                val longTerm = _longTermMemory.value.ifBlank { "Пусто." }
                
                val augmentedSystem = """
                    $personalizedPrompt
                    
                    [РАБОЧАЯ ПАМЯТЬ]: $working
                    [ДОЛГОВРЕМЕННАЯ ПАМЯТЬ]: $longTerm
                """.trimIndent()
                
                listOf(ChatMessage("system", augmentedSystem)) + _messages.value.takeLast(SHORT_TERM_WINDOW)
            }
            ContextStrategy.BRANCHING -> {
                listOf(ChatMessage("system", personalizedPrompt)) + _messages.value
            }
        }
    }
    
    /**
     * Комплексное обновление контекста: Память + Task State Machine.
     */
    private suspend fun updateContext(userText: String) {
        val prompt = """
            Обнови контекст и состояние задачи. 
            
            ТЕКУЩИЕ ДАННЫЕ:
            Memory Working: ${_workingMemory.value}
            Memory Long-term: ${_longTermMemory.value}
            Task State: [Stage: ${_taskState.value.stage}, Step: ${_taskState.value.step}, Action: ${_taskState.value.expectedAction}]
            
            Message: "$userText"
            
            Инструкция для Task Stage:
            - PLANNING: Если обсуждаете план или ТЗ.
            - EXECUTION: Если выполняете конкретные шаги.
            - VALIDATION: Если проверяете результат.
            - DONE: Если задача завершена.
            - IDLE: Если задачи нет.
            
            Верни JSON:
            {
              "working": "обновленная рабочая память",
              "longTerm": "обновленная долговременная память",
              "task": {
                "stage": "IDLE|PLANNING|EXECUTION|VALIDATION|DONE",
                "step": "название текущего шага",
                "expectedAction": "что должен сделать пользователь или ассистент"
              }
            }
        """.trimIndent()
        
        try {
            val response = openAiApi.chat(messages = listOf(ChatMessage("user", prompt)), model = "gpt-4o", jsonMode = true)
            val root = json.parseToJsonElement(response.content) as? JsonObject
            if (root != null) {
                _workingMemory.value = elementToString(root["working"]) ?: _workingMemory.value
                _longTermMemory.value = elementToString(root["longTerm"]) ?: _longTermMemory.value
                
                val taskObj = root["task"] as? JsonObject
                if (taskObj != null) {
                    val stageStr = (taskObj["stage"] as? JsonPrimitive)?.content ?: "IDLE"
                    val stage = try { TaskStage.valueOf(stageStr) } catch(e: Exception) { TaskStage.IDLE }
                    val step = (taskObj["step"] as? JsonPrimitive)?.content ?: "Нет шага"
                    val action = (taskObj["expectedAction"] as? JsonPrimitive)?.content ?: "Ожидание"
                    _taskState.value = TaskState(stage, step, action)
                }
                saveHistory()
            }
        } catch (e: Exception) { }
    }
    
    private fun elementToString(el: JsonElement?): String? {
        return when (el) {
            is JsonPrimitive -> el.contentOrNull ?: el.toString()
            is JsonObject -> json.encodeToString(el)
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
        _taskState.value = TaskState()
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
    val totalUsage: TokenUsage,
    val userProfile: UserProfile? = null,
    val taskState: TaskState? = null
)
