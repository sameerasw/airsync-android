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
fun FileAnimation(
    modifier: Modifier = Modifier
) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.file_motion))

    val primaryColor = MaterialTheme.colorScheme.primary.toArgb()
    val accentColor = MaterialTheme.colorScheme.onSurface.toArgb()

    val dynamicProperties = rememberLottieDynamicProperties(
        // Primary fill for all static layers
        rememberLottieDynamicProperty(
            property = LottieProperty.COLOR,
            value = primaryColor,
            keyPath = arrayOf("**")
        ),
        // Accent for the single animated layer (file/media flying in)
        rememberLottieDynamicProperty(
            property = LottieProperty.COLOR,
            value = accentColor,
            keyPath = arrayOf("file-animated", "**")
        )
    )

    LottieAnimation(
        composition = composition,
        iterations = LottieConstants.IterateForever,
        dynamicProperties = dynamicProperties,
        modifier = modifier
    )
}
