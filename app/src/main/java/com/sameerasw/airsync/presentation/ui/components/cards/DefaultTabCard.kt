package com.sameerasw.airsync.presentation.ui.components.cards

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Phonelink
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.sameerasw.airsync.R
import com.sameerasw.airsync.utils.HapticUtil
import androidx.compose.ui.platform.LocalHapticFeedback

@Composable
fun DefaultTabCard(
    currentDefaultTab: String,
    onDefaultTabChange: (String) -> Unit
) {
    val haptics = LocalHapticFeedback.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Default tab",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Select which tab the app should open to by default",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally)
            ) {
                TabOption(
                    title = "Connect",
                    icon = Icons.Filled.Phonelink,
                    isSelected = currentDefaultTab == "connect",
                    onClick = { 
                        HapticUtil.performClick(haptics)
                        onDefaultTabChange("connect") 
                    }
                )
                TabOption(
                    title = "Remote",
                    icon = Icons.Filled.Gamepad,
                    isSelected = currentDefaultTab == "remote",
                    onClick = { 
                        HapticUtil.performClick(haptics)
                        onDefaultTabChange("remote") 
                    }
                )
                TabOption(
                    title = "Clipboard",
                    icon = Icons.Filled.ContentPaste,
                    isSelected = currentDefaultTab == "clipboard",
                    onClick = { 
                        HapticUtil.performClick(haptics)
                        onDefaultTabChange("clipboard") 
                    }
                )
                TabOption(
                    title = "Dynamic",
                    iconRes = R.drawable.rounded_asterisk_24,
                    isSelected = currentDefaultTab == "dynamic",
                    onClick = { 
                        HapticUtil.performClick(haptics)
                        onDefaultTabChange("dynamic") 
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TabOption(
    title: String,
    icon: ImageVector? = null,
    iconRes: Int? = null,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(64.dp),
            colors = if (isSelected) {
                IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            shape = if (isSelected) MaterialTheme.shapes.small else MaterialTheme.shapes.extraExtraLarge,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    modifier = Modifier.size(32.dp)
                )
            } else if (iconRes != null) {
                Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = title,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
        if (isSelected) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
