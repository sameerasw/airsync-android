package com.sameerasw.airsync.presentation.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.TouchApp
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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.composed
import com.sameerasw.airsync.presentation.ui.components.RoundedCardContainer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import com.sameerasw.airsync.presentation.ui.components.KeyboardInputSheet
import com.sameerasw.airsync.presentation.ui.components.KeyboardModifiers
import com.sameerasw.airsync.presentation.ui.components.ModifierStatus
import kotlinx.coroutines.delay
import org.json.JSONArray

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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

    fun sendRemoteAction(action: String, value: Any? = null, extras: Map<String, Any> = emptyMap(), performHaptic: Boolean = true) {
        scope.launch {
            try {
                if (performHaptic) {
                    HapticUtil.performLightTick(haptics)
                }
                val json = JSONObject()
                json.put("type", "remoteControl")
                val data = JSONObject()
                data.put("action", action)
                if (value != null) {
                    data.put("value", value)
                }
                extras.forEach { (k, v) ->
                    if (v is List<*>) {
                        data.put(k, JSONArray(v))
                    } else {
                        data.put(k, v)
                    }
                }
                json.put("data", data)
                WebSocketUtil.sendMessage(json.toString())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun performLightHaptic() {
        HapticUtil.performLightTick(haptics)
    }

    var showKeyboard by remember { mutableStateOf(false) }
    var isMouseMode by remember { mutableStateOf(false) }
    var activeModifiers by remember { mutableStateOf(setOf<String>()) }

    val modifiers = remember(activeModifiers) {
        KeyboardModifiers(
            shift = ModifierStatus(activeModifiers.contains("shift")),
            ctrl = ModifierStatus(activeModifiers.contains("ctrl")),
            option = ModifierStatus(activeModifiers.contains("option")),
            command = ModifierStatus(activeModifiers.contains("command")),
            fn = ModifierStatus(activeModifiers.contains("fn"))
        )
    }

    // Memoize the callbacks to prevent unnecessary recompositions in KeyboardInputSheet
    val handleType = remember(scope, activeModifiers) {
        { text: String, fromSystem: Boolean ->
            sendRemoteAction(
                "type",
                extras = mapOf(
                    "text" to text,
                    "modifiers" to activeModifiers.toList()
                ),
                performHaptic = !fromSystem
            )
        }
    }

    val handleKeyPress = remember(scope, activeModifiers) {
        { code: Int, fromSystem: Boolean ->
            sendRemoteAction(
                "keypress",
                extras = mapOf(
                    "keycode" to code,
                    "modifiers" to activeModifiers.toList()
                ),
                performHaptic = !fromSystem
            )
        }
    }

    val handleToggleModifier = remember {
        { modifier: String ->
            activeModifiers = if (activeModifiers.contains(modifier)) {
                activeModifiers - modifier
            } else {
                activeModifiers + modifier
            }
        }
    }

    val handleClearModifiers = remember {
        {
            activeModifiers = emptySet()
        }
    }


    if (showKeyboard) {
        KeyboardInputSheet(
            onDismiss = { showKeyboard = false },
            onType = handleType,
            onKeyPress = handleKeyPress,
            onClearModifiers = handleClearModifiers,
            modifiers = modifiers,
            onToggleModifier = handleToggleModifier
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
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
                        Image(
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
                                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.45f))
                        )
                    }
                    Column(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(32.dp)
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

                        // ButtonGroup
                        val isBuffering = false // Placeholder
                        val playWhenReady = true // Placeholder

                        ButtonGroup(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(60.dp)
                                .padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            content = {
                            // Previous Button
                            val prevInteraction = remember { MutableInteractionSource() }
                            FilledTonalIconButton(
                                onClick = { sendRemoteAction("media_prev") },
                                interactionSource = prevInteraction,
                                modifier = Modifier
                                    .weight(0.7f)
                                    .fillMaxHeight()
                                    .animateWidth(prevInteraction),
                            ) {
                                Icon(
                                    Icons.Rounded.SkipPrevious,
                                    contentDescription = "Previous",
                                    modifier = Modifier.size(36.dp)
                                )
                            }

                            // Play/Pause Button
                            val playInteraction = remember { MutableInteractionSource() }
                            FilledIconButton(
                                onClick = { sendRemoteAction("media_play_pause") },
                                interactionSource = playInteraction,
                                modifier = Modifier
                                    .weight(1.5f)
                                    .fillMaxHeight()
                                    .animateWidth(playInteraction)
                            ) {
                                if (isBuffering && playWhenReady && !(musicInfo?.isPlaying == true)) {
                                    LoadingIndicator()
                                } else {
                                    Icon(
                                        imageVector = if (musicInfo?.isPlaying == true) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                        contentDescription = if (musicInfo?.isPlaying == true) "Pause" else "Play",
                                        modifier = Modifier.size(48.dp)
                                    )
                                }
                            }

                            // Next Button
                            val nextInteraction = remember { MutableInteractionSource() }
                            FilledTonalIconButton(
                                onClick = { sendRemoteAction("media_next") },
                                interactionSource = nextInteraction,
                                modifier = Modifier
                                    .weight(0.7f)
                                    .fillMaxHeight()
                                    .animateWidth(nextInteraction),
                            ) {
                                Icon(
                                    Icons.Rounded.SkipNext,
                                    contentDescription = "Next",
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }
                        )
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
                            sendRemoteAction("vol_mute")
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

        if (isMouseMode) {
            // Trackpad Area
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 16.dp)
                    .pointerInput(isMouseMode) {
                        if (!isMouseMode) return@pointerInput
                        
                        awaitEachGesture {
                            val firstDown = awaitFirstDown()
                            var totalMoved = 0f
                            var totalHapticDistance = 0f
                            var lastPosition = firstDown.position
                            var isTwoFinger = false
                            
                            val dragThreshold = 10f
                            val hapticInterval = 25f

                            while (true) {
                                val event = awaitPointerEvent()
                                val pointers = event.changes
                                
                                if (pointers.size >= 2) {
                                    isTwoFinger = true
                                    val change1 = pointers[0]
                                    val change2 = pointers[1]
                                    
                                    val currentCenter = (change1.position + change2.position) / 2f
                                    val prevCenter = (change1.previousPosition + change2.previousPosition) / 2f
                                    val scrollDelta = currentCenter - prevCenter
                                    val scrollDist = scrollDelta.getDistance()
                                    
                                    if (scrollDist > 0.5f) {
                                        totalMoved += scrollDist
                                        totalHapticDistance += scrollDist
                                        if (totalHapticDistance > hapticInterval) {
                                            performLightHaptic()
                                            totalHapticDistance = 0f
                                        }
                                        
                                        sendRemoteAction(
                                            "mouse_scroll",
                                            extras = mapOf(
                                                "dx" to scrollDelta.x.toDouble() * 2.0,
                                                "dy" to scrollDelta.y.toDouble() * 2.0
                                            ),
                                            performHaptic = false
                                        )
                                    }
                                    pointers.forEach { it.consume() }
                                } else if (pointers.size == 1) {
                                    val change = pointers[0]
                                    if (change.pressed) {
                                        val delta = change.position - lastPosition
                                        val dist = delta.getDistance()
                                        lastPosition = change.position
                                        
                                        if (!isTwoFinger) {
                                            if (dist > 0.1f) {
                                                totalMoved += dist
                                                totalHapticDistance += dist
                                                if (totalHapticDistance > hapticInterval) {
                                                    performLightHaptic()
                                                    totalHapticDistance = 0f
                                                }
                                                
                                                sendRemoteAction(
                                                    "mouse_move",
                                                    extras = mapOf(
                                                        "dx" to (delta.x * 1.8f).toDouble(),
                                                        "dy" to (delta.y * 1.8f).toDouble()
                                                    ),
                                                    performHaptic = false
                                                )
                                            }
                                        }
                                        change.consume()
                                    } else {
                                        // Finger lifted
                                        if (isTwoFinger) {
                                            if (totalMoved < 30f) {
                                                performLightHaptic()
                                                sendRemoteAction("mouse_click", extras = mapOf("button" to "right", "isDown" to true))
                                                scope.launch {
                                                    delay(50)
                                                    sendRemoteAction("mouse_click", extras = mapOf("button" to "right", "isDown" to false))
                                                }
                                            }
                                        } else {
                                            if (totalMoved < dragThreshold) {
                                                // CLICK
                                                performLightHaptic()
                                                sendRemoteAction("mouse_click", extras = mapOf("button" to "left", "isDown" to true))
                                                scope.launch {
                                                    delay(50)
                                                    sendRemoteAction("mouse_click", extras = mapOf("button" to "left", "isDown" to false))
                                                }
                                            }
                                        }
                                        break
                                    }
                                } else {
                                    break
                                }
                            }
                        }
                    },
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(id = R.drawable.rounded_drag_click_24),
                        contentDescription = null,
                        modifier = Modifier.size(48.dp).alpha(0.1f),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        } else {
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
        }

        // Extra Keys
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = { sendRemoteAction("escape") },
                modifier = Modifier.weight(1f)
            ) {
                Text("Esc")
            }
            
           OutlinedButton(
               onClick = { sendRemoteAction("space") },
               modifier = Modifier.weight(1f)
           ) {
                Icon(Icons.Default.SpaceBar, "Space", modifier = Modifier.size(18.dp))
            }
            // Mouse Toggle
            OutlinedButton(
                onClick = { isMouseMode = !isMouseMode },
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    painter = if (isMouseMode) painterResource(id = R.drawable.rounded_drag_click_24) else painterResource(id = R.drawable.rounded_gamepad_circle_up_24),
                    contentDescription = if (isMouseMode) "Touchpad Mode" else "Mouse Mode",
                    modifier = Modifier.size(20.dp)
                )
            }

            OutlinedButton(
                onClick = { showKeyboard = true },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Keyboard, "Keyboard")
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

