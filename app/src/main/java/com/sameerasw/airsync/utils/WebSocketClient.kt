package com.sameerasw.airsync.utils

import android.util.Log
import okhttp3.*
import okio.ByteString

class WebSocketClient(
    private val url: String,
    private val onMessage: (String) -> Unit,
    private val onConnectionStatus: (Boolean) -> Unit
) {
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient()

    fun connect() {
        if (url.isBlank() || (!url.startsWith("ws://") && !url.startsWith("wss://"))) {
            onConnectionStatus(false)
            return
        }
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onConnectionStatus(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                onMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                onMessage(bytes.hex())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                onConnectionStatus(false)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("WebSocketClient", "Connection failed", t)
                onConnectionStatus(false)
            }
        })
    }

    fun sendMessage(message: String): Boolean {
        return webSocket?.send(message) ?: false
    }

    fun disconnect() {
        webSocket?.close(1000, "Client disconnected")
    }
}