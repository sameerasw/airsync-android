package com.sameerasw.airsync.presentation.ui.screens

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sameerasw.airsync.R
import com.sameerasw.airsync.presentation.ui.components.KeyboardInputSheet
import com.sameerasw.airsync.presentation.ui.components.KeyboardModifiers
import com.sameerasw.airsync.presentation.ui.components.ModifierStatus
import com.sameerasw.airsync.presentation.ui.components.RoundedCardContainer
import com.sameerasw.airsync.utils.HapticUtil
import com.sameerasw.airsync.utils.WebSocketUtil
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RemoteControlScreen(
    modifier: Modifier = Modifier,
    showKeyboard: Boolean,
    onDismissKeyboard: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    LocalContext.current

    fun sendRemoteAction(
        action: String,
        value: Any? = null,
        extras: Map<String, Any> = emptyMap(),
        performHaptic: Boolean = true
    ) {
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

    var activeModifiers by remember { mutableStateOf(setOf<String>()) }

    val moveChannel = remember { Channel<Offset>(Channel.UNLIMITED) }
    val scrollChannel = remember { Channel<Offset>(Channel.UNLIMITED) }

    // movement batching at 100Hz with smoothing
    LaunchedEffect(Unit) {

        var pendingMove = Offset.Zero
        var pendingScroll = Offset.Zero
        var lastSentMove = Offset.Zero
        val smoothing = 0.7f // 0-1, higher = smoother but more lag

        while (true) {
            delay(10)

            // Conserving moves
            while (true) {
                val poll = moveChannel.tryReceive().getOrNull() ?: break
                pendingMove += poll
            }
            if (pendingMove != Offset.Zero) {
                // Apply subtle smoothing (Low-pass filter)
                val smoothedX = pendingMove.x * (1f - smoothing) + lastSentMove.x * smoothing
                val smoothedY = pendingMove.y * (1f - smoothing) + lastSentMove.y * smoothing

                sendRemoteAction(
                    "mouse_move",
                    extras = mapOf(
                        "dx" to smoothedX.toDouble(),
                        "dy" to smoothedY.toDouble()
                    ),
                    performHaptic = false
                )
                lastSentMove = Offset(smoothedX.toFloat(), smoothedY.toFloat())
                pendingMove = Offset.Zero
            }

            // Conserving scrolls
            while (true) {
                val poll = scrollChannel.tryReceive().getOrNull() ?: break
                pendingScroll += poll
            }
            if (pendingScroll != Offset.Zero) {
                sendRemoteAction(
                    "mouse_scroll",
                    extras = mapOf(
                        "dx" to pendingScroll.x.toDouble(),
                        "dy" to pendingScroll.y.toDouble()
                    ),
                    performHaptic = false
                )
                pendingScroll = Offset.Zero
            }
        }
    }

    val modifiers = remember(activeModifiers) {
        KeyboardModifiers(
            shift = ModifierStatus(activeModifiers.contains("shift")),
            ctrl = ModifierStatus(activeModifiers.contains("ctrl")),
            option = ModifierStatus(activeModifiers.contains("option")),
            command = ModifierStatus(activeModifiers.contains("command")),
            fn = ModifierStatus(activeModifiers.contains("fn"))
        )
    }


    val handleType = remember(scope) {
        { text: String, fromSystem: Boolean, mods: List<String> ->
            sendRemoteAction(
                "type",
                extras = mapOf(
                    "text" to text,
                    "modifiers" to mods
                ),
                performHaptic = !fromSystem
            )
        }
    }

    val handleKeyPress = remember(scope) {
        { code: Int, fromSystem: Boolean, mods: List<String> ->
            sendRemoteAction(
                "keypress",
                extras = mapOf(
                    "keycode" to code,
                    "modifiers" to mods
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
            onDismiss = onDismissKeyboard,
            onType = handleType,
            onKeyPress = handleKeyPress,
            onClearModifiers = handleClearModifiers,
            modifiers = modifiers,
            onToggleModifier = handleToggleModifier
        )
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {

        // Extra Keys
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            maxItemsInEachRow = 3
        ) {
            OutlinedButton(
                onClick = { sendRemoteAction("escape") },
                modifier = Modifier.padding(horizontal = 4.dp)
            ) {
                Text("Esc")
            }

            OutlinedButton(
                onClick = { sendRemoteAction("space") },
                modifier = Modifier.padding(horizontal = 4.dp)
            ) {
                Icon(Icons.Default.SpaceBar, "Space", modifier = Modifier.size(18.dp))
            }
        }

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
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
            )

            // Down
            RemoteButton(
                onClick = { sendRemoteAction("arrow_down") },
                icon = Icons.Default.ArrowDownward,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
            )

            // Left
            RemoteButton(
                onClick = { sendRemoteAction("arrow_left") },
                icon = Icons.Default.ArrowBack,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 16.dp)
            )

            // Right
            RemoteButton(
                onClick = { sendRemoteAction("arrow_right") },
                icon = Icons.Default.ArrowForward,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp)
            )

            // Center (OK/Enter)
            FilledTonalIconButton(
                onClick = { sendRemoteAction("enter") },
                modifier = Modifier.size(64.dp)
            ) {
                Icon(Icons.Default.Circle, "Enter", modifier = Modifier.size(24.dp))
            }
        }

        // Trackpad Area
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 400.dp, max = 600.dp)
                .pointerInput(Unit) {
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
                                val prevCenter =
                                    (change1.previousPosition + change2.previousPosition) / 2f
                                val scrollDelta = currentCenter - prevCenter
                                val scrollDist = scrollDelta.getDistance()

                                if (scrollDist > 0.5f) {
                                    totalMoved += scrollDist
                                    totalHapticDistance += scrollDist
                                    if (totalHapticDistance > hapticInterval) {
                                        performLightHaptic()
                                        totalHapticDistance = 0f
                                    }
                                    scrollChannel.trySend(scrollDelta * 2f)
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
                                            moveChannel.trySend(delta * 1.8f)
                                        }
                                    }
                                    change.consume()
                                } else {
                                    // Finger lifted
                                    if (isTwoFinger) {
                                        if (totalMoved < 30f) {
                                            performLightHaptic()
                                            sendRemoteAction(
                                                "mouse_click",
                                                extras = mapOf(
                                                    "button" to "right",
                                                    "isDown" to true
                                                )
                                            )
                                            scope.launch {
                                                delay(50)
                                                sendRemoteAction(
                                                    "mouse_click",
                                                    extras = mapOf(
                                                        "button" to "right",
                                                        "isDown" to false
                                                    )
                                                )
                                            }
                                        }
                                    } else {
                                        if (totalMoved < dragThreshold) {
                                            // CLICK
                                            performLightHaptic()
                                            sendRemoteAction(
                                                "mouse_click",
                                                extras = mapOf(
                                                    "button" to "left",
                                                    "isDown" to true
                                                )
                                            )
                                            scope.launch {
                                                delay(50)
                                                sendRemoteAction(
                                                    "mouse_click",
                                                    extras = mapOf(
                                                        "button" to "left",
                                                        "isDown" to false
                                                    )
                                                )
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
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            )
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(id = R.drawable.rounded_drag_click_24),
                    contentDescription = null,
                    modifier = Modifier
                        .size(48.dp)
                        .alpha(0.1f),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }


        Spacer(modifier = Modifier.height(80.dp))
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

