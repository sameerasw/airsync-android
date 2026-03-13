package com.sameerasw.airsync.presentation.ui.components

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.sameerasw.airsync.R
import com.sameerasw.airsync.utils.HapticUtil
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan2

@Composable
fun RotatingAppIcon(
    modifier: Modifier = Modifier,
    haptics: androidx.compose.ui.hapticfeedback.HapticFeedback,
    hasTriggeredEasterEgg: Boolean = false,
    onEasterEggTriggered: () -> Unit = {},
    isVisible: Boolean = true
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rotationAnimatable = remember { Animatable(0f) }

    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val gravitySensor = remember { sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }
    var accumulatedRotation by remember { mutableFloatStateOf(0f) }
    var lastAngle by remember { mutableFloatStateOf(0f) }
    val minorStep = 10f
    var lastHapticRotation by remember { mutableFloatStateOf(0f) }

    var smoothedAx by remember { mutableFloatStateOf(0f) }
    var smoothedAy by remember { mutableFloatStateOf(9.8f) }
    val alpha = 0.1f

    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, isVisible) {
        if (!isVisible) {
            onDispose { }
        } else {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                        val ax = event.values[0]
                        val ay = event.values[1]

                        smoothedAx = smoothedAx + alpha * (ax - smoothedAx)
                        smoothedAy = smoothedAy + alpha * (ay - smoothedAy)

                        val tiltMagnitudeSqr = smoothedAx * smoothedAx + smoothedAy * smoothedAy
                        if (tiltMagnitudeSqr < 2.0f) return

                        val targetAngle = (atan2(smoothedAx.toDouble(), smoothedAy.toDouble()) * 180 / PI).toFloat()

                        var delta = targetAngle - lastAngle
                        if (delta > 180) delta -= 360
                        if (delta < -180) delta += 360

                        accumulatedRotation += delta
                        lastAngle = targetAngle

                        if (kotlin.math.abs(accumulatedRotation - lastHapticRotation) >= minorStep) {
                            HapticUtil.performLightTick(haptics)
                            lastHapticRotation = accumulatedRotation
                        }

                        if (!hasTriggeredEasterEgg && kotlin.math.abs(accumulatedRotation) >= 3600f) {
                            onEasterEggTriggered()
                            val rickRollUrl = "https://youtu.be/dQw4w9WgXcQ"
                            val intent = Intent(Intent.ACTION_VIEW, rickRollUrl.toUri())
                            context.startActivity(intent)
                        }

                        scope.launch {
                            rotationAnimatable.animateTo(
                                accumulatedRotation,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessLow
                                )
                            )
                        }
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }

            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        sensorManager.registerListener(listener, gravitySensor, SensorManager.SENSOR_DELAY_UI)
                    }
                    Lifecycle.Event.ON_PAUSE -> {
                        sensorManager.unregisterListener(listener)
                    }
                    else -> {}
                }
            }

            lifecycleOwner.lifecycle.addObserver(observer)
            // Initial register if already resumed
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                sensorManager.registerListener(listener, gravitySensor, SensorManager.SENSOR_DELAY_UI)
            }

            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                sensorManager.unregisterListener(listener)
            }
        }
    }

    Image(
        painter = painterResource(id = R.drawable.ic_launcher_foreground),
        contentDescription = null,
        modifier = modifier
            .graphicsLayer {
                rotationZ = rotationAnimatable.value
            }
    )
}
