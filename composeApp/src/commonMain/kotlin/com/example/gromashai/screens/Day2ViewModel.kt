package com.example.gromashai.screens

import com.example.gromashai.openai.OpenAiApi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Day2State(
    val isLoading: Boolean = false,
    val prompt: String = "составь диалог между двумя людьми А и Б",
    val formatHint: String = "Ответ в виде диалога. Каждая реплика с новой строки в формате: 'А: ...' / 'Б: ...'.",
    val maxOutputTokens: String = "120",
    val stopSequence: String = "END",
    val resultNoLimits: String = "",
    val resultWithLimits: String = "",
    val error: String? = null
)

class Day2ViewModel(
    private val api: OpenAiApi
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(Day2State())
    val state: StateFlow<Day2State> = _state.asStateFlow()

    fun updatePrompt(v: String) = mutate { copy(prompt = v) }
    fun updateFormat(v: String) = mutate { copy(formatHint = v) }
    fun updateMaxTokens(v: String) = mutate { copy(maxOutputTokens = v.filter(Char::isDigit)) }
    fun updateStop(v: String) = mutate { copy(stopSequence = v) }

    fun runNoLimits() {
        scope.launch {
            mutate { copy(isLoading = true, error = null, resultNoLimits = "") }

            runCatching {
                // ✅ useLimits=false => API отправит только userPrompt (formatHint игнорируется)
                api.day2Generate(
                    userPrompt = _state.value.prompt,
                    formatHint = _state.value.formatHint,
                    maxOutputTokens = null,
                    stopSequence = null,
                    useLimits = false
                )
            }.onSuccess { res ->
                val suffix = if (res.isIncomplete) "\n\n⚠️ incomplete: ${res.incompleteReason}" else ""
                mutate { copy(isLoading = false, resultNoLimits = res.text + suffix) }
            }.onFailure { e ->
                mutate { copy(isLoading = false, error = e.message ?: "Unknown error") }
            }
        }
    }

    fun runWithLimits() {
        scope.launch {
            mutate { copy(isLoading = true, error = null, resultWithLimits = "") }

            val maxTokens = _state.value.maxOutputTokens.toIntOrNull()?.takeIf { it > 0 }
            val stop = _state.value.stopSequence.trim().takeIf { it.isNotBlank() }

            runCatching {
                api.day2Generate(
                    userPrompt = _state.value.prompt,
                    formatHint = _state.value.formatHint,
                    maxOutputTokens = maxTokens,
                    stopSequence = stop,
                    useLimits = true
                )
            }.onSuccess { res ->
                val suffix = if (res.isIncomplete) "\n\n⚠️ incomplete: ${res.incompleteReason}" else ""
                mutate { copy(isLoading = false, resultWithLimits = res.text + suffix) }
            }.onFailure { e ->
                mutate { copy(isLoading = false, error = e.message ?: "Unknown error") }
            }
        }
    }

    fun dispose() {
        scope.cancel()
    }

    private fun mutate(block: Day2State.() -> Day2State) {
        _state.value = _state.value.block()
    }
}
