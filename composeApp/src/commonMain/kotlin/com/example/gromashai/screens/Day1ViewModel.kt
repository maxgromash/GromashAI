package com.example.gromashai.screens

import com.example.gromashai.openai.LuckyHoroscope
import com.example.gromashai.openai.OpenAiApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

data class Day1State(
    val isLoading: Boolean = false,
    val text: String = "Нажми кнопку — дам число и гороскоп 🙂",
    val error: String? = null
)

class Day1ViewModel(
    private val api: OpenAiApi
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(Day1State())
    val state: StateFlow<Day1State> = _state.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    fun generate() {
        scope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)

            runCatching {
                val raw = api.day1GenerateLuckyHoroscope() // <-- String (JSON)
                json.decodeFromString(LuckyHoroscope.serializer(), raw) // TODO
            }.onSuccess { result ->
                _state.value = Day1State(
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
