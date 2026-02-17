package com.example.gromashai

import com.example.gromashai.openai.OpenAiApi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UiState(
    val isLoading: Boolean = false,
    val text: String = "Нажми кнопку — дам число и гороскоп 🙂",
    val error: String? = null
)

class ChatGptViewModel(
    private val api: OpenAiApi
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun generate() {
        scope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            runCatching {
                api.generateLuckyHoroscope()
            }.onSuccess { result ->
                _state.value = UiState(
                    isLoading = false,
                    text = "🎲 Счастливое число: ${result.luckyNumber}\n\n🔮 ${result.horoscope}",
                    error = null
                )
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = e.message ?: "Unknown error"
                )
            }
        }
    }

    fun dispose() {
        scope.cancel()
    }
}
