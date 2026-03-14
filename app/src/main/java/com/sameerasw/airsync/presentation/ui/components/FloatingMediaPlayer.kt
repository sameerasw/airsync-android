package com.sameerasw.airsync.presentation.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import com.sameerasw.airsync.domain.model.MacMusicInfo
import com.sameerasw.airsync.utils.HapticUtil
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class DragValue { Collapsed, Expanded }

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
fun FloatingMediaPlayer(
    musicInfo: MacMusicInfo?,
    albumArtBitmap: Bitmap?,
    volume: Float,
    isMuted: Boolean,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onMediaAction: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val screenHeight = config.screenHeightDp.dp
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    val collapsedHeight = 72.dp
    val expandedHeight = 280.dp
    
    val collapsedPx = with(density) { collapsedHeight.toPx() }
    val expandedPx = with(density) { expandedHeight.toPx() }

    val anchoredDraggableState = remember {
        AnchoredDraggableState<DragValue>(
            initialValue = DragValue.Collapsed,
            anchors = DraggableAnchors {
                DragValue.Collapsed at 0f
                DragValue.Expanded at -(expandedPx - collapsedPx)
            },
            positionalThreshold = { distance: Float -> distance * 0.5f },
            velocityThreshold = { with(density) { 100.dp.toPx() } },
            snapAnimationSpec = spring(),
            decayAnimationSpec = exponentialDecay()
        )
    }

    // Trigger haptic feedback on expansion state change
    LaunchedEffect(anchoredDraggableState.currentValue) {
        HapticUtil.performLightTick(haptics)
    }

    // Sync state with anchoredDraggableState
    val currentOffset = anchoredDraggableState.requireOffset()
    val isExpanded = anchoredDraggableState.currentValue == DragValue.Expanded
    val progress = if (currentOffset.isNaN()) 0f else {
        (currentOffset / -(expandedPx - collapsedPx)).coerceIn(0f, 1f)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(collapsedHeight + (expandedHeight - collapsedHeight) * progress)
            .anchoredDraggable<DragValue>(anchoredDraggableState, Orientation.Vertical),
        shape = RoundedCornerShape(lerp(64f, 24f, progress).dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Background Image (Album Art)
            if (albumArtBitmap != null) {
                Image(
                    bitmap = albumArtBitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .matchParentSize()
                        .blur(16.dp),
                    contentScale = ContentScale.Crop
                )
                // Scrim
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                )
            }

            // Adaptive content based on progress
            if (progress < 0.5f) {
                // Mini Player Layout (Fading out)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(collapsedHeight)
                        .padding(8.dp)
                        .graphicsLayer(alpha = 1f - progress * 2),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Expand Button
                    IconButton(
                        onClick = { 
                            scope.launch { anchoredDraggableState.animateTo(DragValue.Expanded) }
                        },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowUp,
                            contentDescription = "Expand",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Metadata
                    Column(
                        modifier = Modifier
                            .weight(1f)
                    ) {
                        Text(
                            text = musicInfo?.title?.takeIf { it.isNotEmpty() } ?: "Nothing Playing",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = musicInfo?.artist?.takeIf { it.isNotEmpty() } ?: "from your Mac",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Play/Pause Button
                    FilledIconButton(
                        onClick = { onMediaAction("media_play_pause") },
                    ) {
                        Icon(
                            imageVector = if (musicInfo?.isPlaying == true) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (musicInfo?.isPlaying == true) "Pause" else "Play",
                            modifier = Modifier.size(48.dp)
                        )
                    }
                }
            } else {
                // Expanded Player Layout (Fading in)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                        .graphicsLayer(alpha = (progress - 0.5f) * 2),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    // Header with collapse button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(onClick = { 
                            scope.launch { anchoredDraggableState.animateTo(DragValue.Collapsed) }
                        }) {
                            Icon(
                                imageVector = Icons.Rounded.KeyboardArrowDown,
                                contentDescription = "Collapse",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        
                        // Metadata (Centered)
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = musicInfo?.title?.takeIf { it.isNotEmpty() } ?: "Nothing Playing",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = musicInfo?.artist?.takeIf { it.isNotEmpty() } ?: "from your Mac",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        
                        Spacer(modifier = Modifier.size(48.dp)) // To balance the chevron
                    }

                    // Media Controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        ButtonGroup(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(60.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            content = {
                                FilledTonalIconButton(
                                    onClick = { onMediaAction("media_prev") },
                                    modifier = Modifier.weight(0.7f).fillMaxHeight()
                                ) {
                                    Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(36.dp))
                                }

                                FilledIconButton(
                                    onClick = { onMediaAction("media_play_pause") },
                                    modifier = Modifier.weight(1.5f).fillMaxHeight()
                                ) {
                                    Icon(
                                        imageVector = if (musicInfo?.isPlaying == true) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                        contentDescription = if (musicInfo?.isPlaying == true) "Pause" else "Play",
                                        modifier = Modifier.size(48.dp)
                                    )
                                }

                                FilledTonalIconButton(
                                    onClick = { onMediaAction("media_next") },
                                    modifier = Modifier.weight(0.7f).fillMaxHeight()
                                ) {
                                    Icon(Icons.Rounded.SkipNext, contentDescription = "Next", modifier = Modifier.size(36.dp))
                                }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Volume Control
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        IconButton(onClick = onToggleMute) {
                            Icon(
                                imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = "Mute",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        Slider(
                            value = volume,
                            onValueChange = onVolumeChange,
                            valueRange = 0f..100f,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                    }
                }
            }
        }
    }
}

fun lerp(start: Float, stop: Float, fraction: Float): Float {
    return (1 - fraction) * start + fraction * stop
}
