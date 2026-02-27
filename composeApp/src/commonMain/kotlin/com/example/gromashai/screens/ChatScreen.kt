package com.example.gromashai.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gromashai.openai.AgentModel
import com.example.gromashai.openai.ChatMessage
import com.example.gromashai.openai.OpenAiAgent
import com.example.gromashai.openai.TokenUsage
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(agent: OpenAiAgent) {
    val messages by agent.messages.collectAsState()
    val isLoading by agent.isLoading.collectAsState()
    val isCompressing by agent.isCompressing.collectAsState()
    val currentModel by agent.currentModel.collectAsState()
    val lastUsage by agent.lastUsage.collectAsState()
    val totalUsage by agent.totalUsage.collectAsState()
    val summary by agent.summary.collectAsState()

    var inputText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var expandedModelMenu by remember { mutableStateOf(false) }
    var showSummary by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        
        // --- Model Selector & Total Stats ---
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Модель: ", fontWeight = FontWeight.Bold)
                    Box {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clickable { expandedModelMenu = true }
                                .padding(4.dp)
                        ) {
                            Text(currentModel.displayName, color = MaterialTheme.colorScheme.primary)
                            Icon(Icons.Default.ArrowDropDown, "Select Model")
                        }
                        DropdownMenu(
                            expanded = expandedModelMenu,
                            onDismissRequest = { expandedModelMenu = false }
                        ) {
                            AgentModel.entries.forEach { model ->
                                DropdownMenuItem(
                                    text = { Text(model.displayName) },
                                    onClick = {
                                        agent.setModel(model)
                                        expandedModelMenu = false
                                    }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    // Total Usage (Session)
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Session Total:", style = MaterialTheme.typography.labelSmall)
                        Text(
                            "${totalUsage.totalTokens} tok", 
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                
                // --- Summary Status ---
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (summary.isNotBlank()) {
                         Text(
                             text = "Контекст сжат", 
                             style = MaterialTheme.typography.labelSmall, 
                             color = MaterialTheme.colorScheme.tertiary,
                             fontWeight = FontWeight.Bold
                         )
                         Spacer(Modifier.width(8.dp))
                         Text(
                             text = if (showSummary) "Скрыть" else "Показать",
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.primary,
                             modifier = Modifier.clickable { showSummary = !showSummary }
                         )
                    } else {
                        Text("Сжатие не активно", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    }
                    
                    Spacer(Modifier.weight(1f))
                    
                    if (isCompressing) {
                        Text("Сжимаю контекст...", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(4.dp))
                        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.dp)
                    }
                }
                
                AnimatedVisibility(visible = showSummary) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) {
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodySmall,
                            fontStyle = FontStyle.Italic,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }

                if (lastUsage != null) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text("Last Response Usage:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Prompt: ${lastUsage?.promptTokens}", style = MaterialTheme.typography.labelSmall)
                        Text("Compl: ${lastUsage?.completionTokens}", style = MaterialTheme.typography.labelSmall)
                        Text("Total: ${lastUsage?.totalTokens}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { msg ->
                ChatBubble(message = msg)
            }
            if (isLoading) {
                item {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(24.dp)
                            .padding(8.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Введите сообщение...") },
                maxLines = 4
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    val textToSend = inputText
                    inputText = ""
                    scope.launch {
                        agent.sendQuery(textToSend)
                    }
                },
                enabled = !isLoading && inputText.isNotBlank()
            ) {
                Text("Отправить")
            }
        }
    }
}

@Composable
fun ChatBubble(message: ChatMessage) {
    val isUser = message.role == "user"
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val bgColor = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
    val textColor = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
            Surface(
                color = bgColor,
                shape = RoundedCornerShape(
                    topStart = 12.dp,
                    topEnd = 12.dp,
                    bottomStart = if (isUser) 12.dp else 0.dp,
                    bottomEnd = if (isUser) 0.dp else 12.dp
                ),
                modifier = Modifier.widthIn(max = 300.dp)
            ) {
                SelectionContainer {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor
                        )
                    }
                }
            }
            
            // Show usage for individual assistant messages if available
            if (!isUser && message.usage != null) {
                Text(
                    text = "${message.usage.totalTokens} tokens",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, start = 4.dp)
                )
            }
        }
    }
}
