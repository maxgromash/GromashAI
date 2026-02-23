package com.example.gromashai.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.gromashai.currentTimeMillis
import com.example.gromashai.hf.HfApi
import kotlinx.coroutines.launch

class Day5ViewModel(private val api: HfApi) : ViewModel() {

    var prompt by mutableStateOf("")
    var selectedModel by mutableStateOf("meta-llama/Llama-3.2-3B-Instruct")
    var responseText by mutableStateOf("")
    var isLoading by mutableStateOf(false)
    var tokensCount by mutableStateOf<Int?>(null)
    var requestTimeMs by mutableStateOf<Long?>(null)

    val models = listOf(
        "meta-llama/Llama-3.3-70B-Instruct",
        "meta-llama/Meta-Llama-3-8B-Instruct",
        "meta-llama/Llama-3.2-1B-Instruct"
    )

    fun sendRequest(onComplete: (Long) -> Unit) {
        if (prompt.isBlank()) return

        viewModelScope.launch {
            isLoading = true
            val startTime = currentTimeMillis()
            try {
                val result = api.generateText(selectedModel, prompt)
                val endTime = currentTimeMillis()
                val duration = endTime - startTime
                
                responseText = result.generatedText
                tokensCount = result.details?.generatedTokens ?: (result.generatedText.length / 4)
                requestTimeMs = duration
                onComplete(duration)
            } catch (e: Exception) {
                responseText = "Error: ${e.message}"
                tokensCount = null
                requestTimeMs = null
            } finally {
                isLoading = false
            }
        }
    }
}
