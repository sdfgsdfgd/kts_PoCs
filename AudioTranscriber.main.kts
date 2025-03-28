#!/usr/bin/env kotlin

@file:DependsOn("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
@file:DependsOn("io.ktor:ktor-client-cio-jvm:2.3.3")
@file:DependsOn("io.ktor:ktor-client-logging-jvm:2.3.3")
@file:DependsOn("io.ktor:ktor-client-websockets:2.3.3")
@file:DependsOn("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.1")
@file:OptIn(FlowPreview::class)
@file:Suppress("OPT_IN_USAGE")

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.logging.DEFAULT
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

// xx       === Resources ===
//  https://platform.openai.com/docs/guides/realtime-transcription
//  https://platform.openai.com/docs/guides/speech-to-text
//  https://github.com/openai/whisper/discussions/264        ( OpenAI/whisper/discussions/264 - Transcription and diarization )
//
//  Doesn't seem to be as accurate as time-stamp, buffer corrected normal Transcription. Overall, a successful PoC nevertheless.
//  Real-time can be overlayed by the longer-buffered audio-transcription via a secondary call, to achieve ultimate accuracy (100%) of words at the expense of paying a bit more :P
//
// It can also be overlayed by  `Speaker Diarization` via Elevenlabs API, to distinguish between speakers.

//
//
//
// =====================================================|
//                                                      |
//  Real-time Transcription API  &  Speech-to-Text API  |
//             ( OpenAI )                               |
// =====================================================|
//
//
//

// StateFlow to hold the entire evolving transcript
private val _transcriptionStateFlow = MutableStateFlow("")
val transcriptionStateFlow: StateFlow<String> = _transcriptionStateFlow

GlobalScope.launch {
    transcriptionStateFlow.debounce(2.seconds).filterNot { it.isBlank() }.collect { text ->
        _transcriptionStateFlow.value = "${_transcriptionStateFlow.value} \n"

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
                    put("prompt", "expect words relating to kotlin, android development or software keywords")
                    put("language", "en")
                }
                putJsonObject("turn_detection") {
                    put("type", "server_vad")
                    put("threshold", 0.5)
                    put("prefix_padding_ms", 300)
                    put("silence_duration_ms", 200)
                }
                putJsonObject("input_audio_noise_reduction") {
                    put("type", "near_field") // values:  null, near_field, far_field | Default: near_field  if null not passed
                }
                put("include", buildJsonArray {
                    add("item.input_audio_transcription.logprobs") // <-- Use to calculate confidence scores  ( will be very helpful with accuracy improvements & diarization)
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
 * 2)   Cold Flow<ByteArray> that captures from the mic.
 *      We hold the line open until the collector cancels (structured concurrency)
 */
fun micFlow(
    format: AudioFormat = AudioFormat(16000f, 16, 1, true, false),
    bufferSize: Int = 6400 // Corresponds to 400ms of audio, still potentially cuts too many words
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

    // Use prompt entry for Job toggle  ( pause/resume )
    val userInputJob = launch(Dispatchers.IO) {
        readlnOrNull()
        println("User pressed ENTER. Stopping ...")
        coroutineContext.cancelChildren()  // cancel all siblings
    }

    // 3) Connect via WebSocket and handle transcription events
    val webSocketJob = launch {
        client.webSocket(request = {
            url("wss://api.openai.com/v1/realtime?intent=transcription")
            header(HttpHeaders.Authorization, ephemeralBearer)
            header("OpenAI-Beta", "realtime=v1")
        }) {
            println("WS CONNECTED, ephemeralSessionId=$ephemeralSessionId")
            var micJob: Job? = null
            try {
                for (frame in incoming) {
                    frame as? Frame.Text ?: continue
                    val root = frame.readText().parseJson()
                    when (root["type"]?.jsonPrimitive?.contentOrNull) {
                        "transcription_session.created" -> {
                            send(
                                Frame.Text(
                                    buildJsonObject {
                                        put("type", "transcription_session.update")
                                        putJsonObject("session") { }
                                    }.toString()
                                )
                            )
                            println("✅ Session created. Starting mic stream...")

                            // 4) Start streaming mic data
                            micJob = launch {
                                micFlow().collect { audioChunk ->
                                    val b64 = Base64.getEncoder().encodeToString(audioChunk)
                                    send(
                                        Frame.Text(
                                            buildJsonObject {
                                                put("type", "input_audio_buffer.append")
                                                put("event_id", "event_${System.currentTimeMillis()}")
                                                put("audio", b64)
                                            }.toString()
                                        )
                                    )
                                }
                            }
                        }

                        "conversation.item.input_audio_transcription.delta" -> { // Partial transcripts
                            val snippet = root["delta"]?.jsonPrimitive?.contentOrNull
                                ?: root["transcript"]?.jsonPrimitive?.contentOrNull
                                ?: ""
                            _transcriptionStateFlow.value += " $snippet"
                        }

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

                        "conversation.item.input_audio_transcription.completed",
                        "transcription_session.updated",
                        "input_audio_buffer.speech_started",
                        "input_audio_buffer.speech_stopped",
                        "input_audio_buffer.committed" -> {
                            println("✅ [ ${root["type"]} ] -> Msg contents were --> $root")
                        }

                        else -> println("unknown message type: ${root["type"]?.jsonPrimitive?.contentOrNull}")
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

fun String.parseJson() = Json.parseToJsonElement(this).jsonObject
