package com.sameerasw.airsync.presentation.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.rounded.Keyboard
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
import androidx.compose.ui.res.painterResource
import com.sameerasw.airsync.R

enum class ShiftState {
    OFF,
    ON,
    LOCKED
}

data class ModifierStatus(
    val active: Boolean = false
)

data class KeyboardModifiers(
    val shift: ModifierStatus = ModifierStatus(),
    val ctrl: ModifierStatus = ModifierStatus(),
    val option: ModifierStatus = ModifierStatus(),
    val command: ModifierStatus = ModifierStatus(),
    val fn: ModifierStatus = ModifierStatus()
)

object MacKeycodes {
    private val keycodeMap = mapOf(
        'a' to 0, 's' to 1, 'd' to 2, 'f' to 3, 'h' to 4, 'g' to 5, 'z' to 6, 'x' to 7, 'c' to 8, 'v' to 9,
        'b' to 11, 'q' to 12, 'w' to 13, 'e' to 14, 'r' to 15, 'y' to 16, 't' to 17, '1' to 18, '2' to 19,
        '3' to 20, '4' to 21, '6' to 22, '5' to 23, '=' to 24, '9' to 25, '7' to 26, '-' to 27, '8' to 28,
        '0' to 29, ']' to 30, 'o' to 31, 'u' to 32, '[' to 33, 'i' to 34, 'p' to 35, 'l' to 37, 'j' to 38,
        '\'' to 39, 'k' to 40, ';' to 41, '\\' to 42, ',' to 43, '/' to 44, 'n' to 45, 'm' to 46, '.' to 47,
        ' ' to 49, '`' to 50, '!' to 18, '@' to 19, '#' to 20, '$' to 21, '%' to 23, '^' to 22, '&' to 26,
        '*' to 28, '(' to 25, ')' to 29, '_' to 27, '+' to 24, '{' to 33, '}' to 30, '|' to 42, ':' to 41,
        '\"' to 39, '<' to 43, '>' to 47, '?' to 44
    )

    fun getKeyCode(char: Char): Int? = keycodeMap[char.lowercaseChar()]

