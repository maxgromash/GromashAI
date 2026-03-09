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
    PLANNING,   // Планирование (сбор требований, ТЗ)
    EXECUTION,  // Выполнение (написание кода, реализация)
    VALIDATION, // Проверка (тестирование, ревью)
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
 * Агент с многослойной моделью памяти, персонализацией, машиной состояний задачи, инвариантами и контролируемыми переходами.
 */
class OpenAiAgent(
    private val openAiApi: OpenAiApi,
    private val hfApi: HfApi,
    private val storage: Settings,
    initialSystemPrompt: String = "Ты полезный и лаконичный помощник."
) {
    private val _systemPrompt = MutableStateFlow(initialSystemPrompt)
    val systemPrompt: StateFlow<String> = _systemPrompt.asStateFlow()

    // --- Инварианты (Ограничения) ---
    private val _invariants = MutableStateFlow("")
    val invariants: StateFlow<String> = _invariants.asStateFlow()

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
    private val HISTORY_KEY = "chat_history_agent_v7"
    
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

    fun updateInvariants(newInvariants: String) {
        _invariants.value = newInvariants
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
                _invariants.value = data.invariants ?: ""
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
                taskState = _taskState.value,
                invariants = _invariants.value
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
        val invariantsText = _invariants.value
        
        // Инварианты — это жесткие ограничения
        val invariantsPrompt = if (invariantsText.isNotBlank()) {
            """
                [ЖЕСТКИЕ ИНВАРИАНТЫ И ОГРАНИЧЕНИЯ]:
                $invariantsText
                
                ВАЖНО: Ты НЕ ИМЕЕШЬ ПРАВА предлагать решения или совершать действия, нарушающие эти инварианты. Если пользователь просит нарушить их, вежливо откажи и объясни причину, ссылаясь на установленные ограничения.
            """.trimIndent()
        } else ""

        val statePrompt = """
            [ТЕКУЩЕЕ СОСТОЯНИЕ ЗАДАЧИ]:
            - Этап: ${task.stage}
            - Текущий шаг: ${task.step}
            - Ожидаемое действие: ${task.expectedAction}
            
            [ПРАВИЛА ПЕРЕХОДОВ]:
            - Ты не можешь перепрыгивать этапы.
            - Нельзя приступать к EXECUTION (реализации) без завершенного PLANNING (утвержденного плана/ТЗ).
            - Нельзя переходить к DONE без этапа VALIDATION (проверки результата).
            - Если пользователь просит "сразу сделать" без плана, вежливо объясни, что сначала нужно пройти этап планирования.
        """.trimIndent()

        val fullSystemPrompt = """
            $sysPrompt
            
            [ПРОФИЛЬ ПОЛЬЗОВАТЕЛЯ]:
            - Стиль: ${profile.style}, Формат: ${profile.format}, Ограничения: ${profile.constraints}
            
            $invariantsPrompt
            
            $statePrompt
        """.trimIndent()

        return when (_strategy.value) {
            ContextStrategy.SLIDING_WINDOW -> {
                listOf(ChatMessage("system", fullSystemPrompt)) + _messages.value.takeLast(SHORT_TERM_WINDOW)
            }
            ContextStrategy.STICKY_FACTS, ContextStrategy.MULTI_LAYER_MEMORY -> {
                val working = _workingMemory.value.ifBlank { "Пусто." }
                val longTerm = _longTermMemory.value.ifBlank { "Пусто." }
                
                val augmentedSystem = """
                    $fullSystemPrompt
                    
                    [РАБОЧАЯ ПАМЯТЬ]: $working
                    [ДОЛГОВРЕМЕННАЯ ПАМЯТЬ]: $longTerm
                """.trimIndent()
                
                listOf(ChatMessage("system", augmentedSystem)) + _messages.value.takeLast(SHORT_TERM_WINDOW)
            }
            ContextStrategy.BRANCHING -> {
                listOf(ChatMessage("system", fullSystemPrompt)) + _messages.value
            }
        }
    }
    
    /**
     * Комплексное обновление контекста: Память + Task State Machine с контролем переходов.
     */
    private suspend fun updateContext(userText: String) {
        val prompt = """
            Обнови контекст и состояние задачи. Соблюдай правила переходов!
            
            ТЕКУЩИЕ ДАННЫЕ:
            Memory Working: ${_workingMemory.value}
            Memory Long-term: ${_longTermMemory.value}
            Task State: [Stage: ${_taskState.value.stage}, Step: ${_taskState.value.step}]
            
            Message: "$userText"
            
            ДОПУСТИМЫЕ ПЕРЕХОДЫ (STAGES):
            1. IDLE -> PLANNING (всегда, если появилась задача)
            2. PLANNING -> EXECUTION (только если план готов и утвержден)
            3. EXECUTION -> VALIDATION (когда реализация завершена)
            4. VALIDATION -> DONE (если результат верен)
            5. VALIDATION -> EXECUTION (если нужны правки)
            6. DONE -> IDLE (если всё завершено)
            
            ЗАПРЕЩЕНО: Перескакивать этапы (например, из PLANNING сразу в VALIDATION).
            
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
                    val newStage = try { TaskStage.valueOf(stageStr) } catch(e: Exception) { _taskState.value.stage }
                    
                    // Валидация перехода (на стороне логики извлечения)
                    // Модель уже получила инструкции, но мы можем добавить доп. логику здесь если нужно.
                    // Пока доверяем модели, так как она видит правила переходов.
                    
                    val step = (taskObj["step"] as? JsonPrimitive)?.content ?: "Нет шага"
                    val action = (taskObj["expectedAction"] as? JsonPrimitive)?.content ?: "Ожидание"
                    _taskState.value = TaskState(newStage, step, action)
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
        _invariants.value = ""
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
    val taskState: TaskState? = null,
    val invariants: String? = null
)
