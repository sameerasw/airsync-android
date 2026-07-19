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
fun ClipAnimation(
    modifier: Modifier = Modifier
) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.clip_motion))

    val primaryColor = MaterialTheme.colorScheme.primary.toArgb()
    val accentColor = MaterialTheme.colorScheme.onSurface.toArgb()

    val dynamicProperties = rememberLottieDynamicProperties(
        // Primary fill for all static layers as base
        rememberLottieDynamicProperty(
            property = LottieProperty.COLOR,
            value = primaryColor,
            keyPath = arrayOf("**")
        ),
        // Accent fill for the animated clipboard item strips (cb-animated layers in nested comps)
        rememberLottieDynamicProperty(
            property = LottieProperty.COLOR,
            value = accentColor,
            keyPath = arrayOf("cb-animated", "**")
        )
    )

    LottieAnimation(
        composition = composition,
        iterations = LottieConstants.IterateForever,
        dynamicProperties = dynamicProperties,
        modifier = modifier
    )
}
