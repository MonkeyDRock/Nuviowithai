package com.nuvio.app.features.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

// --- State & Interfaces ---

/** 
 * Implement this interface in your DI or data layer using DataStore 
 * or Multiplatform Settings to persist the key. 
 */
interface ApiKeyManager {
    fun getKey(): String?
    fun saveKey(key: String)
}

/** 
 * Represents the current state of Nuvio's media player. 
 */
data class PlayerState(val isPlaying: Boolean, val isFullScreen: Boolean)

// --- Parsed Message Segments ---

sealed class MessageSegment {
    data class Text(val content: String) : MessageSegment()
    data class TmdbLink(val title: String, val mediaType: String, val id: String) : MessageSegment()
}

// --- Main Composable ---

@Composable
fun GeminiChatOverlay(
    navController: NavController,
    playerState: PlayerState,
    apiKeyManager: ApiKeyManager,
    modifier: Modifier = Modifier
) {
    var apiKey by remember { mutableStateOf(apiKeyManager.getKey()) }
    var isOverlayVisible by remember { mutableStateOf(true) }

    // Auto-Dismiss on Playback
    LaunchedEffect(playerState.isPlaying, playerState.isFullScreen) {
        if (playerState.isPlaying || playerState.isFullScreen) {
            isOverlayVisible = false
        }
    }

    if (!isOverlayVisible) {
        // Minimized state - tap to open chat
        FloatingActionButton(
            onClick = { isOverlayVisible = true },
            modifier = modifier.padding(16.dp)
        ) {
            Text("AI", modifier = Modifier.padding(16.dp))
        }
        return
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(0.6f)
            .padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 8.dp
    ) {
        if (apiKey.isNullOrEmpty()) {
            ApiKeyPrompt(
                onKeySubmitted = { newKey ->
                    apiKeyManager.saveKey(newKey)
                    apiKey = newKey
                }
            )
        } else {
            ChatScreen(
                apiKey = apiKey!!,
                navController = navController,
                onClose = { isOverlayVisible = false }
            )
        }
    }
}

// --- UI Components ---

@Composable
fun ApiKeyPrompt(onKeySubmitted: (String) -> Unit) {
    var input by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Welcome to Nuvio AI", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Enter your Gemini API key to enable fast, free recommendations.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("Gemini API Key") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { if (input.isNotBlank()) onKeySubmitted(input.trim()) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save & Continue")
        }
    }
}

@Composable
fun ChatScreen(
    apiKey: String,
    navController: NavController,
    onClose: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var inputText by remember { mutableStateOf("") }
    val chatHistory = remember { mutableStateListOf<ChatMessage>() }
    var isLoading by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("AI Assistant", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onClose) { Text("Close") }
        }

        Divider()

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(8.dp),
            reverseLayout = false
        ) {
            items(chatHistory) { message ->
                MessageBubble(message = message, navController = navController)
            }
        }

        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask for a movie or show...") },
                singleLine = true
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    if (inputText.isNotBlank()) {
                        val query = inputText
                        inputText = ""
                        chatHistory.add(ChatMessage(role = "user", content = query))
                        isLoading = true
                        
                        coroutineScope.launch {
                            val response = generateGeminiResponse(apiKey, query)
                            chatHistory.add(ChatMessage(role = "model", content = response))
                            isLoading = false
                        }
                    }
                },
                enabled = !isLoading
            ) {
                Text("Send")
            }
        }
    }
}

@Composable
fun MessageBubble(message: ChatMessage, navController: NavController) {
    val isUser = message.role == "user"
    val align = if (isUser) Alignment.End else Alignment.Start
    val bgColor = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = align
    ) {
        Box(
            modifier = Modifier
                .background(bgColor, RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            if (isUser) {
                Text(message.content)
            } else {
                ParsedMessageContent(message.content, navController)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ParsedMessageContent(text: String, navController: NavController) {
    val segments = remember(text) { parseGeminiResponse(text) }

    Column {
        segments.forEach { segment ->
            when (segment) {
                is MessageSegment.Text -> {
                    if (segment.content.isNotBlank()) {
                        Text(text = segment.content.trim())
                    }
                }
                is MessageSegment.TmdbLink -> {
                    SuggestionChip(
                        onClick = { navController.navigate("details/${segment.mediaType}/${segment.id}") },
                        label = { Text(segment.title) },
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        }
    }
}

// --- Parsing Logic ---

fun parseGeminiResponse(text: String): List<MessageSegment> {
    val regex = """\[(.*?)\]\(tmdb://(movie|tv)/(\d+)\)""".toRegex()
    val segments = mutableListOf<MessageSegment>()
    var lastIndex = 0

    regex.findAll(text).forEach { matchResult ->
        if (matchResult.range.first > lastIndex) {
            segments.add(MessageSegment.Text(text.substring(lastIndex, matchResult.range.first)))
        }
        segments.add(
            MessageSegment.TmdbLink(
                title = matchResult.groupValues[1],
                mediaType = matchResult.groupValues[2], // "movie" or "tv"
                id = matchResult.groupValues[3]
            )
        )
        lastIndex = matchResult.range.last + 1
    }

    if (lastIndex < text.length) {
        segments.add(MessageSegment.Text(text.substring(lastIndex)))
    }
    
    return segments
}

// --- Network & API ---

data class ChatMessage(val role: String, val content: String)

/** 
 * Uses Ktor to call the Gemini 1.5 Flash endpoint. 
 * Ensure io.ktor:ktor-client-core and io.ktor:ktor-client-content-negotiation are in your build.gradle.kts 
 */
suspend fun generateGeminiResponse(apiKey: String, prompt: String): String {
    val client = HttpClient {
        // Assume ContentNegotiation is installed with kotlinx.serialization
    }
    
    val systemInstruction = """
        You are Nuvio's internal media assistant. 
        When recommending movies or TV shows, you MUST format the title as a deep link 
        using exactly this syntax: [Title](tmdb://movie/ID) or [Title](tmdb://tv/ID).
        Do not use standard web URLs for TMDB items.
    """.trimIndent()

    val payload = """
        {
          "system_instruction": {
            "parts": { "text": "$systemInstruction" }
          },
          "contents": [
            {
              "role": "user",
              "parts": [{ "text": "$prompt" }]
            }
          ]
        }
    """.trimIndent()

    return try {
        val response: HttpResponse = client.post("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey") {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        
        // Basic string parsing for the snippet - in production, parse this via kotlinx.serialization 
        // to match the GeminiResponse data classes.
        val responseBody = response.bodyAsText()
        extractTextFromJson(responseBody)
    } catch (e: Exception) {
        "Error fetching response: ${e.message}"
    } finally {
        client.close()
    }
}

// Helper for raw JSON parsing (replace with proper kotlinx.serialization models for production)
fun extractTextFromJson(json: String): String {
    val textRegex = """"text"\s*:\s*"(.*?)"""".toRegex()
    val match = textRegex.find(json)
    return match?.groupValues?.get(1)?.replace("\\n", "\n")?.replace("\\\"", "\"") 
        ?: "I couldn't process that request."
}
