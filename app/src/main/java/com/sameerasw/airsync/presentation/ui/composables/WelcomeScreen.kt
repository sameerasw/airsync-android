package com.sameerasw.airsync.presentation.ui.composables

import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import com.sameerasw.airsync.R
import com.sameerasw.airsync.presentation.ui.components.HelpAndGuidesContent
import com.sameerasw.airsync.presentation.ui.components.cards.IconToggleItem
import com.sameerasw.airsync.presentation.ui.components.pickers.CrashReportingPicker
import com.sameerasw.airsync.presentation.ui.components.RotatingAppIcon
import com.sameerasw.airsync.presentation.ui.components.RoundedCardContainer
import com.sameerasw.airsync.presentation.viewmodel.AirSyncViewModel
import com.sameerasw.airsync.ui.theme.GoogleSansFlex
import com.sameerasw.airsync.utils.HapticUtil
import com.sameerasw.airsync.utils.DeviceInfoUtil
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan2

enum class OnboardingStep {
    WELCOME,
    ACKNOWLEDGEMENT,
    PREFERENCES,
    FEATURE_INTRODUCTION
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WelcomeScreen(
    viewModel: AirSyncViewModel,
    onBeginClick: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    val uiState by viewModel.uiState.collectAsState()

    var currentStep by remember { mutableStateOf(OnboardingStep.WELCOME) }
    var hasTriggeredEasterEgg by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        AnimatedContent(
            targetState = currentStep,
            transitionSpec = {
                if (targetState.ordinal > initialState.ordinal) {
                    (slideInHorizontally { it } + fadeIn(tween(400)))
                        .togetherWith(slideOutHorizontally { -it } + fadeOut(tween(400)))
                } else {
                    (slideInHorizontally { -it } + fadeIn(tween(400)))
                        .togetherWith(slideOutHorizontally { it } + fadeOut(tween(400)))
                }
            },
            label = "OnboardingTransition"
        ) { step ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                when (step) {
                    OnboardingStep.WELCOME -> {
                        WelcomeStepContent(
                            haptics = haptics,
                            hasTriggeredEasterEgg = hasTriggeredEasterEgg,
                            onEasterEggTriggered = { hasTriggeredEasterEgg = true },
                            onNext = {
                                HapticUtil.performClick(haptics)
                                currentStep = OnboardingStep.ACKNOWLEDGEMENT
                            }
                        )
                    }

                    OnboardingStep.ACKNOWLEDGEMENT -> {
                        AcknowledgementStepContent(
                            haptics = haptics,
                            missingPermissionsCount = uiState.missingPermissions.size,
                            onBack = {
                                HapticUtil.performClick(haptics)
                                currentStep = OnboardingStep.WELCOME
                            },
                            onNext = {
                                HapticUtil.performClick(haptics)
                                currentStep = OnboardingStep.PREFERENCES
                            }
                        )
                    }

                    OnboardingStep.PREFERENCES -> {
                        PreferencesStepContent(
                            haptics = haptics,
                            viewModel = viewModel,
                            uiState = uiState,
                            onBack = {
                                HapticUtil.performClick(haptics)
                                currentStep = OnboardingStep.ACKNOWLEDGEMENT
                            },
                            onNext = {
                                HapticUtil.performClick(haptics)
                                viewModel.setOnboardingCompleted(true)
                                currentStep = OnboardingStep.FEATURE_INTRODUCTION
                            }
                        )
                    }

                    OnboardingStep.FEATURE_INTRODUCTION -> {
                        FeatureIntroStepContent(
                            haptics = haptics,
                            onBack = {
                                HapticUtil.performClick(haptics)
                                currentStep = OnboardingStep.PREFERENCES
                            },
                            onFinish = {
                                HapticUtil.performClick(haptics)
                                onBeginClick()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun WelcomeStepContent(
    haptics: androidx.compose.ui.hapticfeedback.HapticFeedback,
    hasTriggeredEasterEgg: Boolean,
    onEasterEggTriggered: () -> Unit,
    onNext: () -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(modifier = Modifier.statusBarsPadding())

            Spacer(modifier = Modifier.weight(1f))

            RotatingAppIcon(
                haptics = haptics,
                hasTriggeredEasterEgg = hasTriggeredEasterEgg,
                onEasterEggTriggered = onEasterEggTriggered,
                modifier = Modifier.size(240.dp)
            )

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = stringResource(R.string.welcome_title),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = GoogleSansFlex,
                    fontWeight = FontWeight.SemiBold
                ),
                textAlign = TextAlign.Center,
            )

            Text(
                text = stringResource(R.string.welcome_subtitle),
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.weight(1f))

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(100.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(8.dp)
                    .clickable {
                        val websiteUrl = "https://sameerasw.com"
                        val intent = Intent(Intent.ACTION_VIEW, websiteUrl.toUri())
                        context.startActivity(intent)
                    },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(id = R.drawable.avatar),
                    contentDescription = "Developer Avatar",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(100.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = stringResource(R.string.welcome_developer_attribution),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(end = 4.dp)
                )
            }

            Spacer(modifier = Modifier.weight(0.3f))
        }

        Button(
            onClick = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp)
                .height(56.dp)
        ) {
            Text(
                text = stringResource(R.string.action_lets_begin),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.weight(1f))

            Icon(
                painter = painterResource(id = R.drawable.rounded_keyboard_arrow_right_24),
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
fun AcknowledgementStepContent(
    haptics: androidx.compose.ui.hapticfeedback.HapticFeedback,
    missingPermissionsCount: Int,
    onBack: () -> Unit,
    onNext: () -> Unit
) {

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(modifier = Modifier.statusBarsPadding())

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.acknowledgement_title),
            style = MaterialTheme.typography.headlineLarge.copy(
                fontFamily = GoogleSansFlex,
                fontWeight = FontWeight.Bold
            ),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.acknowledgement_desc),
                    style = MaterialTheme.typography.bodyLarge
                )

                Spacer(modifier = Modifier.height(32.dp))


                Text(
                    text = stringResource(R.string.acknowledgement_footer),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        RoundedCardContainer {
            com.sameerasw.airsync.presentation.ui.components.cards.PermissionsCard(
                missingPermissionsCount = missingPermissionsCount
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = {
                    HapticUtil.performClick(haptics)
                    onBack()
                },
                modifier = Modifier.size(56.dp),
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.rounded_arrow_back_24),
                    contentDescription = stringResource(R.string.action_back),
                    modifier = Modifier.size(24.dp)
                )
            }

            Button(
                onClick = onNext,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
            ) {
                Text(
                    text = stringResource(R.string.action_i_understand),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.weight(1f))

                Icon(
                    painter = painterResource(id = R.drawable.rounded_check_24),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
fun FeatureIntroStepContent(
    haptics: androidx.compose.ui.hapticfeedback.HapticFeedback,
    onBack: () -> Unit,
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    val imageLoader = remember {
        ImageLoader.Builder(context)
            .components {
                if (Build.VERSION.SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .build()
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.statusBarsPadding())

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.action_how_to_connect),
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontFamily = GoogleSansFlex,
                    fontWeight = FontWeight.Bold
                ),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.feature_intro_desc),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Start
            )


            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Quick Settings Tiles",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp).fillMaxWidth(),
                textAlign = TextAlign.Start
            )

            RoundedCardContainer {
                com.sameerasw.airsync.presentation.ui.components.cards.QuickSettingsTilesCard(
                    isConnectionTileAdded = com.sameerasw.airsync.utils.QuickSettingsUtil.isQSTileAdded(
                        context,
                        com.sameerasw.airsync.service.AirSyncTileService::class.java
                    ),
                    isClipboardTileAdded = com.sameerasw.airsync.utils.QuickSettingsUtil.isQSTileAdded(
                        context,
                        com.sameerasw.airsync.service.ClipboardTileService::class.java
                    )
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            val configuration = LocalConfiguration.current
            val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val isLargeScreen = configuration.screenWidthDp >= 600


            GifItem(
                modifier = Modifier.fillMaxWidth(),
                imageLoader = imageLoader,
                gifResId = R.drawable.airsync_scan
            )

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = stringResource(R.string.help_guides_title),
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontFamily = GoogleSansFlex,
                    fontWeight = FontWeight.Bold
                ),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            HelpAndGuidesContent()


            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.feature_intro_footer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start
            )

            Spacer(modifier = Modifier.height(24.dp))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = {
                    HapticUtil.performClick(haptics)
                    onBack()
                },
                modifier = Modifier.size(56.dp),
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.rounded_arrow_back_24),
                    contentDescription = stringResource(R.string.action_back),
                    modifier = Modifier.size(24.dp)
                )
            }

