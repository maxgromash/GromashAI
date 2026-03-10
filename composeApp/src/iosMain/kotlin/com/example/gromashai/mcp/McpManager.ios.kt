package com.example.gromashai.mcp

actual fun createMcpManager(): McpManager = IosMcpManager()

class IosMcpManager : McpManager {
    override suspend fun connectToPostman(apiKey: String) {
        // Not implemented on iOS
    }
    
    override suspend fun fetchAvailableTools(): String {
        return "MCP is not supported on iOS yet."
    }
}
