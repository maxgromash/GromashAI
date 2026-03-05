package com.example.gromashai.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gromashai.openai.*
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(agent: OpenAiAgent) {
    val messages by agent.messages.collectAsState()
    val isLoading by agent.isLoading.collectAsState()
    val currentModel by agent.currentModel.collectAsState()
    val totalUsage by agent.totalUsage.collectAsState()
    
    val strategy by agent.strategy.collectAsState()
    val workingMemory by agent.workingMemory.collectAsState()
    val longTermMemory by agent.longTermMemory.collectAsState()
    val userProfile by agent.userProfile.collectAsState()
    val taskState by agent.taskState.collectAsState()
    
    val branches by agent.branches.collectAsState()
    val currentBranchId by agent.currentBranchId.collectAsState()

    var inputText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    
    var expandedModelMenu by remember { mutableStateOf(false) }
    var expandedStrategyMenu by remember { mutableStateOf(false) }
    var expandedBranchMenu by remember { mutableStateOf(false) }
    
    var showContextDetails by remember { mutableStateOf(false) }
    var showProfileEditor by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        
        // --- Панель управления ---
        Card(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Model:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    Box {
                        Text(
                            currentModel.displayName, 
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { expandedModelMenu = true }.padding(horizontal = 8.dp),
                            style = MaterialTheme.typography.labelMedium
                        )
                        DropdownMenu(expanded = expandedModelMenu, onDismissRequest = { expandedModelMenu = false }) {
                            AgentModel.entries.forEach { model ->
                                DropdownMenuItem(text = { Text(model.displayName) }, onClick = {
                                    agent.setModel(model)
                                    expandedModelMenu = false
                                })
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    
                    IconButton(onClick = { showProfileEditor = true }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Person, contentDescription = "Profile", tint = MaterialTheme.colorScheme.primary)
                    }
                    
                    Spacer(Modifier.width(8.dp))
                    Text("${totalUsage.totalTokens} tok", style = MaterialTheme.typography.labelSmall)
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // --- Task State Indicator ---
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Assignment, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(4.dp))
                    Text("Task: ", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Text(taskState.stage.name, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelSmall)
                    Text(" | Step: ${taskState.step}", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Strategy:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    Box {
                        Text(
                            strategy.name, 
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.clickable { expandedStrategyMenu = true }.padding(horizontal = 8.dp),
                            style = MaterialTheme.typography.labelMedium
                        )
                        DropdownMenu(expanded = expandedStrategyMenu, onDismissRequest = { expandedStrategyMenu = false }) {
                            ContextStrategy.entries.forEach { strat ->
                                DropdownMenuItem(text = { Text(strat.name) }, onClick = {
                                    agent.setStrategy(strat)
                                    expandedStrategyMenu = false
                                })
                            }
                        }
                    }
                    
                    if (strategy == ContextStrategy.BRANCHING) {
                        Spacer(Modifier.width(8.dp))
                        Text("| Branch:", style = MaterialTheme.typography.labelMedium)
                        Box {
                            Text(
                                currentBranchId,
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.clickable { expandedBranchMenu = true }.padding(horizontal = 8.dp),
                                style = MaterialTheme.typography.labelMedium
                            )
                            DropdownMenu(expanded = expandedBranchMenu, onDismissRequest = { expandedBranchMenu = false }) {
                                branches.keys.forEach { branch ->
                                    DropdownMenuItem(text = { Text(branch) }, onClick = {
                                        agent.switchBranch(branch)
                                        expandedBranchMenu = false
                                    })
                                }
                                DropdownMenuItem(
                                    text = { Row { Icon(Icons.Default.Add, null); Text("New Branch") } },
                                    onClick = {
                                        agent.createBranch()
                                        expandedBranchMenu = false
                                    }
                                )
                            }
                        }
                    }
                }

                // --- Секция Memory Layers & Task Details ---
                if (strategy == ContextStrategy.MULTI_LAYER_MEMORY || strategy == ContextStrategy.STICKY_FACTS) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (showContextDetails) "▼ Скрыть детали контекста" else "▶ Показать детали контекста",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { showContextDetails = !showContextDetails }
                    )
                    
                    AnimatedVisibility(visible = showContextDetails) {
                        Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            MemoryLayerItem("Рабочая память", workingMemory)
                            MemoryLayerItem("Долговременная память", longTermMemory)
                            MemoryLayerItem("Ожидаемое действие", taskState.expectedAction)
                        }
                    }
                }
            }
        }

        // --- Список сообщений ---
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { msg ->
                ChatBubble(message = msg)
            }
            if (isLoading) {
                item {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp).padding(8.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
        }

        // --- Ввод сообщения ---
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
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
                    scope.launch { agent.sendQuery(textToSend) }
                },
                enabled = !isLoading && inputText.isNotBlank()
            ) {
                Text("Отправить")
            }
        }
    }

    if (showProfileEditor) {
        ProfileEditorDialog(
            currentProfile = userProfile,
            onDismiss = { showProfileEditor = false },
            onSave = { updatedProfile ->
                agent.updateUserProfile(updatedProfile)
                showProfileEditor = false
            }
        )
    }
}

@Composable
fun ProfileEditorDialog(
    currentProfile: UserProfile,
    onDismiss: () -> Unit,
    onSave: (UserProfile) -> Unit
) {
    var style by remember { mutableStateOf(currentProfile.style) }
    var format by remember { mutableStateOf(currentProfile.format) }
    var constraints by remember { mutableStateOf(currentProfile.constraints) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Профиль и предпочтения") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = style, onValueChange = { style = it }, label = { Text("Стиль (напр. Офиц.)") })
                OutlinedTextField(value = format, onValueChange = { format = it }, label = { Text("Формат (напр. Списки)") })
                OutlinedTextField(value = constraints, onValueChange = { constraints = it }, label = { Text("Ограничения (напр. Без эмодзи)") })
            }
        },
        confirmButton = {
            Button(onClick = { onSave(UserProfile(style, format, constraints)) }) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

@Composable
fun MemoryLayerItem(title: String, content: String) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    ) {
        Column(modifier = Modifier.padding(6.dp)) {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
            Text(content.ifBlank { "(Пусто)" }, style = MaterialTheme.typography.labelSmall)
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
                    topStart = 12.dp, topEnd = 12.dp,
                    bottomStart = if (isUser) 12.dp else 0.dp,
                    bottomEnd = if (isUser) 0.dp else 12.dp
                ),
                modifier = Modifier.widthIn(max = 300.dp)
            ) {
                SelectionContainer {
                    Text(
                        text = message.content,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor
                    )
                }
            }
            if (!isUser && message.usage != null) {
                Text(
                    text = "${message.usage.totalTokens} tokens",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}
