#!/usr/bin/env kotlin

@file:DependsOn("io.ktor:ktor-client-cio-jvm:2.3.10")
@file:DependsOn("io.ktor:ktor-client-websockets:2.3.10")
@file:DependsOn("io.ktor:ktor-client-content-negotiation-jvm:2.3.10")
@file:DependsOn("io.ktor:ktor-serialization-gson-jvm:2.3.10")
@file:DependsOn("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
@file:DependsOn("com.google.code.gson:gson:2.10.1")

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.serialization.gson.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import com.google.gson.*
import kotlinx.coroutines.channels.consumeEach


main()

//----------_>  Trigger after:    Cursor --args --remote-debugging-port=9222
fun main() = runBlocking {
    println("- 1 -")

    val client = HttpClient(CIO) {
        install(WebSockets)
        install(ContentNegotiation) { gson() }
    }
    println("test")


    runBlocking {
        val targets: List<JsonObject> = client.get("http://localhost:9222/json").body()
        val webSocketDebuggerUrl = targets.firstOrNull()?.get("webSocketDebuggerUrl")?.asString
            ?: error("Couldn't find websocket debugger URL.")

        println("Connecting to CDP at: $webSocketDebuggerUrl")

        client.webSocket(webSocketDebuggerUrl) {
            println("Connected via CDP!")

            var messageId = 1

            suspend fun sendCommand(method: String, params: JsonObject? = null) {
                val payload = JsonObject().apply {
                    addProperty("id", messageId++)
                    addProperty("method", method)
                    params?.let { add("params", it) }
                }
                send(payload.toString())
            }

            // CRITICAL STEP: Enable Runtime domain first!
            sendCommand("Runtime.enable")

            suspend fun evaluate(script: String) = sendCommand(
                "Runtime.evaluate",
                JsonObject().apply { addProperty("expression", script) }
            )

            suspend fun pressShortcut(key: String, modifiers: Int = 8) { // 8 is CMD (Meta)
                sendCommand("Input.dispatchKeyEvent", JsonObject().apply {
                    addProperty("type", "keyDown")
                    addProperty("key", key)
                    addProperty("modifiers", modifiers)
                })
                delay(50)
                sendCommand("Input.dispatchKeyEvent", JsonObject().apply {
                    addProperty("type", "keyUp")
                    addProperty("key", key)
                    addProperty("modifiers", modifiers)
                })
            }

            // Evaluate initially
            evaluate("!!document.querySelector('.aislash-editor-input')")

            incoming.consumeEach { frame ->
                if (frame is Frame.Text) {
                    val response = JsonParser.parseString(frame.readText()).asJsonObject

                    if (response.has("id")) {
                        val result = response["result"]?.asJsonObject
                        val exists = result
                            ?.getAsJsonObject("result")
                            ?.get("value")?.asBoolean

                        when (exists) {
                            false -> {
                                println("Agent input not open, triggering CMD+I")
                                pressShortcut("i") // CMD+I
                                delay(1000)
                                evaluate("!!document.querySelector('.aislash-editor-input')")
                            }

                            true -> {
                                println("Agent input available, typing query...")
                                evaluate("document.querySelector('.aislash-editor-input').focus()")
                                evaluate(
                                    """document.execCommand('insertText', false, '--- hi how are you ---')"""
                                )
                                delay(50)
                                sendCommand("Input.dispatchKeyEvent", JsonObject().apply {
                                    addProperty("type", "keyDown")
                                    addProperty("key", "Enter")
                                })
                                sendCommand("Input.dispatchKeyEvent", JsonObject().apply {
                                    addProperty("type", "keyUp")
                                    addProperty("key", "Enter")
                                })

                                println("✅ Query sent!")
                                close(CloseReason(CloseReason.Codes.NORMAL, "Done"))
                            }

                            else -> Unit
                        }
                    }
                }
            }
        }

        client.close()
    }
}
