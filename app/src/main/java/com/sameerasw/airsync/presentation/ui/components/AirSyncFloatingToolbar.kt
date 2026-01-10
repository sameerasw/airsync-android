package com.sameerasw.airsync.presentation.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.FloatingToolbarScrollBehavior
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.sameerasw.airsync.presentation.ui.models.AirSyncTab
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AirSyncFloatingToolbar(
    modifier: Modifier = Modifier,
    currentPage: Int,
    tabs: List<AirSyncTab>,
    onTabSelected: (Int) -> Unit,
    scrollBehavior: FloatingToolbarScrollBehavior
) {
    var interactionCount by remember { mutableStateOf(0) }

    // Track which tab was just selected for bump animation
    var bumpingTab by remember { mutableIntStateOf(-1) }
    var bumpKey by remember { mutableIntStateOf(0) }

    // Reset bump animation after delay
    LaunchedEffect(bumpKey) {
        if (bumpingTab >= 0) {
            delay(200)
            bumpingTab = -1
        }
    }

    HorizontalFloatingToolbar(
        modifier = modifier,
        expanded = true,
        scrollBehavior = scrollBehavior,
        colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
        content = {
            // FIXED ORDER LOOP to prevent shifting
            tabs.forEachIndexed { index, tab ->
                val isSelected = currentPage == index
                val isBumping = bumpingTab == index

                // Animate scale for non-selected tabs when collapsing/expanding
                val itemScale by animateFloatAsState(
                    targetValue = when {
                        isBumping -> 1.28f // Subtle bump animation when selected
                        isSelected -> 1.2f
                        else -> 1.0f // Normal scale
                    },
                    animationSpec = spring(
                        dampingRatio = if (isBumping) Spring.DampingRatioMediumBouncy else Spring.DampingRatioLowBouncy,
                        stiffness = if (isBumping) Spring.StiffnessHigh else Spring.StiffnessLow
                    ),
                    label = "item_scale_$index"
                )

                // Animate alpha for smooth fade
                val itemAlpha = 1f

                // Animate width for spacing
                val itemWidth = 48.dp

                // Animate spacer width
                val spacerWidth = if (index < tabs.size - 1) 16.dp else 0.dp

                // Always render the button
                IconButton(
                    onClick = {
                        interactionCount++
                        bumpingTab = index
                        bumpKey++ 
                        onTabSelected(index)
                    },
                    modifier = Modifier
                        .width(itemWidth)
                        .height(48.dp)
                        .graphicsLayer {
                            scaleX = itemScale
                            scaleY = itemScale
                            alpha = itemAlpha
                        },
                    colors = if (isSelected) {
                        IconButtonDefaults.filledTonalIconButtonColors()
                    } else {
                        IconButtonDefaults.iconButtonColors()
                    }
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.title,
                        tint = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.background
                        },
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Spacing between buttons
                if (index < tabs.size - 1) {
                    Spacer(modifier = Modifier.width(spacerWidth))
                }
            }
        }
    )
}
