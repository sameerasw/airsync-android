package com.sameerasw.airsync.quickshare

import android.content.Context
import android.util.Log
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * A TCP server that listens for incoming Quick Share connections.
 */
class QuickShareServer(
    private val context: Context,
    private val onNewConnection: (InboundQuickShareConnection) -> Unit
) {
    companion object {
        private const val TAG = "QuickShareServer"
    }

    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newSingleThreadExecutor()
    private var isRunning = false

    val port: Int
        get() = serverSocket?.localPort ?: -1

    fun start() {
        if (isRunning) return
        isRunning = true
        
        try {
            serverSocket = ServerSocket(0) // Bind to any available port synchronously
            val currentPort = port
            Log.d(TAG, "Server bound to port $currentPort")
            
            executor.execute {
                try {
                    while (isRunning) {
                        val socket = serverSocket?.accept() ?: break
                        Log.d(TAG, "New connection from ${socket.remoteSocketAddress}")
                        
                        val connection = InboundQuickShareConnection(
                            context = context,
                            socket = socket
                        )
                        onNewConnection(connection)
                    }
                } catch (e: Exception) {
                    if (isRunning) {
                        Log.e(TAG, "Server accept error", e)
                    }
                } finally {
                    stop()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server", e)
            isRunning = false
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // Ignore
        }
        serverSocket = null
    }
}
