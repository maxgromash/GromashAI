package com.example.gromashai.mcp

interface McpManager {
    suspend fun connectToPostman(apiKey: String)
    suspend fun fetchAvailableTools(): String
}

expect fun createMcpManager(): McpManager