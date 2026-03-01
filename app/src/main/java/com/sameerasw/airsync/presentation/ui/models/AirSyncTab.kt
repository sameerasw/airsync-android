package com.sameerasw.airsync.presentation.ui.models

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector

data class AirSyncTab(
    @StringRes val title: Int,
    val icon: ImageVector,
    val index: Int
)
