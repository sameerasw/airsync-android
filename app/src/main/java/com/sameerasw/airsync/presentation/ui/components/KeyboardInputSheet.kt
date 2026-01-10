package com.sameerasw.airsync.presentation.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource

enum class ShiftState {
    OFF,
    ON,
    LOCKED
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun KeyboardInputSheet(
    onDismiss: () -> Unit,
    onType: (String, Boolean) -> Unit, // Boolean: isSystemKeyboard
    onKeyPress: (Int, Boolean) -> Unit // Boolean: isSystemKeyboard
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surfaceContainer
    ) {
        var isSystemKeyboard by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .navigationBarsPadding()
                .imePadding()
        ) {
            if (isSystemKeyboard) {
                // Header is visible in System Keyboard mode
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 0.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "System Keyboard",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    
                    TextButton(onClick = { isSystemKeyboard = false }) {
                        Text("Use AirSync")
                    }
                }
                
                SystemInputArea(onType, onKeyPress)
            } else {
                // Custom/AirSync Keyboard - Headless (header hidden)
                // Switched via Spacebar long-press
                CustomKeyboard(
                    onType = { onType(it, false) },
                    onKeyPress = { onKeyPress(it, false) },
                    onSwitchToSystem = { isSystemKeyboard = true }
                )
            }
        }
    }
}

