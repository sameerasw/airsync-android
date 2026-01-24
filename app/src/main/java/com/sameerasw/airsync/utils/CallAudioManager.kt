package com.sameerasw.airsync.utils

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object CallAudioManager {
    private const val TAG = "CallAudioManager"
    private const val SAMPLE_RATE = 8000
    private const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
    private const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    private var audioTrack: AudioTrack? = null
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var isRunning = false

    @SuppressLint("MissingPermission")
    fun startCallAudio(context: Context) {
        if (isRunning) return
        isRunning = true
        Log.d(TAG, "Starting call audio...")

        try {
            // Setup AudioTrack for playback
            val minBufferSizeOut = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_FORMAT)
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_CONFIG_OUT)
                        .build()
                )
                .setBufferSizeInBytes(minBufferSizeOut)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            
            audioTrack?.play()

            // Setup AudioRecord for capture
            val minBufferSizeIn = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT)
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG_IN,
                AUDIO_FORMAT,
                minBufferSizeIn
            )

            audioRecord?.startRecording()

            // Start recording loop
            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                val buffer = ByteArray(minBufferSizeIn)
                while (isActive && isRunning) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        val base64 = Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP)
                        sendMicAudio(base64)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting call audio: ${e.message}")
            stopCallAudio()
        }
    }

    fun stopCallAudio() {
        if (!isRunning) return
        isRunning = false
        Log.d(TAG, "Stopping call audio...")

        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null

            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio: ${e.message}")
        }
    }

    fun playReceivedAudio(base64Audio: String) {
        if (!isRunning || audioTrack == null) return
        try {
            val audioData = Base64.decode(base64Audio, Base64.NO_WRAP)
            audioTrack?.write(audioData, 0, audioData.size)
        } catch (e: Exception) {
            Log.e(TAG, "Error playing audio: ${e.message}")
        }
    }

    private fun sendMicAudio(base64Audio: String) {
        val message = """
        {
            "type": "callMicAudio",
            "data": {
                "audio": "$base64Audio"
            }
        }
        """.trimIndent()
        WebSocketUtil.sendMessage(message)
    }
}
