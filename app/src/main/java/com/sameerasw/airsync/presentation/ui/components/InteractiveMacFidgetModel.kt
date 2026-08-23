package com.sameerasw.airsync.presentation.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.android.filament.gltfio.FilamentInstance
import com.sameerasw.airsync.domain.model.ConnectedDevice
import com.sameerasw.airsync.utils.DevicePreviewResolver
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.SceneView
import io.github.sceneview.SurfaceType
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMainLightNode
import io.github.sceneview.rememberModelLoader
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val DEFAULT_ROTATION_Y = 0f
private const val DEFAULT_ROTATION_X = 12f

@Composable
fun InteractiveMacFidgetModel(
    modifier: Modifier = Modifier,
    modelPath: String = "models/macbook.glb",
    scaleToUnits: Float = 1.55f,
    isConnected: Boolean = true,
    onModelLoadFailed: (() -> Unit)? = null
) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)

    var modelInstance by remember { mutableStateOf<FilamentInstance?>(null) }
    var modelNodeRef by remember { mutableStateOf<ModelNode?>(null) }
    var isReadyForFadeIn by remember { mutableStateOf(false) }

    val alphaAnim by animateFloatAsState(
        targetValue = if (isReadyForFadeIn) 1f else 0f,
        animationSpec = tween(durationMillis = 600),
        label = "MacModelFadeIn"
    )

    // Keyframe Animation Progress (0f = start / disconnected closed, 1f = end / connected open)
    val animationProgress = remember { Animatable(if (isConnected) 1f else 0f) }

    // Smooth auto-centering animation states
    val coroutineScope = rememberCoroutineScope()
    val animRotationY = remember { Animatable(DEFAULT_ROTATION_Y) }
    val animRotationX = remember { Animatable(DEFAULT_ROTATION_X) }
    var autoCenterJob by remember { mutableStateOf<Job?>(null) }

    val cameraNode = rememberCameraNode(engine) {
        position = Float3(x = 0f, y = 0.35f, z = 2.2f)
        lookAt(Float3(0f, 0f, 0f))
    }

    val mainLight = rememberMainLightNode(engine) {
        intensity = 80_000.0f
        isShadowCaster = false
    }

    // Load 3D model instance asynchronously
    LaunchedEffect(modelPath) {
        try {
            val instance = modelLoader.loadModelInstance(modelPath)
            if (instance != null) {
                modelInstance = instance
                delay(80)
                isReadyForFadeIn = true
            } else {
                onModelLoadFailed?.invoke()
            }
        } catch (e: Exception) {
            onModelLoadFailed?.invoke()
        }
    }

    // Handle forward / reverse keyframe transitions on connect/disconnect
    LaunchedEffect(isConnected, modelNodeRef) {
        val target = if (isConnected) 1f else 0f
        animationProgress.animateTo(
            targetValue = target,
            animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing)
        )
    }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        autoCenterJob?.cancel()
                    },
                    onDrag = { change: PointerInputChange, dragAmount ->
                        change.consume()
                        coroutineScope.launch {
                            val newY = (animRotationY.value + dragAmount.x * 0.6f)
                            val newX = (animRotationX.value + dragAmount.y * 0.35f).coerceIn(-25f, 40f)
                            animRotationY.snapTo(newY)
                            animRotationX.snapTo(newX)
                        }
                    },
                    onDragEnd = {
                        autoCenterJob = coroutineScope.launch {
                            val targetY = kotlin.math.round(animRotationY.value / 360f) * 360f
                            launch {
                                animRotationY.animateTo(
                                    targetValue = targetY,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessLow
                                    )
                                )
                            }
                            launch {
                                animRotationX.animateTo(
                                    targetValue = DEFAULT_ROTATION_X,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessLow
                                    )
                                )
                            }
                        }
                    },
                    onDragCancel = {
                        autoCenterJob = coroutineScope.launch {
                            val targetY = kotlin.math.round(animRotationY.value / 360f) * 360f
                            animRotationY.animateTo(targetY, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow))
                            animRotationX.animateTo(DEFAULT_ROTATION_X, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow))
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        SceneView(
            modifier = Modifier
                .fillMaxSize()
                .alpha(alphaAnim),
            surfaceType = SurfaceType.TextureSurface,
            engine = engine,
            modelLoader = modelLoader,
            cameraNode = cameraNode,
            mainLightNode = mainLight,
            isOpaque = false,
            onFrame = {
                modelNodeRef?.animator?.let { animator ->
                    val count = animator.animationCount
                    for (i in 0 until count) {
                        val duration = animator.getAnimationDuration(i)
                        animator.applyAnimation(i, animationProgress.value * duration)
                    }
                    animator.updateBoneMatrices()
                }
            },
            content = {
                modelInstance?.let { instance ->
                    ModelNode(
                        modelInstance = instance,
                        scaleToUnits = scaleToUnits,
                        centerOrigin = Float3(0f, 0f, 0f),
                        rotation = Float3(animRotationX.value, animRotationY.value, 0f),
                        apply = {
                            isShadowCaster = false
                            isShadowReceiver = false
                            playingAnimations.clear()
                            modelNodeRef = this
                        }
                    )
                }
            }
        )
    }
}

@Composable
fun MacDevicePreview(
    connectedDevice: ConnectedDevice?,
    modifier: Modifier = Modifier,
    is3dEnabled: Boolean = true,
    isConnected: Boolean = true,
    isPageVisible: Boolean = true,
    height: Dp = 290.dp
) {
    var has3dError by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
        contentAlignment = Alignment.Center
    ) {
        if (is3dEnabled && isPageVisible && !has3dError) {
            InteractiveMacFidgetModel(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height),
                modelPath = "models/macbook.glb",
                isConnected = isConnected,
                onModelLoadFailed = {
                    has3dError = true
                }
            )
        } else {
            val previewRes = DevicePreviewResolver.getPreviewRes(connectedDevice)
            Image(
                painter = painterResource(id = previewRes),
                contentDescription = "Connected Mac preview",
                modifier = Modifier.fillMaxWidth(0.85f),
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(androidx.compose.material3.MaterialTheme.colorScheme.primary)
            )
        }
    }
}
