package com.example.gromashai.mcp

import android.util.Log
import io.ktor.client.*
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.jetbrains.kotlinx.mcp.JSONRPCMessage
import org.jetbrains.kotlinx.mcp.shared.Transport

class PostmanSseTransport(
    private val client: HttpClient,
    private val urlString: String
) : Transport {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var session: ClientSSESession? = null
    private var sessionCookies: String? = null
    
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    override var onClose: (() -> Unit)? = null
    override var onError: ((Throwable) -> Unit)? = null
    override var onMessage: (suspend ((JSONRPCMessage) -> Unit))? = null

    private var collectionJob: Job? = null

    override suspend fun start() {
        Log.d("PostmanTransport", "Transport started")
    }

    override suspend fun send(message: JSONRPCMessage) {
        val originalElement = json.encodeToJsonElement(JSONRPCMessage.serializer(), message).jsonObject
        val cleanedMap = originalElement.toMutableMap()
        cleanedMap["jsonrpc"] = JsonPrimitive("2.0")
        
        if (cleanedMap["params"] is JsonNull) cleanedMap.remove("params")
        
        val cleanedMessage = JsonObject(cleanedMap)
        val messageJson = cleanedMessage.toString()
        val method = cleanedMessage["method"]?.jsonPrimitive?.content ?: ""

        if (method == "notifications/initialized") return

        if (session == null) {
            Log.d("PostmanTransport", "Handshake: $messageJson")
            try {
                val newSession = client.sseSession(urlString) {
                    this.method = HttpMethod.Post
                    header("Accept", "application/json, text/event-stream")
                    contentType(ContentType.Application.Json)
                    setBody(messageJson)
                }
                sessionCookies = newSession.call.response.headers.getAll(HttpHeaders.SetCookie)?.joinToString("; ") { it.split(";")[0] }
                session = newSession
                startCollecting(newSession)
                
                // Проверяем, нет ли ответа прямо в теле рукопожатия
                // (Postman может вернуть ответ на initialize сразу)
            } catch (e: Exception) {
                onError?.invoke(e)
                throw e
            }
        } else {
            try {
                val response = client.post(urlString) {
                    header("Accept", "application/json, text/event-stream")
                    contentType(ContentType.Application.Json)
                    sessionCookies?.let { header(HttpHeaders.Cookie, it) }
                    setBody(messageJson)
                }
                
                if (response.status.isSuccess()) {
                    val body = response.bodyAsText()
                    // Если Postman вернул ответ ПРЯМО в теле HTTP (нестандартно для MCP, но бывает в их шлюзе)
                    if (body.contains("data: {")) {
                        val jsonOnly = body.substringAfter("data: ").substringBefore("\n")
                        try {
                            val msg = json.decodeFromString<JSONRPCMessage>(jsonOnly)
                            Log.d("PostmanTransport", "Direct Response handled: $jsonOnly")
                            onMessage?.invoke(msg)
                        } catch (e: Exception) { }
                    }
                } else {
                    Log.e("PostmanTransport", "Error ${response.status}: ${response.bodyAsText()}")
                }
            } catch (e: Exception) {
                onError?.invoke(e)
                throw e
            }
        }
    }

    private fun startCollecting(sseSession: ClientSSESession) {
        collectionJob = scope.launch {
            try {
                sseSession.incoming.collect { event ->
                    val data = event.data ?: ""
                    if (data.isNotBlank() && data.startsWith("{")) {
                        try {
                            val msg = json.decodeFromString<JSONRPCMessage>(data)
                            Log.d("PostmanTransport", "SSE Message: $data")
                            onMessage?.invoke(msg)
                        } catch (e: Exception) { }
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) onError?.invoke(e)
            }
        }
    }

    override suspend fun close() {
        collectionJob?.cancel()
        session?.cancel()
        onClose?.invoke()
    }
}
