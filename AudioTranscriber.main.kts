#!/usr/bin/env kotlin

@file:DependsOn("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
@file:DependsOn("io.ktor:ktor-client-cio-jvm:2.3.3")
@file:DependsOn("io.ktor:ktor-client-logging-jvm:2.3.3")
@file:DependsOn("io.ktor:ktor-client-websockets:2.3.3")
@file:DependsOn("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.1")

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.text.SimpleDateFormat
import java.util.*
import javax.sound.sampled.*
import kotlin.system.exitProcess

// StateFlow to hold the entire evolving transcript
private val _transcriptionStateFlow = MutableStateFlow("")
val transcriptionStateFlow: StateFlow<String> = _transcriptionStateFlow

GlobalScope.launch {
    transcriptionStateFlow.collectLatest { text ->
        println("\n[Transcript] => $text\n")
    }
}


/**
 * 1) Fetch ephemeral session from OpenAI, returning (sessionId, ephemeralBearer).
 */
suspend fun obtainEphemeralSession(
    client: HttpClient,
    openAiApiKey: String
): Pair<String, String> {
    val resp = client.post("https://api.openai.com/v1/realtime/transcription_sessions") {
        header(HttpHeaders.Authorization, "Bearer $openAiApiKey")
        header("OpenAI-Beta", "realtime=v1")
        contentType(ContentType.Application.Json)
        setBody(
            buildJsonObject {
                put("input_audio_format", "pcm16")
                putJsonObject("input_audio_transcription") {
                    put("model", "gpt-4o-transcribe")
                    put("prompt", "")
                    put("language", "en")
                }
                putJsonObject("turn_detection") {
                    put("type", "server_vad")
                    put("threshold", 0.5)
                    put("prefix_padding_ms", 300)
                    put("silence_duration_ms", 500)
                }
                putJsonObject("input_audio_noise_reduction") {
                    put("type", "near_field")
                }
                put("include", buildJsonArray {
                    add("item.input_audio_transcription.logprobs")
                })
            }.toString()
        )
    }
    check(resp.status.isSuccess()) { "Failed ephemeral request => ${resp.status}" }

    val root = resp.body<String>().parseJson()
    val sessionId = root["id"]?.jsonPrimitive?.content
        ?: error("No ephemeral session id found")
    val clientSecretObj = root["client_secret"]?.jsonObject
        ?: error("No ephemeral token in response")
    val ephemeralToken = clientSecretObj["value"]?.jsonPrimitive?.content
        ?: error("No ephemeral token found")

    // Print optional expiry info
    clientSecretObj["expires_at"]?.jsonPrimitive?.longOrNull?.let { epochSec ->
        println(
            "Ephemeral token expires at: " +
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date(epochSec * 1000))
        )
    }

    println("Ephemeral session ID => $sessionId")
    return sessionId to "Bearer $ephemeralToken"
}

/**
 * 2) Create a cold Flow<ByteArray> that captures from the mic. We hold
 *    the line open until the collector cancels (structured concurrency).
 */
fun microphoneFlow(
    format: AudioFormat = AudioFormat(16000f, 16, 1, true, false),
    bufferSize: Int = 2048
): Flow<ByteArray> = callbackFlow {
    val info = DataLine.Info(TargetDataLine::class.java, format)
    check(AudioSystem.isLineSupported(info)) { "No supported microphone found!" }
    val line = (AudioSystem.getLine(info) as TargetDataLine).apply {
        open(format)
        start()
    }

    val buffer = ByteArray(bufferSize)
    try {
        while (isActive) {
            val read = line.read(buffer, 0, buffer.size)
            if (read > 0) send(buffer.copyOf(read))
        }
    } finally {
        line.stop()
        line.close()
    }
    // We never manually close; the callbackFlow closes on cancel.
}

