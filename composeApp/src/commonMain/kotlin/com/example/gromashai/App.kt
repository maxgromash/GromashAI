package com.example.gromashai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.gromashai.openai.OpenAiApi
import com.example.gromashai.openai.PlatformApiKeyProvider
import com.example.gromashai.openai.createHttpClient
import com.example.gromashai.screens.Day1Screen
import com.example.gromashai.screens.Day2Screen

private sealed interface Screen {
    val id: String
    val title: String

    data object Home : Screen {
        override val id: String = "home"
        override val title: String = "Дом"
    }

    data object Day1 : Screen {
        override val id: String = "day1"
        override val title: String = "День 1"
    }

    data object Day2 : Screen {
        override val id: String = "day2"
        override val title: String = "День 2"
    }
}

private fun screenFromId(id: String): Screen = when (id) {
    Screen.Home.id -> Screen.Home
    Screen.Day1.id -> Screen.Day1
    Screen.Day2.id -> Screen.Day2
    else -> Screen.Home
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    MaterialTheme {
        val http = remember { createHttpClient() }
        val api = remember { OpenAiApi(http, PlatformApiKeyProvider()) }

        // ✅ fix: сохраняем только String (Bundle-safe)
        var screenId by rememberSaveable { mutableStateOf(Screen.Home.id) }
        val screen = remember(screenId) { screenFromId(screenId) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(screen.title) },
                    navigationIcon = {
                        if (screenId != Screen.Home.id) {
                            IconButton(onClick = { screenId = Screen.Home.id }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back"
                                )
                            }
                        }
                    }
                )
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
            ) {
                when (screenId) {
                    Screen.Home.id -> HomeScreen(
                        onOpenDay1 = { screenId = Screen.Day1.id },
                        onOpenDay2 = { screenId = Screen.Day2.id }
                    )

                    Screen.Day1.id -> Day1Screen(api = api)
                    Screen.Day2.id -> Day2Screen(api = api)

                    else -> HomeScreen(
                        onOpenDay1 = { screenId = Screen.Day1.id },
                        onOpenDay2 = { screenId = Screen.Day2.id }
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    onOpenDay1: () -> Unit,
    onOpenDay2: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Выбери задание:", style = MaterialTheme.typography.titleMedium)

        DayTile(
            title = "Day 1",
            subtitle = "Счастливое число + гороскоп (JSON schema)",
            onClick = onOpenDay1
        )
        DayTile(
            title = "Day 2",
            subtitle = "Формат + лимит длины + stop sequence (сравнение)",
            onClick = onOpenDay2
        )
    }
}

@Composable
private fun DayTile(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
