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
fun AirSyncLoadingAnimation(
    isPlus: Boolean = false,
    modifier: Modifier = Modifier
) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.logo_motion))
    
    val primaryColor = MaterialTheme.colorScheme.primary.toArgb()
    val secondaryColor = (if (isPlus) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant).toArgb()

    val dynamicProperties = rememberLottieDynamicProperties(
        rememberLottieDynamicProperty(
            property = LottieProperty.COLOR,
            value = primaryColor,
            keyPath = arrayOf("android_logo", "**", "android_fill")
        ),
        rememberLottieDynamicProperty(
            property = LottieProperty.COLOR,
            value = secondaryColor,
            keyPath = arrayOf("outer_ring", "**", "ring_fill")
        )
    )

    LottieAnimation(
        composition = composition,
        iterations = LottieConstants.IterateForever,
        dynamicProperties = dynamicProperties,
        modifier = modifier
    )
}
