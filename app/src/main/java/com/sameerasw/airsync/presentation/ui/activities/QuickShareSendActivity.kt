package com.sameerasw.airsync.presentation.ui.activities

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sameerasw.airsync.R
import com.sameerasw.airsync.presentation.viewmodel.AirSyncViewModel
import com.sameerasw.airsync.service.OutboundQuickShareService
import com.sameerasw.airsync.ui.theme.AirSyncTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

class QuickShareSendActivity : ComponentActivity() {

    private val _windowFocus = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val uris = extractUris(intent)

        setContent {
            val viewModel: AirSyncViewModel = viewModel { AirSyncViewModel.create(this@QuickShareSendActivity) }
            val uiState by viewModel.uiState.collectAsState()

            AirSyncTheme(pitchBlackTheme = uiState.isPitchBlackThemeEnabled) {
                QuickShareSendScreen(
                    hasWindowFocus = _windowFocus.value,
                    uris = uris,
                    pairedDeviceName = uiState.lastConnectedDevice?.name,
                    onFinished = { finish() }
                )
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        _windowFocus.value = hasFocus
    }

    private fun extractUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                listOfNotNull(uri)
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                val list: ArrayList<out Parcelable>? =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                    }
                list?.filterIsInstance<Uri>() ?: emptyList()
            }

            else -> emptyList()
        }
    }
}

@OptIn(ExperimentalAnimationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun QuickShareSendScreen(
    hasWindowFocus: Boolean,
    uris: List<Uri>,
    pairedDeviceName: String?,
    onFinished: () -> Unit
) {
    val context = LocalContext.current
    var uiState by remember { mutableStateOf<SendUiState>(SendUiState.Loading) }
    var hasStarted by remember { mutableStateOf(false) }

    LaunchedEffect(hasWindowFocus) {
        if (hasWindowFocus && !hasStarted) {
            hasStarted = true
            delay(100)

            if (uris.isEmpty()) {
                uiState = SendUiState.Error("Nothing to send")
                delay(1500)
                onFinished()
            } else if (pairedDeviceName.isNullOrBlank()) {
                uiState = SendUiState.Error("No Mac paired")
                delay(1500)
                onFinished()
            } else {
                val localUris = withContext(Dispatchers.IO) { copyUrisToCache(context, uris) }
                if (localUris.isEmpty()) {
                    uiState = SendUiState.Error("Couldn't read the file")
                    delay(1500)
                    onFinished()
                } else {
                    OutboundQuickShareService.start(context, localUris)
                    uiState = SendUiState.Success
                    delay(1200)
                    onFinished()
                }
            }
        }
    }

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
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_laptop_24),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )

                val label = when (uiState) {
                    is SendUiState.Loading -> "Sending to ${pairedDeviceName ?: stringResource(R.string.your_mac)}..."
                    is SendUiState.Success -> "Sending to ${pairedDeviceName ?: stringResource(R.string.your_mac)}"
                    is SendUiState.Error -> (uiState as SendUiState.Error).message
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )

                AnimatedContent(
                    targetState = uiState,
                    transitionSpec = { fadeIn().togetherWith(fadeOut()) },
                    label = "StatusAnimation"
                ) { state ->
                    Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                        when (state) {
                            is SendUiState.Loading -> LoadingIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.primary
                            )

                            is SendUiState.Success -> Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = "Success",
                                modifier = Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )

                            is SendUiState.Error -> Icon(
                                imageVector = Icons.Rounded.Error,
                                contentDescription = "Error",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

sealed class SendUiState {
    data object Loading : SendUiState()
    data object Success : SendUiState()
    data class Error(val message: String) : SendUiState()
}

private fun copyUrisToCache(context: Context, uris: List<Uri>): List<Uri> {
    val outDir = File(context.cacheDir, "quickshare_outgoing").apply { mkdirs() }
    return uris.mapNotNull { uri ->
        try {
            var name = "file_${System.currentTimeMillis()}"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIdx >= 0) {
                    cursor.getString(nameIdx)?.let { name = it }
                }
            }

            var dest = File(outDir, name)
            var counter = 1
            while (dest.exists()) {
                val base = name.substringBeforeLast(".")
                val ext = name.substringAfterLast(".", "")
                dest = File(outDir, if (ext.isEmpty()) "$base ($counter)" else "$base ($counter).$ext")
                counter++
            }

            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: return@mapNotNull null

            Uri.fromFile(dest)
        } catch (e: Exception) {
            Log.e("QuickShareSendActivity", "Failed to cache $uri", e)
            null
        }
    }
}

@Preview(name = "Loading", showBackground = true)
@Composable
private fun QuickShareSendScreenPreviewLoading() {
    AirSyncTheme {
        QuickShareSendScreen(
            hasWindowFocus = true,
            uris = emptyList(),
            pairedDeviceName = "Sameera's Mac",
            onFinished = {}
        )
    }
}
