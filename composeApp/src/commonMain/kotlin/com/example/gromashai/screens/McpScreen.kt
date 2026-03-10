package com.example.gromashai.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.gromashai.mcp.createMcpManager
import com.example.gromashai.openai.PlatformApiKeyProvider
import kotlinx.coroutines.launch

@Composable
fun McpScreen(apiKeyProvider: PlatformApiKeyProvider) {
    var state by remember { mutableStateOf<String>("Ready to connect") }
    var toolsList by remember { mutableStateOf<String>("") }
    var isLoading by remember { mutableStateOf(false) }
    var isConnected by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    
    val mcpManager = remember { createMcpManager() }
    
    var apiKey by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Postman MCP Connection",
            style = MaterialTheme.typography.headlineSmall
        )

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("Postman API Key (PMAK)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            enabled = !isConnected
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    scope.launch {
                        try {
                            isLoading = true
                            state = "Connecting to Postman..."
                            mcpManager.connectToPostman(apiKey)
                            isConnected = true
                            state = "Connected! Fetching collection methods..."
                            
                            val tools = mcpManager.fetchAvailableTools()
                            toolsList = tools
                            state = "Collection methods loaded."
                        } catch (e: Exception) {
                            state = "Error: ${e.message}"
                            isConnected = false
                        } finally {
                            isLoading = false
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = !isLoading && apiKey.isNotBlank() && !isConnected
            ) {
                Text("Connect")
            }

            if (isConnected) {
                OutlinedButton(
                    onClick = {
                        isConnected = false
                        state = "Disconnected"
                        toolsList = ""
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Reset")
                }
            }
        }
        
        if (isLoading) {
            CircularProgressIndicator()
        }
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Status: $state",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (state.startsWith("Error")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (toolsList.isNotBlank()) {
            Text(
                text = "Available Methods (from Collections):",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.Start)
            )
            
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface
            ) {
                Text(
                    text = toolsList,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
