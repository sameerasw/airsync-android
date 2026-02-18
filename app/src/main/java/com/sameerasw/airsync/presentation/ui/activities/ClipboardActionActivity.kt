package com.sameerasw.airsync.presentation.ui.activities

import android.app.Activity

import android.widget.Toast
import android.graphics.drawable.ColorDrawable



import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.with
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sameerasw.airsync.utils.ClipboardSyncManager
import com.sameerasw.airsync.utils.ClipboardUtil
import kotlinx.coroutines.delay

import com.sameerasw.airsync.ui.theme.AirSyncTheme
import com.sameerasw.airsync.utils.HapticUtil

import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.layout.navigationBarsPadding

class ClipboardActionActivity : ComponentActivity() {

    private val _windowFocus =  mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Standard Edge-to-Edge with explicit transparent bars
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )

        // Ensure background is transparent
        window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))

        setContent {
            AirSyncTheme {
                ClipboardActionScreen(
                    hasWindowFocus = _windowFocus.value,
                    onFinished = { finish() }
                )
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        _windowFocus.value = hasFocus
    }
}

@OptIn(ExperimentalAnimationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable

fun ClipboardActionScreen(hasWindowFocus: Boolean, onFinished: () -> Unit) {
    var uiState by remember { mutableStateOf<ClipboardUiState>(ClipboardUiState.Loading) }
    var hasAttemptedSync by remember { mutableStateOf(false) }

    // Transparent background that dismisses on click
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.2f))

           .navigationBarsPadding()
            .clickable(onClick = onFinished),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.padding(24.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp
        ) {
            Box(
                modifier = Modifier
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = uiState,
                    transitionSpec = { fadeIn().togetherWith(fadeOut()) },
                    label = "ClipboardStateAnimation"
                ) { state ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        when (state) {
                            is ClipboardUiState.Loading -> {
                               LoadingIndicator(
                                    modifier = Modifier.size(48.dp),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "Syncing...",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            is ClipboardUiState.Success -> {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = "Success",
                                    tint = Color(0xFF4CAF50), // Nice Green
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "Clipboard Sent!",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            is ClipboardUiState.Error -> {
                                Icon(
                                    imageVector = Icons.Rounded.Error,
                                    contentDescription = "Error",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = state.message,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(hasWindowFocus) {
        if (hasWindowFocus && !hasAttemptedSync) {
            hasAttemptedSync = true
            // Small delay to ensure system considers us "interacted" if needed, 
            // though focus should be enough.
            delay(100) 
            
            try {
                val clipboardText = ClipboardUtil.getClipboardText(context)
                
                if (!clipboardText.isNullOrEmpty()) {
                    ClipboardSyncManager.syncTextToDesktop(clipboardText)
                    uiState = ClipboardUiState.Success
                    delay(1200) // Show success for 1.2s
                    onFinished()
                } else {
                    uiState = ClipboardUiState.Error("Clipboard empty")
                    delay(1500)
                    onFinished()
                }
            } catch (e: Exception) {
                uiState = ClipboardUiState.Error("Failed")
                delay(1500)
                onFinished()
            }
        }
    }
}

sealed class ClipboardUiState {
    data object Loading : ClipboardUiState()
    data object Success : ClipboardUiState()
    data class Error(val message: String) : ClipboardUiState()
}


