package com.sameerasw.airsync.presentation.ui.components.cards

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.sameerasw.airsync.R
import com.sameerasw.airsync.utils.HapticUtil

@Composable
fun IconToggleItem(
    title: String,
    modifier: Modifier = Modifier,
    iconRes: Int? = null,
    description: String? = null,
    isChecked: Boolean = false,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    onCheckedChangeWithPosition: ((Boolean, Offset) -> Unit)? = null,
    enabled: Boolean = true,
    onDisabledClick: (() -> Unit)? = null,
    showToggle: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailingIcon: Int? = null
) {
    val haptics = LocalHapticFeedback.current
    var switchCenterOffset by remember { mutableStateOf(Offset.Zero) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraSmall,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceBright
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    enabled = enabled || onDisabledClick != null,
                    onClick = {
                        if (enabled) {
                            HapticUtil.performClick(haptics)
                            if (onClick != null) {
                                onClick()
                            } else if (onCheckedChange != null && showToggle) {
                                val nextState = !isChecked
                                onCheckedChange(nextState)
                                onCheckedChangeWithPosition?.invoke(nextState, switchCenterOffset)
                            }
                        } else if (onDisabledClick != null) {
                            HapticUtil.performClick(haptics)
                            onDisabledClick()
                        }
                    }
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (iconRes != null) {
                Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = title,
                    modifier = Modifier.size(24.dp),
                    tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(
                        alpha = 0.38f
                    )
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(
                        alpha = 0.38f
                    )
                )
                if (description != null) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = 0.38f
                        ),
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            if (showToggle && onCheckedChange != null) {
                Switch(
                    checked = if (enabled) isChecked else false,
                    onCheckedChange = { checked ->
                        if (enabled) {
                            HapticUtil.performClick(haptics)
                            onCheckedChange(checked)
                            onCheckedChangeWithPosition?.invoke(checked, switchCenterOffset)
                        }
                    },
                    enabled = enabled,
                    modifier = Modifier.onGloballyPositioned { coords ->
                        val pos = coords.positionInRoot()
                        val size = coords.size
                        switchCenterOffset = Offset(
                            x = pos.x + (size.width / 2f),
                            y = pos.y + (size.height / 2f)
                        )
                    }
                )
            } else if (onClick != null && !showToggle) {
                Icon(
                    painter = painterResource(
                        id = trailingIcon ?: R.drawable.rounded_keyboard_arrow_right_24
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = 0.38f
                    )
                )
            }
        }
    }
}