runBlocking {
    val openAiApiKey = System.getenv("OPENAI_API_KEY")
        ?: error("No OPENAI_API_KEY found in environment!")

    println("Starting realtime transcription. Press ENTER at any time to stop.")

    // Prepare an HttpClient
    val client = HttpClient(CIO) {
        install(WebSockets)
        install(Logging) {
            logger = Logger.DEFAULT
            level = LogLevel.INFO
        }
    }

    // Obtain ephemeral session & token
    val (ephemeralSessionId, ephemeralBearer) = obtainEphemeralSession(client, openAiApiKey)
    println("Ephemeral token obtained => $ephemeralBearer")

    // A job that waits for user input (ENTER) to stop
    val userInputJob = launch(Dispatchers.IO) {
        readlnOrNull()
        println("User pressed ENTER. Stopping ...")
        coroutineContext.cancelChildren()  // cancel all siblings
    }

    // 3) Connect via WebSocket and handle transcription events
    val webSocketJob = launch {
        client.webSocket(
            request = {
                url("wss://api.openai.com/v1/realtime?intent=transcription")
                header(HttpHeaders.Authorization, ephemeralBearer)
                header("OpenAI-Beta", "realtime=v1")
            }
        ) {
            println("WS CONNECTED, ephemeralSessionId=$ephemeralSessionId")

            // We'll start a mic streaming job only once we see 'transcription_session.created'
            var micJob: Job? = null

            try {
                for (frame in incoming) {
                    frame as? Frame.Text ?: continue
                    val text = frame.readText()
                    val root = text.parseJson()
                    val eventType = root["type"]?.jsonPrimitive?.contentOrNull

                    when (eventType) {
                        "transcription_session.created" -> {
                            // Indicate we're ready to receive updates
                            send(
                                Frame.Text(
                                    buildJsonObject {
                                        put("type", "transcription_session.update")
                                        putJsonObject("session") { }
                                    }.toString()
                                )
                            )
                            println("✅ Session confirmed. Starting mic stream...")

                            // 4) Start streaming mic data
                            micJob = launch {
                                microphoneFlow().collect { audioChunk ->
                                    val b64 = Base64.getEncoder().encodeToString(audioChunk)
                                    val frameObj = buildJsonObject {
                                        put("type", "input_audio_buffer.append")
                                        put("event_id", "event_${System.currentTimeMillis()}")
                                        put("audio", b64)
                                    }
                                    send(Frame.Text(frameObj.toString()))
                                }
                            }
                        }

                        // Partial transcripts
                        "conversation.item.input_audio_transcription.delta" -> {
                            val snippet = root["delta"]?.jsonPrimitive?.contentOrNull
                                ?: root["transcript"]?.jsonPrimitive?.contentOrNull
                                ?: ""
                            _transcriptionStateFlow.value += " $snippet"
                        }

                        // "Final" chunk for a turn (or chunk)
                        "conversation.item.created" -> {
                            val item = root["item"]?.jsonObject ?: continue
                            val contentArray = item["content"]?.jsonArray ?: continue
                            val transcript = contentArray.firstOrNull {
                                it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "input_audio"
                            }?.jsonObject?.get("transcript")?.jsonPrimitive?.contentOrNull

                            if (!transcript.isNullOrBlank()) {
                                _transcriptionStateFlow.value += " $transcript"
                                println("\n[Final chunk] => $transcript\n")
                            }
                        }

                        else -> {
                            // For debugging: show any other event that arrives
//                            println("Other event => $eventType, full=$root")
                        }
                    }
                }
            } catch (ce: CancellationException) {
                println("WS receiving loop cancelled => ${ce.message}")
            } finally {
                println("Closing mic stream.")
                micJob?.cancelAndJoin()
            }
        }
    }

    // Wait for both user-input and WebSocket jobs
    joinAll(userInputJob, webSocketJob)

    // Print final transcript and cleanup
    println("Cleaning up. Final transcript => \"${transcriptionStateFlow.value}\"")
    client.close()
    exitProcess(0)
}
/**
 * Main driver in a runBlocking script, with maximum coroutines idiom.
 */

fun String.parseJson() = Json.parseToJsonElement(this).jsonObject
