package com.sameerasw.airsync.presentation.ui.activities

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sameerasw.airsync.R
import com.sameerasw.airsync.data.local.DataStoreManager
import com.sameerasw.airsync.domain.model.ConnectedDevice
import com.sameerasw.airsync.ui.theme.AirSyncTheme
import com.sameerasw.airsync.utils.ClipboardSyncManager
import com.sameerasw.airsync.utils.ClipboardUtil
import com.sameerasw.airsync.utils.DevicePreviewResolver
import kotlinx.coroutines.delay

class ClipboardActionActivity : ComponentActivity() {

    private val _windowFocus = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Standard Edge-to-Edge with explicit transparent bars
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                Color.TRANSPARENT,
                Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.auto(
                Color.TRANSPARENT,
                Color.TRANSPARENT
            )
        )

        // Ensure background is transparent
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

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

@Composable
fun ClipboardActionScreen(hasWindowFocus: Boolean, onFinished: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val dataStoreManager = remember { DataStoreManager.getInstance(context) }
    val connectedDevice by dataStoreManager.getLastConnectedDevice().collectAsState(initial = null)

    var uiState by remember { mutableStateOf<ClipboardUiState>(ClipboardUiState.Loading) }
    var hasAttemptedSync by remember { mutableStateOf(false) }

    ClipboardActionScreenContent(
        uiState = uiState,
        connectedDevice = connectedDevice,
        onFinished = onFinished
    )

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
            } catch (_: Exception) {
                uiState = ClipboardUiState.Error("Failed")
                delay(1500)
                onFinished()
            }
        }
    }
}

@OptIn(ExperimentalAnimationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ClipboardActionScreenContent(
    uiState: ClipboardUiState,
    connectedDevice: ConnectedDevice?,
    onFinished: () -> Unit
) {
    // Transparent background that dismisses on click
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.2f))

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
                    .padding(24.dp),
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
                        // Device preview with overlay
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(bottom = 16.dp)
                        ) {
                            val previewRes = DevicePreviewResolver.getPreviewRes(connectedDevice)
                            Image(
                                painter = painterResource(id = previewRes),
                                contentDescription = "Device Preview",
                                modifier = Modifier.fillMaxWidth(0.9f),
                                contentScale = ContentScale.Fit
                            )

                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .background(
                                        MaterialTheme.colorScheme.surfaceContainerHigh,
                                        shape = CircleShape
                                    )
                            ) {
                                // Overlay icon/indicator
                                when (state) {
                                    is ClipboardUiState.Loading -> {
                                        LoadingIndicator(
                                            modifier = Modifier.size(56.dp),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }

                                    is ClipboardUiState.Success -> {
                                        Icon(
                                            imageVector = Icons.Rounded.CheckCircle,
                                            contentDescription = "Success",
                                            modifier = Modifier.size(56.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }

                                    is ClipboardUiState.Error -> {
                                        Icon(
                                            imageVector = Icons.Rounded.Error,
                                            contentDescription = "Error",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(56.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Text(
                            text = connectedDevice?.name ?: stringResource(R.string.your_mac),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.primary,
                                    shape = RoundedCornerShape(32.dp)
                                )
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Status Text
                        Text(
                            text = when (state) {
                                is ClipboardUiState.Loading -> stringResource(R.string.sending)
                                is ClipboardUiState.Success -> stringResource(R.string.clipboard_sent)
                                is ClipboardUiState.Error -> stringResource(R.string.failed_to_send_clipboard)
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = if (state is ClipboardUiState.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

sealed class ClipboardUiState {
    data object Loading : ClipboardUiState()
    data object Success : ClipboardUiState()
    data class Error(val message: String) : ClipboardUiState()
}

@Preview(name = "Loading State", showBackground = true)
@Composable
private fun ClipboardActionScreenPreviewLoading() {
    AirSyncTheme {
        ClipboardActionScreenContent(
            uiState = ClipboardUiState.Loading,
            connectedDevice = null,
            onFinished = {})
    }
}

@Preview(name = "Success State", showBackground = true)
@Composable
private fun ClipboardActionScreenPreviewSuccess() {
    AirSyncTheme {
        ClipboardActionScreenContent(
            uiState = ClipboardUiState.Success,
            connectedDevice = null,
            onFinished = {})
    }
}

@Preview(name = "Error State", showBackground = true)
@Composable
private fun ClipboardActionScreenPreviewError() {
    AirSyncTheme {
        ClipboardActionScreenContent(
            uiState = ClipboardUiState.Error("Failed to sync"),
            connectedDevice = null,
            onFinished = {})
    }
}
