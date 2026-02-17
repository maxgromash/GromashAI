package com.example.gromashai

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.gromashai.openai.OpenAiApi
import com.example.gromashai.openai.PlatformApiKeyProvider
import com.example.gromashai.openai.createHttpClient

@Composable
fun App() {
    MaterialTheme {
        val vm = remember {
            val http = createHttpClient()
            val api = OpenAiApi(http, PlatformApiKeyProvider())
            ChatGptViewModel(api)
        }

        val state by vm.state.collectAsState()

        DisposableEffect(Unit) {
            onDispose { vm.dispose() }
        }

        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {

                Text(
                    text = state.text,
                    style = MaterialTheme.typography.bodyLarge
                )

                if (state.error != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Ошибка: ${state.error}",
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(Modifier.height(20.dp))

                Button(
                    onClick = { vm.generate() },
                    enabled = !state.isLoading
                ) {
                    if (state.isLoading) {
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
    }
}
