package com.sameerasw.airsync.presentation.ui.components.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sameerasw.airsync.R
import com.sameerasw.airsync.utils.HapticUtil

/**
 * A card containing quick remote functions for the connected Mac.
 * Currently includes: Lock Screen.
 */
@Composable
fun RemoteFunctionsCard(
    onRemoteAction: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current

    Card(
        modifier = modifier
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.extraSmall,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Lock Screen Button
            Button(
                onClick = {
                    HapticUtil.performClick(haptics)
                    onRemoteAction("lock_screen")
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceBright,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ),
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.rounded_lock_24),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = stringResource(id = R.string.action_lock_screen),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1
                )
            }

            // Screensaver Button
            Button(
                onClick = {
                    HapticUtil.performClick(haptics)
                    onRemoteAction("screensaver")
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceBright,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ),
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier
                    .weight(1.3f)
                    .height(48.dp)
            ) {
                Icon(
                    painter = painterResource(id = com.sameerasw.airsync.R.drawable.rounded_screenshot_monitor_24),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = stringResource(id = R.string.action_screensaver),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1
                )
            }

            // Brightness Down Button
            Button(
                onClick = {
                    HapticUtil.performClick(haptics)
                    onRemoteAction("brightness_down")
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceBright,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ),
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier
                    .weight(0.5f)
                    .height(48.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.rounded_brightness_5_24),
                    contentDescription = stringResource(id = R.string.content_description_brightness_down),
                    modifier = Modifier.size(20.dp)
                )
            }

            // Brightness Up Button
            Button(
                onClick = {
                    HapticUtil.performClick(haptics)
                    onRemoteAction("brightness_up")
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceBright,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ),
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier
                    .weight(0.5f)
                    .height(48.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.rounded_brightness_7_24),
                    contentDescription = stringResource(id = R.string.content_description_brightness_up),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
