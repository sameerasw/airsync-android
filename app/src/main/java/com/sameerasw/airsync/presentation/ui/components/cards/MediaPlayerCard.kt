package com.sameerasw.airsync.presentation.ui.components.cards

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sameerasw.airsync.domain.model.MacMusicInfo

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MediaPlayerCard(
    musicInfo: MacMusicInfo?,
    albumArtBitmap: Bitmap?,
    volume: Float,
    isMuted: Boolean,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onMediaAction: (String) -> Unit,
    modifier: Modifier = Modifier
) {

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Background Image (Album Art)
            if (albumArtBitmap != null) {
                Image(
                    bitmap = albumArtBitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .matchParentSize()
                        .blur(8.dp),
                    contentScale = ContentScale.Crop
                )
                // Dark scrim for readability
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.6f))
                )
            }
            Column(
                modifier = Modifier.padding(
                    horizontal = 24.dp,
                    vertical = 32.dp
                ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(32.dp)
            ) {
                // Metadata
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = musicInfo?.title?.takeIf { it.isNotEmpty() }
                            ?: "Nothing Playing",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (albumArtBitmap != null) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = musicInfo?.artist?.takeIf { it.isNotEmpty() }
                            ?: "from your Mac",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (albumArtBitmap != null) MaterialTheme.colorScheme.onBackground.copy(
                            alpha = 0.7f
                        ) else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ButtonGroup(
                        modifier = Modifier
                            .weight(1f)
                            .height(60.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        content = {
                            // Previous Button
                            FilledTonalIconButton(
                                onClick = { onMediaAction("media_prev") },
                                modifier = Modifier
                                    .weight(0.7f)
                                    .fillMaxHeight(),
                            ) {
                                Icon(
                                    Icons.Rounded.SkipPrevious,
                                    contentDescription = "Previous",
                                    modifier = Modifier.size(36.dp)
                                )
                            }

                            // Play/Pause Button
                            FilledIconButton(
                                onClick = { onMediaAction("media_play_pause") },
                                modifier = Modifier
                                    .weight(1.5f)
                                    .fillMaxHeight()
                            ) {
                                Icon(
                                    imageVector = if (musicInfo?.isPlaying == true) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = if (musicInfo?.isPlaying == true) "Pause" else "Play",
                                    modifier = Modifier.size(48.dp)
                                )
                            }

                            // Next Button
                            FilledTonalIconButton(
                                onClick = { onMediaAction("media_next") },
                                modifier = Modifier
                                    .weight(0.7f)
                                    .fillMaxHeight(),
                            ) {
                                Icon(
                                    Icons.Rounded.SkipNext,
                                    contentDescription = "Next",
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }
                    )
                }

                // Volume Control
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    IconButton(onClick = onToggleMute) {
                        Icon(
                            imageVector = if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                            contentDescription = "Mute",
                            tint = if (albumArtBitmap != null) Color.White else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Slider(
                        value = volume,
                        onValueChange = onVolumeChange,
                        valueRange = 0f..100f,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = if (albumArtBitmap != null) Color.White else MaterialTheme.colorScheme.primary,
                            activeTrackColor = if (albumArtBitmap != null) Color.White else MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = if (albumArtBitmap != null) Color.White.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                }
            }
        }
    }
}
