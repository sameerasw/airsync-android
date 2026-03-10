package com.sameerasw.airsync.presentation.ui.components.sliders

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.platform.LocalHapticFeedback
import com.sameerasw.airsync.R
import com.sameerasw.airsync.utils.HapticUtil
import java.math.BigDecimal
import java.math.RoundingMode

@Composable
fun ConfigSliderItem(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    increment: Float = 0.1f,
    onValueChangeFinished: (() -> Unit)? = null,
    enabled: Boolean = true
) {

    val haptics = LocalHapticFeedback.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceBright,
                shape = RoundedCornerShape(MaterialTheme.shapes.extraSmall.bottomEnd)
            )
            .padding(top = 16.dp, start = 16.dp, end = 16.dp, bottom = 8.dp)
    ) {
        var sliderValue by remember(value) { mutableFloatStateOf(value) }
        val view = LocalView.current

        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    val newValue = (BigDecimal.valueOf(sliderValue.toDouble())
                        .subtract(BigDecimal.valueOf(increment.toDouble()))
                        .setScale(2, RoundingMode.HALF_UP))
                        .toFloat()
                    val clamped = newValue.coerceIn(valueRange)
                    sliderValue = clamped
                    onValueChange(clamped)
                    onValueChangeFinished?.invoke()
                    HapticUtil.performClick(haptics)
                },
                modifier = Modifier.padding(end = 4.dp),
                enabled = enabled
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.rounded_remove_24),
                    contentDescription = "Decrease",
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            Slider(
                value = sliderValue,
                onValueChange = {
                    sliderValue = it
                    HapticUtil.performLightTick(haptics)
                },
                valueRange = valueRange,
                steps = steps,
                onValueChangeFinished = {
                    onValueChange(sliderValue)
                    onValueChangeFinished?.invoke()
                },
                modifier = Modifier.weight(1f),
                enabled = enabled
            )

            IconButton(
                onClick = {
                    val newValue = (BigDecimal.valueOf(sliderValue.toDouble())
                        .add(BigDecimal.valueOf(increment.toDouble()))
                        .setScale(2, RoundingMode.HALF_UP))
                        .toFloat()
                    val clamped = newValue.coerceIn(valueRange)
                    sliderValue = clamped
                    onValueChange(clamped)
                    onValueChangeFinished?.invoke()
                    HapticUtil.performClick(haptics)
                },
                modifier = Modifier.padding(start = 4.dp),
                enabled = enabled
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.rounded_add_24),
                    contentDescription = "Increase",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