            Button(
                onClick = onFinish,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
            ) {
                Text(
                    text = stringResource(R.string.action_let_me_in),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.weight(1f))

                Icon(
                    painter = painterResource(id = R.drawable.rounded_mobile_check_24),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
fun GifItem(
    modifier: Modifier = Modifier,
    imageLoader: ImageLoader,
    gifResId: Int
) {
    Surface(
        modifier = modifier.fillMaxHeight(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(gifResId)
                .crossfade(true)
                .build(),
            imageLoader = imageLoader,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PreferencesStepContent(
    haptics: androidx.compose.ui.hapticfeedback.HapticFeedback,
    viewModel: AirSyncViewModel,
    uiState: com.sameerasw.airsync.domain.model.UiState,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    val context = LocalContext.current
    val deviceInfo by viewModel.deviceInfo.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.statusBarsPadding())

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = stringResource(R.string.preferences_title),
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontFamily = GoogleSansFlex,
                    fontWeight = FontWeight.Bold,
                    fontSize = 36.sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.preferences_desc),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))


            Text(
                text = stringResource(R.string.label_app_settings),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 12.dp, bottom = 8.dp).fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Start
            )

            RoundedCardContainer {
                IconToggleItem(
                    iconRes = R.drawable.rounded_mobile_vibrate_24,
                    title = stringResource(R.string.label_haptic_feedback),
                    isChecked = true,
                    onCheckedChange = { _ ->
                    }
                )
                IconToggleItem(
                    iconRes = R.drawable.rounded_invert_colors_24,
                    title = stringResource(R.string.label_pitch_black_theme),
                    description = stringResource(R.string.subtitle_pitch_black_theme),
                    isChecked = uiState.isPitchBlackThemeEnabled,
                    onCheckedChange = { viewModel.setPitchBlackThemeEnabled(it) }
                )
                val isBlurProblematic = remember { DeviceInfoUtil.isBlurProblematicDevice() }
                IconToggleItem(
                    iconRes = R.drawable.rounded_blur_on_24,
                    title = stringResource(R.string.label_use_blur),
                    description = if (isBlurProblematic) {
                        stringResource(R.string.subtitle_blur_disabled_samsung)
                    } else {
                        stringResource(R.string.subtitle_use_blur)
                    },
                    isChecked = uiState.isBlurEnabled,
                    onCheckedChange = { viewModel.setUseBlurEnabled(it, context) },
                    enabled = !isBlurProblematic
                )
                IconToggleItem(
                    iconRes = R.drawable.rounded_security_24,
                    title = stringResource(R.string.label_error_reporting),
                    description = stringResource(R.string.subtitle_error_reporting),
                    isChecked = uiState.isSentryReportingEnabled,
                    onCheckedChange = { viewModel.setSentryReportingEnabled(it) }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))


            Text(
                text = "Connection",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 12.dp, bottom = 8.dp).fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Start
            )

            RoundedCardContainer {
                com.sameerasw.airsync.presentation.ui.components.cards.DeviceInfoCard(
                    deviceName = uiState.deviceNameInput,
                    localIp = deviceInfo.localIp,
                    onDeviceNameChange = { viewModel.updateDeviceName(it) }
                )

                com.sameerasw.airsync.presentation.ui.components.cards.ExpandNetworkingCard(context)
            }

            Spacer(modifier = Modifier.height(32.dp))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = {
                    HapticUtil.performClick(haptics)
                    onBack()
                },
                modifier = Modifier.size(56.dp),
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.rounded_arrow_back_24),
                    contentDescription = stringResource(R.string.action_back),
                    modifier = Modifier.size(24.dp)
                )
            }

            Button(
                onClick = onNext,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
            ) {
                Text(
                    text = "Continue",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.weight(1f))

                Icon(
                    painter = painterResource(id = R.drawable.rounded_keyboard_arrow_right_24),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
