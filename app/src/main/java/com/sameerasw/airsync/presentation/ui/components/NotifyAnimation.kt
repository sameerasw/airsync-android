package com.sameerasw.airsync.presentation.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import com.airbnb.lottie.compose.rememberLottieDynamicProperties
import com.airbnb.lottie.compose.rememberLottieDynamicProperty
import com.sameerasw.airsync.R

@Composable
fun NotifyAnimation(
    isPlus: Boolean = false,
    modifier: Modifier = Modifier
) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.notify_motion))

    val primaryColor = MaterialTheme.colorScheme.primary.toArgb()
    val secondaryColor = MaterialTheme.colorScheme.onSurface.toArgb()

    val dynamicProperties = rememberLottieDynamicProperties(
        // Primary fill for all layers as base
        rememberLottieDynamicProperty(
            property = LottieProperty.COLOR,
            value = primaryColor,
            keyPath = arrayOf("**")
        ),
        // Secondary fill for notification-mac (animated popup)
        rememberLottieDynamicProperty(
            property = LottieProperty.COLOR,
            value = secondaryColor,
            keyPath = arrayOf("notification-mac", "**")
        ),
        // Secondary fill for notification-mobile (animated popup)
        rememberLottieDynamicProperty(
            property = LottieProperty.COLOR,
            value = secondaryColor,
            keyPath = arrayOf("notification-mobile", "**")
        )
    )

    LottieAnimation(
        composition = composition,
        iterations = LottieConstants.IterateForever,
        dynamicProperties = dynamicProperties,
        modifier = modifier
    )
}
