package com.sameerasw.airsync.island

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.sameerasw.airsync.R

@OptIn(ExperimentalTextApi::class)
private fun flexFont(weight: Int) = Font(
    R.font.google_sans_flex,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.Setting("ROND", 100f)),
)

val IslandFontFamily = FontFamily(flexFont(400), flexFont(500), flexFont(600), flexFont(700))
