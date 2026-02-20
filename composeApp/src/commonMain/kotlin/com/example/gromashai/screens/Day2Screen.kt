package com.example.gromashai.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.gromashai.openai.OpenAiApi

@Composable
fun Day2Screen(api: OpenAiApi) {
    val vm = remember { Day2ViewModel(api) }
    val s by vm.state.collectAsState()

    DisposableEffect(Unit) {
        onDispose { vm.dispose() }
    }

    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedTextField(
            value = s.prompt,
            onValueChange = vm::updatePrompt,
            label = { Text("Введите запрос") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3
        )

        OutlinedTextField(
            value = s.formatHint,
            onValueChange = vm::updateFormat,
            label = { Text("Формат ответа (используется только в «С ограничениями»)") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = s.maxOutputTokens,
                onValueChange = vm::updateMaxTokens,
                label = { Text("Ограничение длины (tokens)") },
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = s.stopSequence,
                onValueChange = vm::updateStop,
                label = { Text("Условие завершения (строка)") },
                modifier = Modifier.weight(1f)
            )
        }

        Divider(Modifier.padding(vertical = 6.dp))

        // Day4 temperature UI (одиночное поле + кнопка "сравнить")
        Text("День 4: Temperature", style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(
            value = s.temperature,
            onValueChange = vm::updateTemperature,
            label = { Text("Температура (для ручного прогона, если нужно)") },
            supportingText = { Text("Для задания мы сравниваем фиксированные 0 / 0.7 / 1.2") },
            modifier = Modifier.fillMaxWidth()
        )

        if (s.error != null) {
            Text("Ошибка: ${s.error}", color = MaterialTheme.colorScheme.error)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = vm::runNoLimits,
                enabled = !s.isLoading
            ) { Text("Без ограничений") }

            Button(
                onClick = vm::runWithLimits,
                enabled = !s.isLoading
            ) { Text("С ограничениями") }

            if (s.isLoading) {
                Spacer(Modifier.width(6.dp))
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            }
        }

        Button(
            onClick = vm::runTemperatureComparison,
            enabled = !s.isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Сравнить temperature: 0 / 0.7 / 1.2")
        }

        Spacer(Modifier.height(6.dp))

        ResultCard(title = "Ответ без ограничений", text = s.resultNoLimits)
        ResultCard(title = "Ответ с ограничениями", text = s.resultWithLimits)

        Spacer(Modifier.height(12.dp))
        Text("Результаты temperature", style = MaterialTheme.typography.titleMedium)

        ResultCard(title = "temperature = 0", text = s.resultT0)
        ResultCard(title = "temperature = 0.7", text = s.resultT07)
        ResultCard(title = "temperature = 1.2", text = s.resultT12)

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ResultCard(title: String, text: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            SelectionContainer {
                Text(text.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}