@Composable
private fun SystemInputArea(
    onType: (String, Boolean) -> Unit,
    onKeyPress: (Int, Boolean) -> Unit
) {
    // Sentinel strategy for System Keyboard
    val sentinel = "\u200B" 
    var text by remember { mutableStateOf(sentinel) }
    val focusRequester = remember { FocusRequester() }
    
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
    
    Box(modifier = Modifier.padding(0.dp)) {
        // Invisible but focused text field
        OutlinedTextField(
            value = text,
            onValueChange = { newText ->
                if (newText.length < text.length) {
                    onKeyPress(51, true) // Mac Delete
                } else if (newText.length > text.length) {
                    val added = newText.drop(text.length)
                    if (added.isNotEmpty()) {
                        onType(added, true)
                    }
                }
                text = sentinel
            },
            modifier = Modifier
                .fillMaxWidth()
                .alpha(0f)
                .height(1.dp)
                .focusRequester(focusRequester),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false)
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CustomKeyboard(
    onType: (String) -> Unit,
    onKeyPress: (Int) -> Unit,
    onSwitchToSystem: () -> Unit
) {
    val view = LocalView.current
    fun performLightHaptic() {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }
    fun performHeavyHaptic() {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    var isSymbols by remember { mutableStateOf(false) }
    var shiftState by remember { mutableStateOf(ShiftState.OFF) }

    // Layers
    val numberRow = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
    
    val row1Letters = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
    val row2Letters = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
    val row3Letters = listOf("z", "x", "c", "v", "b", "n", "m")
    
    val row1Symbols = listOf("!", "@", "#", "$", "%", "^", "&", "*", "(", ")")
    val row2Symbols = listOf("-", "_", "+", "=", "[", "]", "{", "}", "\\", "|")
    // Adjusted row 3 symbols (8 items to roughly match letter row width when no shift)
    val row3Symbols = listOf(";", ":", "'", "\"", ",", ".", "<", ">") 

    val currentRow1 = if (isSymbols) row1Symbols else row1Letters
    val currentRow2 = if (isSymbols) row2Symbols else row2Letters
    val currentRow3 = if (isSymbols) row3Symbols else row3Letters

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .pointerInput(Unit) {
                detectDragGestures { _, _ -> 
                    // Consume drag gestures on the keyboard to prevent accidental sheet dismissal
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Dedicated Number Row
        ButtonGroup(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            content = {
                numberRow.forEach { char ->
                    val numInteraction = remember { MutableInteractionSource() }
                    FilledTonalIconButton(
                        onClick = {
                            performLightHaptic()
                            onType(char)
                        },
                        interactionSource = numInteraction,
                        colors = IconButtonDefaults.iconButtonVibrantColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .animateWidth(numInteraction),
                    ) {
                        Text(
                            text = char,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        )

        // Row 1
        ButtonGroup(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            content = {
                currentRow1.forEach { char ->
                    val displayLabel = if (shiftState != ShiftState.OFF && !isSymbols) char.uppercase() else char
                    val row1Interaction = remember { MutableInteractionSource() }
                    FilledTonalIconButton(
                        onClick = {
                            performLightHaptic()
                            onType(displayLabel)
                            if (shiftState == ShiftState.ON) shiftState = ShiftState.OFF
                        },
                        interactionSource = row1Interaction,
                        colors = IconButtonDefaults.iconButtonVibrantColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .animateWidth(row1Interaction),
                    ) {
                        Text(
                            text = displayLabel,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        )

        // Row 2
        ButtonGroup(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            content = {
                if (!isSymbols) Spacer(modifier = Modifier.weight(0.5f)) // Indent for letters
                currentRow2.forEach { char ->
                    val displayLabel = if (shiftState != ShiftState.OFF && !isSymbols) char.uppercase() else char
                    val row2Interaction = remember { MutableInteractionSource() }
                    FilledTonalIconButton(
                        onClick = {
                            performLightHaptic()
                            onType(displayLabel)
                            if (shiftState == ShiftState.ON) shiftState = ShiftState.OFF
                        },
                        interactionSource = row2Interaction,
                        colors = IconButtonDefaults.iconButtonVibrantColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .animateWidth(row2Interaction),
                    ) {
                        Text(
                            text = displayLabel,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                if (!isSymbols) Spacer(modifier = Modifier.weight(0.5f))
            }
        )

        // Row 3 (with Shift/Backspace logic)
        ButtonGroup(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            content = {
                // Shift Key - Only show if not in symbols mode
                if (!isSymbols) {
                    val shiftInteraction = remember { MutableInteractionSource() }

                    Box(
                        modifier = Modifier
                            .weight(1.5f)
                            .fillMaxHeight()
                            .animateWidth(shiftInteraction)
                            .combinedClickable(
                                onClick = {
                                    performLightHaptic()
                                    shiftState = if (shiftState == ShiftState.OFF) ShiftState.ON else ShiftState.OFF
                                },
                                onLongClick = {
                                    performHeavyHaptic()
                                    shiftState = ShiftState.LOCKED
                                },
                                interactionSource = shiftInteraction,
                                indication = null
                            )
                            .clip(RoundedCornerShape(24.dp))
                            .background(
                                if (shiftState != ShiftState.OFF) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceContainerHighest
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.ArrowUpward,
                            contentDescription = "Shift",
                            modifier = Modifier.size(24.dp),
                            tint = if (shiftState != ShiftState.OFF) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurface
                        )
                    }
                } else {
                     // Spacing balance for symbols mode
                     Spacer(modifier = Modifier.weight(0.5f))
                }

                currentRow3.forEach { char ->
                    val displayLabel = if (shiftState != ShiftState.OFF && !isSymbols) char.uppercase() else char
                    val row3Interaction = remember { MutableInteractionSource() }
                    FilledTonalIconButton(
                        onClick = {
                            performLightHaptic()
                            onType(displayLabel)
                            if (shiftState == ShiftState.ON) shiftState = ShiftState.OFF
                        },
                        interactionSource = row3Interaction,
                        colors = IconButtonDefaults.iconButtonVibrantColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .animateWidth(row3Interaction),
                    ) {
                        Text(
                            text = displayLabel,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }

                // Backspace Key
                val backspaceInteraction = remember { MutableInteractionSource() }
                FilledTonalIconButton(
                    onClick = {
                        performLightHaptic()
                        onKeyPress(51) // Delete
                    },
                    interactionSource = backspaceInteraction,
                    colors = IconButtonDefaults.iconButtonVibrantColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                    modifier = Modifier
                        .weight(1.5f)
                        .fillMaxHeight()
                        .animateWidth(backspaceInteraction),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Backspace,
                        contentDescription = "Backspace",
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        )

        // Row 4 (Sym, Space, Return)
        ButtonGroup(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            content = {
                // Symbols Toggle
                val symInteraction = remember { MutableInteractionSource() }
                FilledTonalIconButton(
                    onClick = {
                        performLightHaptic()
                        isSymbols = !isSymbols
                    },
                    interactionSource = symInteraction,
                    colors = IconButtonDefaults.iconButtonVibrantColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                    modifier = Modifier
                        .weight(1.5f)
                        .fillMaxHeight()
                        .animateWidth(symInteraction),
                ) {
                    Text(
                        text = if (isSymbols) "ABC" else "?#/",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Medium,
                    )
                }

                // Space
                val spaceInteraction = remember { MutableInteractionSource() }

                Box(
                    modifier = Modifier
                        .weight(4f)
                        .fillMaxHeight()
                        .animateWidth(spaceInteraction)
                        .combinedClickable(
                            onClick = {
                                performLightHaptic()
                                onType(" ")
                            },
                            onLongClick = {
                                performHeavyHaptic()
                                onSwitchToSystem()
                            },
                            interactionSource = spaceInteraction,
                            indication = null
                        )
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Keyboard,
                        contentDescription = "Keyboard",
                        modifier = Modifier
                            .size(24.dp)
                            .alpha(0.4f),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Return
                val returnInteraction = remember { MutableInteractionSource() }
                FilledIconButton(
                    onClick = {
                        performLightHaptic()
                        onKeyPress(36) // Return
                    },
                    interactionSource = returnInteraction,
                    modifier = Modifier
                        .weight(1.5f)
                        .fillMaxHeight()
                        .animateWidth(returnInteraction),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardReturn,
                        contentDescription = "Return",
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        )
    }
}


