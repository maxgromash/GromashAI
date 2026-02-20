package com.example.gromashai.screens

import com.example.gromashai.openai.OpenAiApi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class Day2State(
    val isLoading: Boolean = false,

    val prompt: String = "составь диалог между двумя людьми А и Б",

    // используется ТОЛЬКО в "С ограничениями"
    val formatHint: String = "Ответ в виде диалога. Каждая реплика с новой строки в формате: 'А: ...' / 'Б: ...'.",
    val maxOutputTokens: String = "120",
    val stopSequence: String = "END",

    // Day4 temperature input
    val temperature: String = "0.7",

    val resultNoLimits: String = "",
    val resultWithLimits: String = "",

    // Day4 results
    val resultT0: String = "",
    val resultT07: String = "",
    val resultT12: String = "",

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
    fun updateMaxTokens(v: String) = mutate { copy(maxOutputTokens = v.filter { it.isDigit() }) }
    fun updateStop(v: String) = mutate { copy(stopSequence = v) }
    fun updateTemperature(v: String) = mutate { copy(temperature = v.replace(',', '.')) }

    fun runNoLimits() {
        scope.launch {
            mutate { copy(isLoading = true, error = null, resultNoLimits = "") }
            runCatching {
                val r = api.day2Generate(
                    userPrompt = _state.value.prompt,
                    formatHint = _state.value.formatHint,
                    maxOutputTokens = null,
                    stopSequence = null,
                    useLimits = false,
                    temperature = null
                )
                r.text
            }.onSuccess { text ->
                mutate { copy(isLoading = false, resultNoLimits = text) }
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
                val r = api.day2Generate(
                    userPrompt = _state.value.prompt,
                    formatHint = _state.value.formatHint,
                    maxOutputTokens = maxTokens,
                    stopSequence = stop,
                    useLimits = true,
                    temperature = null
                )

                // если incomplete — покажем как есть + причину
                buildString {
                    if (r.isIncomplete) {
                        appendLine("⚠️ Ответ неполный (reason=${r.incompleteReason}).")
                        appendLine()
                    }
                    append(r.text)
                }
            }.onSuccess { text ->
                mutate { copy(isLoading = false, resultWithLimits = text) }
            }.onFailure { e ->
                mutate { copy(isLoading = false, error = e.message ?: "Unknown error") }
            }
        }
    }

    /**
     * Day4: гоняем одинаковый запрос при разных temperature.
     * Тут логика простая: используем "без ограничений" (чистый prompt), чтобы сравнение было честным.
     */
    fun runTemperatureComparison() {
        scope.launch {
            mutate {
                copy(
                    isLoading = true,
                    error = null,
                    resultT0 = "",
                    resultT07 = "",
                    resultT12 = ""
                )
            }

            runCatching {
                val prompt = _state.value.prompt

                suspend fun call(t: Double): String {
                    val r = api.day2Generate(
                        userPrompt = prompt,
                        formatHint = _state.value.formatHint,
                        maxOutputTokens = null,
                        stopSequence = null,
                        useLimits = false,
                        temperature = t
                    )
                    return r.text
                }

                val a0 = call(0.0)
                val a07 = call(0.7)
                val a12 = call(1.2)

                Triple(a0, a07, a12)
            }.onSuccess { (a0, a07, a12) ->
                mutate {
                    copy(
                        isLoading = false,
                        resultT0 = a0,
                        resultT07 = a07,
                        resultT12 = a12
                    )
                }
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