package com.sameerasw.airsync.presentation.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.ClipDescription
import android.view.DragEvent
import android.util.Log
import com.sameerasw.airsync.domain.model.ClipboardEntry
import com.sameerasw.airsync.utils.HapticUtil
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.Job

@Composable
fun ClipboardScreen(
    clipboardHistory: List<ClipboardEntry>,
    isConnected: Boolean,
    onSendText: (String) -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    var inputText by remember { mutableStateOf("") }
    var isDraggingOver by remember { mutableStateOf(false) }
    val lazyListState = rememberLazyListState()
    val view = LocalView.current
    val haptics = LocalHapticFeedback.current
    var dragHapticsJob by remember { mutableStateOf<Job?>(null) }

    // Haptic feedback effect - continuous tick while dragging
    LaunchedEffect(isDraggingOver) {
        if (isDraggingOver && isConnected) {
            dragHapticsJob = HapticUtil.startLoadingHaptics(haptics)
        } else {
            dragHapticsJob?.cancel()
            dragHapticsJob = null
        }
    }

    // Auto-scroll to newest message when clipboard history changes
    LaunchedEffect(clipboardHistory.size) {
        if (clipboardHistory.isNotEmpty()) {
            lazyListState.animateScrollToItem(0)
        }
    }

    // Helper function to extract text from drag event
    fun handleDroppedContent(event: DragEvent): Boolean {
        return try {
            if (event.clipData != null && event.clipData.itemCount > 0) {
                val item = event.clipData.getItemAt(0)
                val text = when {
                    item.text != null -> item.text.toString()
                    item.uri != null -> item.uri.toString()
                    else -> null
                }
                if (!text.isNullOrBlank()) {
                    Log.d("ClipboardScreen", "Dropped text: ${text.take(50)}")
                    onSendText(text)
                    true
                } else {
                    false
                }
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e("ClipboardScreen", "Error handling dropped content: ${e.message}", e)
            false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        // Set up drag listener for the entire view
        LaunchedEffect(Unit) {
            view.setOnDragListener { _, event ->
                when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED -> {
                        // Check if the dragged data is text
                        event.clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
                        event.clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
                    }
                    DragEvent.ACTION_DRAG_ENTERED -> {
                        isDraggingOver = true
                        true
                    }
                    DragEvent.ACTION_DRAG_LOCATION -> true
                    DragEvent.ACTION_DRAG_EXITED -> {
                        isDraggingOver = false
                        true
                    }
                    DragEvent.ACTION_DROP -> {
                        isDraggingOver = false
                        handleDroppedContent(event)
                    }
                    DragEvent.ACTION_DRAG_ENDED -> {
                        isDraggingOver = false
                        true
                    }
                    else -> false
                }
            }
        }

        // Drag and drop overlay
        if (isDraggingOver && isConnected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .wrapContentHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Surface(
                        modifier = Modifier
                            .size(80.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Send,
                            contentDescription = "Drop to send",
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }

                    // Text feedback
                    Text(
                        text = "Drop to send",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        }

        // Scrollable Message Area - takes remaining space
        Box(modifier = Modifier.weight(1f)) {
            // History List or Empty State
            if (clipboardHistory.isEmpty()) {
                // Show "Nothing shared yet" when connected but no history
                if (isConnected) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Nothing shared yet",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    reverseLayout = true
                ) {
                    items(
                        items = clipboardHistory,
                        key = { entry -> entry.id }
                    ) { entry ->
                        ClipboardEntryBubble(
                            entry = entry,
                            onBubbleTap = {
                                clipboardManager.setText(AnnotatedString(entry.text))
                            }
                        )
                    }
                }
            }
        }

        // Chat Input Box - always visible at bottom when connected
        if (isConnected) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(),
                shape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp, bottomStart = 0.dp, bottomEnd = 0.dp),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 18.dp, max = 100.dp),
                        placeholder = {
                            Text(
                                text = "Type a message or drag text here...",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedContainerColor = MaterialTheme.colorScheme.surface
                        ),
                        singleLine = false,
                        maxLines = 4
                    )

                    OutlinedIconButton(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                onSendText(inputText)
                                inputText = ""
                            } else {
                                // Clear history when input is empty
                                onClearHistory()
                            }
                        },
                        modifier = Modifier
                            .size(60.dp)
                            .background(
                                color = MaterialTheme.colorScheme.background,
                                shape = CircleShape
                            ),
                        enabled = inputText.isNotBlank() || clipboardHistory.isNotEmpty()
                    ) {
                        Icon(
                            imageVector = if (inputText.isNotBlank()) {
                                Icons.AutoMirrored.Rounded.Send
                            } else {
                                Icons.Rounded.Delete
                            },
                            contentDescription = if (inputText.isNotBlank()) "Send" else "Clear history",
                            tint = if (inputText.isNotBlank()) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ClipboardEntryBubble(
    entry: ClipboardEntry,
    onBubbleTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isSent = !entry.isFromPc
    val bubbleColor = if (isSent) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val textColor = if (isSent) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val timeString = remember(entry.timestamp) {
        timeFormat.format(Date(entry.timestamp))
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = if (isSent) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        // For received messages, show time first (on left)
        if (isSent) {
            Text(
                text = timeString,
                style = MaterialTheme.typography.labelSmall,
                color = textColor.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        // Create custom shape: very rounded except on the bottom corner opposite to sender
        val bubbleShape = if (isSent) {
            // Sent message: flat bottom-right, rounded everywhere else
            RoundedCornerShape(
                topStart = 28.dp,
                topEnd = 28.dp,
                bottomStart = 28.dp,
                bottomEnd = 4.dp
            )
        } else {
            // Received message: flat bottom-left, rounded everywhere else
            RoundedCornerShape(
                topStart = 28.dp,
                topEnd = 28.dp,
                bottomStart = 4.dp,
                bottomEnd = 28.dp
            )
        }

        Surface(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .wrapContentHeight()
                .clickable { onBubbleTap() },
            shape = bubbleShape,
            color = bubbleColor
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = entry.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // For sent messages, show time after (on left after reversing)
        if (!isSent) {
            Text(
                text = timeString,
                style = MaterialTheme.typography.labelSmall,
                color = textColor.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}
