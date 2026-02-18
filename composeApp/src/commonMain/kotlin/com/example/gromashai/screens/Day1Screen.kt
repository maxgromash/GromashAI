package com.example.gromashai.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.gromashai.openai.OpenAiApi

@Composable
fun Day1Screen(api: OpenAiApi) {
    val vm = remember { Day1ViewModel(api) }
    val s by vm.state.collectAsState()

    DisposableEffect(Unit) {
        onDispose { vm.dispose() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(s.text, style = MaterialTheme.typography.bodyLarge)

        if (s.error != null) {
            Spacer(Modifier.height(12.dp))
            Text("Ошибка: ${s.error}", color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = vm::generate,
            enabled = !s.isLoading
        ) {
            if (s.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(10.dp))
                Text("Генерирую…")
            } else {
                Text("Дай число и гороскоп")
            }
        }
    }
}