    const val ENTER = 36
    const val BACKSPACE = 51
    const val ESCAPE = 53
    const val SPACE = 49
    const val FN = 63
    const val LEFT = 123
    const val RIGHT = 124
    const val DOWN = 125
    const val UP = 126
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun KeyboardInputSheet(
    onDismiss: () -> Unit,
    onType: (String, Boolean) -> Unit, // Boolean: isSystemKeyboard
    onKeyPress: (Int, Boolean) -> Unit, // Boolean: isSystemKeyboard
    modifiers: KeyboardModifiers = KeyboardModifiers(),
    onToggleModifier: (String) -> Unit = {}
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
            // Shared rows visible in both modes
            NavigationRow(
                onKeyPress = { onKeyPress(it, isSystemKeyboard) },
                onToggleKeyboard = { isSystemKeyboard = !isSystemKeyboard }
            )
            Spacer(modifier = Modifier.height(4.dp))
            ModifierRow(
                modifiers = modifiers,
                onToggleModifier = onToggleModifier,
                onKeyPress = { onKeyPress(it, isSystemKeyboard) }
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (isSystemKeyboard) {
                // System Keyboard mode
                SystemInputArea(onType, onKeyPress)
            } else {
                // Custom/AirSync Keyboard
                CustomKeyboard(
                    onType = { onType(it, false) },
                    onKeyPress = { onKeyPress(it, false) },
                    onSwitchToSystem = { isSystemKeyboard = true },
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
                    onKeyPress(MacKeycodes.BACKSPACE, true)
                } else if (newText.length > text.length) {
                    val added = newText.drop(text.length)
                    if (added == "\n") {
                        onKeyPress(MacKeycodes.ENTER, true)
                    } else if (added.isNotEmpty()) {
                        onType(added, true)
                    }
                }
                text = sentinel
            },
            modifier = Modifier
                .fillMaxWidth()
                .alpha(0f)
                .height(0.dp)
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
            .padding(horizontal = 4.dp)
            .pointerInput(Unit) {
                detectDragGestures { _, _ -> 
                    // Consume drag gestures on the keyboard to prevent accidental sheet dismissal
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Shared rows (Arrows and Modifiers) are now managed in KeyboardInputSheet

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
                            val keycode = MacKeycodes.getKeyCode(char.first())
                            if (keycode != null) {
                                onKeyPress(keycode)
                            } else {
                                onType(char)
                            }
                        },
                        interactionSource = numInteraction,
                        colors = IconButtonDefaults.iconButtonVibrantColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurface
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
                            val keycode = MacKeycodes.getKeyCode(displayLabel.first())
                            if (keycode != null) {
                                onKeyPress(keycode)
                            } else {
                                onType(displayLabel)
                            }
                            if (shiftState == ShiftState.ON) shiftState = ShiftState.OFF
                        },
                        interactionSource = row1Interaction,
                        colors = IconButtonDefaults.iconButtonVibrantColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurface
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
                            color = MaterialTheme.colorScheme.onSurface
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
                            val keycode = MacKeycodes.getKeyCode(displayLabel.first())
                            if (keycode != null) {
                                onKeyPress(keycode)
                            } else {
                                onType(displayLabel)
                            }
                            if (shiftState == ShiftState.ON) shiftState = ShiftState.OFF
                        },
                        interactionSource = row2Interaction,
                        colors = IconButtonDefaults.iconButtonVibrantColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurface
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
                            color = MaterialTheme.colorScheme.onSurface
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
                            val keycode = MacKeycodes.getKeyCode(displayLabel.first())
                            if (keycode != null) {
                                onKeyPress(keycode)
                            } else {
                                onType(displayLabel)
                            }
                            if (shiftState == ShiftState.ON) shiftState = ShiftState.OFF
                        },
                        interactionSource = row3Interaction,
                        colors = IconButtonDefaults.iconButtonVibrantColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurface
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
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // Backspace Key
                val backspaceInteraction = remember { MutableInteractionSource() }
                FilledTonalIconButton(
                    onClick = {
                        performLightHaptic()
                        onKeyPress(MacKeycodes.BACKSPACE)
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
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurface
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
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Space
                val spaceInteraction = remember { MutableInteractionSource() }

                Box(
                    modifier = Modifier
                        .weight(4f)
                        .fillMaxHeight()
                        .animateWidth(spaceInteraction)
                        .clickable(
                            onClick = {
                                performLightHaptic()
                                onKeyPress(MacKeycodes.SPACE)
                            },
                            interactionSource = spaceInteraction,
                            indication = null
                        )
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Keyboard,
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
                        onKeyPress(MacKeycodes.ENTER)
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


@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NavigationRow(
    onKeyPress: (Int) -> Unit,
    onToggleKeyboard: () -> Unit
) {
    val view = LocalView.current
    fun performLightHaptic() {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    ButtonGroup(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        content = {
            val navKeys = listOf(
                Triple("LEFT", Icons.AutoMirrored.Filled.KeyboardArrowLeft, MacKeycodes.LEFT),
                Triple("DOWN", Icons.Default.KeyboardArrowDown, MacKeycodes.DOWN),
                Triple("UP", Icons.Default.KeyboardArrowUp, MacKeycodes.UP),
                Triple("RIGHT", Icons.AutoMirrored.Filled.KeyboardArrowRight, MacKeycodes.RIGHT)
            )

            // Keyboard Toggle Button
            val kbInteraction = remember { MutableInteractionSource() }
            FilledTonalIconButton(
                onClick = {
                    performLightHaptic()
                    onToggleKeyboard()
                },
                interactionSource = kbInteraction,
                colors = IconButtonDefaults.iconButtonVibrantColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier
                    .weight(0.5f)
                    .fillMaxHeight()
                    .animateWidth(kbInteraction),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Keyboard,
                    contentDescription = "Switch Keyboard",
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            
            navKeys.forEach { (name, icon, keycode) ->
                val interaction = remember { MutableInteractionSource() }
                FilledTonalIconButton(
                    onClick = {
                        performLightHaptic()
                        onKeyPress(keycode)
                    },
                    interactionSource = interaction,
                    colors = IconButtonDefaults.iconButtonVibrantColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .animateWidth(interaction),
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = name,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

        }
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ModifierRow(
    modifiers: KeyboardModifiers,
    onToggleModifier: (String) -> Unit,
    onKeyPress: (Int) -> Unit
) {
    val view = LocalView.current
    fun performLightHaptic() {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    ButtonGroup(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        content = {
            // Esc key
            val escInteraction = remember { MutableInteractionSource() }
            FilledTonalIconButton(
                onClick = {
                    performLightHaptic()
                    onKeyPress(MacKeycodes.ESCAPE)
                },
                interactionSource = escInteraction,
                colors = IconButtonDefaults.iconButtonVibrantColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier
                    .weight(1.2f)
                    .fillMaxHeight()
                    .animateWidth(escInteraction),
            ) {
                Text(
                    "esc",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            val modifierList = listOf(
                Triple("fn", "fn", modifiers.fn),
                Triple("shift", R.drawable.key_shift, modifiers.shift),
                Triple("ctrl", R.drawable.key_control, modifiers.ctrl),
                Triple("option", R.drawable.key_option, modifiers.option),
                Triple("command", R.drawable.key_command, modifiers.command)
            )
            
            modifierList.forEach { (type, iconOrText, status) ->
                val interaction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .animateWidth(interaction)
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            if (status.active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                        .combinedClickable(
                            onClick = {
                                performLightHaptic()
                                onToggleModifier(type)
                            },
                            interactionSource = interaction,
                            indication = null
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (iconOrText is Int) {
                            Icon(
                                painter = painterResource(id = iconOrText),
                                contentDescription = type,
                                modifier = Modifier.size(24.dp),
                                tint = if (status.active) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurface
                            )
                        } else {
                            Text(
                                text = iconOrText.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (status.active) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    )
}
