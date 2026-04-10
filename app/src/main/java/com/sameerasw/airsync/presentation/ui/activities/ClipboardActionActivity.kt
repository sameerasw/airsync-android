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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.ReceiptLong
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
import com.sameerasw.airsync.presentation.viewmodel.AirSyncViewModel
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
            val viewModel: com.sameerasw.airsync.presentation.viewmodel.AirSyncViewModel =
                androidx.lifecycle.viewmodel.compose.viewModel {
                    com.sameerasw.airsync.presentation.viewmodel.AirSyncViewModel.create(this@ClipboardActionActivity)
                }
            val uiState by viewModel.uiState.collectAsState()

            AirSyncTheme(pitchBlackTheme = uiState.isPitchBlackThemeEnabled) {
                ClipboardActionScreen(
                    hasWindowFocus = _windowFocus.value,
                    isShareAction = intent?.action == android.content.Intent.ACTION_SEND,
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
fun ClipboardActionScreen(
    hasWindowFocus: Boolean,
    isShareAction: Boolean,
    onFinished: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val dataStoreManager = remember { DataStoreManager.getInstance(context) }
    val connectedDevice by dataStoreManager.getLastConnectedDevice().collectAsState(initial = null)

    var uiState by remember { mutableStateOf<ClipboardUiState>(ClipboardUiState.Loading) }
    var hasAttemptedSync by remember { mutableStateOf(false) }

    ClipboardActionScreenContent(
        uiState = uiState,
        connectedDevice = connectedDevice,
        isShareAction = isShareAction,
        onFinished = onFinished
    )

    LaunchedEffect(hasWindowFocus) {
        if (hasWindowFocus && !hasAttemptedSync) {
            hasAttemptedSync = true
            delay(100)

            try {
                // If this is a share action, extract text from intent
                val activity = context as? android.app.Activity
                val intent = activity?.intent
                val sharedText = if (isShareAction) {
                    intent?.getStringExtra(android.content.Intent.EXTRA_TEXT)
                } else {
                    null
                }

                // Fallback to clipboard only if not a share action or shared text is empty
                val textToSync = sharedText ?: ClipboardUtil.getClipboardText(context)

                if (!textToSync.isNullOrEmpty()) {
                    ClipboardSyncManager.syncTextToDesktop(textToSync)
                    uiState = ClipboardUiState.Success
                    delay(1200)
                    onFinished()
                } else {
                    uiState = ClipboardUiState.Error(
                        if (isShareAction) "Shared text empty" else "Clipboard empty"
                    )
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
    isShareAction: Boolean,
    onFinished: () -> Unit
) {
    // Transparent background that dismisses on click
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onFinished),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(bottom = 64.dp)
                .padding(horizontal = 24.dp),
            shape = RoundedCornerShape(percent = 50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 8.dp,
            shadowElevation = 12.dp
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
            ) {
                // Device Icon
                Icon(
                    painter = painterResource(id = R.drawable.ic_laptop_24),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )

                // Device Name
                Text(
                    text = connectedDevice?.name ?: stringResource(R.string.your_mac),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Divider or Space if needed, but spacing is enough
                
                // Status Icon / Loading Indicator
                AnimatedContent(
                    targetState = uiState,
                    transitionSpec = { fadeIn().togetherWith(fadeOut()) },
                    label = "StatusAnimation"
                ) { state ->
                    Box(
                        modifier = Modifier.size(28.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        when (state) {
                            is ClipboardUiState.Loading -> {
                                LoadingIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            is ClipboardUiState.Success -> {
                                Icon(
                                    imageVector = androidx.compose.material.icons.Icons.Rounded.CheckCircle,
                                    contentDescription = "Success",
                                    modifier = Modifier.size(28.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }

                            is ClipboardUiState.Error -> {
                                Icon(
                                    imageVector = androidx.compose.material.icons.Icons.Rounded.Error,
                                    contentDescription = "Error",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            
                            else -> {
                                // Default/Idle icon
                                Icon(
                                    imageVector = if (isShareAction) 
                                        androidx.compose.material.icons.Icons.Rounded.ReceiptLong 
                                    else 
                                        androidx.compose.material.icons.Icons.Rounded.ContentPaste,
                                    contentDescription = "Sync",
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
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
            isShareAction = false,
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
            isShareAction = false,
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
            isShareAction = false,
            onFinished = {})
    }
}
