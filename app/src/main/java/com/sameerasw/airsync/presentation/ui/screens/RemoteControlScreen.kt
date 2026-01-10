package com.sameerasw.airsync.presentation.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sameerasw.airsync.R
import com.sameerasw.airsync.utils.HapticUtil
import com.sameerasw.airsync.utils.WebSocketUtil
import com.sameerasw.airsync.utils.WebSocketMessageHandler
import com.sameerasw.airsync.utils.MacDeviceStatusManager
import kotlinx.coroutines.launch
import org.json.JSONObject
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import com.sameerasw.airsync.presentation.ui.components.RoundedCardContainer

@Composable
fun RemoteControlScreen(
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    
    // Volume state (0-100)
    var volume by remember { mutableFloatStateOf(50f) }
    var isMuted by remember { mutableStateOf(false) }

    // Observe Mac Status
    val macStatus by MacDeviceStatusManager.macDeviceStatus.collectAsState()
    val musicInfo = macStatus?.music
    
    // Use the centrally managed bitmap flow
    val albumArtBitmap by MacDeviceStatusManager.albumArt.collectAsState()

    // Listen for volume updates from Mac
    DisposableEffect(Unit) {
        val callback = { newVolume: Int ->
            volume = newVolume.toFloat()
        }
        WebSocketMessageHandler.setOnMacVolumeCallback(callback)
        onDispose {
            WebSocketMessageHandler.setOnMacVolumeCallback(null)
        }
    }

    fun sendRemoteAction(action: String, value: Any? = null) {
        scope.launch {
            try {
                HapticUtil.performClick(haptics)
                val json = JSONObject()
                json.put("type", "remoteControl")
                val data = JSONObject()
                data.put("action", action)
                if (value != null) {
                    data.put("value", value)
                }
                json.put("data", data)
                WebSocketUtil.sendMessage(json.toString())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {


        RoundedCardContainer {
            // Now Playing Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Background Image (Album Art)
                    if (albumArtBitmap != null) {
                        androidx.compose.foundation.Image(
                            bitmap = albumArtBitmap!!.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .matchParentSize()
                                .blur(8.dp),
                            contentScale = ContentScale.Crop
                        )
                        // Dark scrim for readability
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
                        )
                    }
                    Column(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        // Album Art (Foreground) & Info
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // Metadata
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = musicInfo?.title?.takeIf { it.isNotEmpty() }
                                        ?: "Nothing Playing",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (albumArtBitmap != null) Color.White else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = musicInfo?.artist?.takeIf { it.isNotEmpty() }
                                        ?: "from your Mac",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (albumArtBitmap != null) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // Media Controls
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilledTonalIconButton(
                                onClick = { sendRemoteAction("media_prev") },
                                modifier = Modifier.size(56.dp),
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = if (albumArtBitmap != null) Color.White.copy(
                                        alpha = 0.2f
                                    ) else MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = if (albumArtBitmap != null) Color.White else MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            ) {
                                Icon(
                                    Icons.Rounded.SkipPrevious,
                                    "Previous",
                                    modifier = Modifier.size(32.dp)
                                )
                            }

                            FilledIconButton(
                                onClick = { sendRemoteAction("media_play_pause") },
                                modifier = Modifier.size(72.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = if (albumArtBitmap != null) Color.White else MaterialTheme.colorScheme.primary,
                                    contentColor = if (albumArtBitmap != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                Icon(
                                    imageVector = if (musicInfo?.isPlaying == true) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = "Play/Pause",
                                    modifier = Modifier.size(40.dp)
                                )
                            }

                            FilledTonalIconButton(
                                onClick = { sendRemoteAction("media_next") },
                                modifier = Modifier.size(56.dp),
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = if (albumArtBitmap != null) Color.White.copy(
                                        alpha = 0.2f
                                    ) else MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = if (albumArtBitmap != null) Color.White else MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            ) {
                                Icon(
                                    Icons.Rounded.SkipNext,
                                    "Next",
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    }
                }
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        IconButton(onClick = {
                            sendRemoteAction("toggleMute")
                            isMuted = !isMuted
                        }) {
                            Icon(
                                imageVector = if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                contentDescription = "Mute"
                            )
                        }

                        Slider(
                            value = volume,
                            onValueChange = {
                                volume = it
                                sendRemoteAction("vol_set", it.toInt())
                            },
                            valueRange = 0f..100f,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }


        Spacer(modifier = Modifier.height(16.dp))

        // D-Pad and Navigation
        Box(
            modifier = Modifier
                .size(240.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerLow, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            // Up
            RemoteButton(
                onClick = { sendRemoteAction("arrow_up") },
                icon = Icons.Default.ArrowUpward,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp)
            )
            
            // Down
            RemoteButton(
                onClick = { sendRemoteAction("arrow_down") },
                icon = Icons.Default.ArrowDownward,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
            )
            
            // Left
            RemoteButton(
                onClick = { sendRemoteAction("arrow_left") },
                icon = Icons.Default.ArrowBack,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp)
            )
            
            // Right
            RemoteButton(
                onClick = { sendRemoteAction("arrow_right") },
                icon = Icons.Default.ArrowForward,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp)
            )
            
            // Center (OK/Enter)
            FilledTonalIconButton(
                onClick = { sendRemoteAction("enter") },
                modifier = Modifier.size(64.dp)
            ) {
                Icon(Icons.Default.Circle, "Enter", modifier = Modifier.size(24.dp))
            }
        }

        // Extra Keys
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            OutlinedButton(onClick = { sendRemoteAction("escape") }) {
                Text("Esc")
            }
            
            FilledTonalButton(onClick = { sendRemoteAction("space") }) {
                Icon(Icons.Default.SpaceBar, "Space", modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Space")
            }
            
            OutlinedButton(onClick = { sendRemoteAction("backspace") }) {
                Icon(Icons.Default.Backspace, "Backspace")
            }
        }
    }
}

@Composable
fun RemoteButton(
    onClick: () -> Unit,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = modifier.size(56.dp)
    ) {
        Icon(icon, contentDescription = null)
    }
}
