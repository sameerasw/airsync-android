package com.sameerasw.airsync.presentation.ui.components.sheets

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.sameerasw.airsync.R
import com.sameerasw.airsync.presentation.ui.components.RoundedCardContainer

data class HelpSection(
    val title: String,
    val iconRes: Int,
    val content: String,
    val links: List<Pair<String, String>> = emptyList()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpSupportBottomSheet(
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    val sections = remember {
        listOf(
            HelpSection(
                title = "Getting Started",
                iconRes = R.drawable.rounded_qr_code_scanner_24,
                content = "To connect your Mac, ensure both devices are on the same Wi-Fi network or with Extended networking on tailscale or similar network. Scan the QR code in AirSync for Mac.",
                links = listOf("Manual Auth Info" to "https://airsync.notion.site")
            ),
            HelpSection(
                title = "Permissions & Usage",
                iconRes = R.drawable.rounded_security_24,
                content = "• Notification Access: Required to sync alerts/media. For sideloaded installs, enable 'Restricted Settings' in App Info.\n• Post Notifications: For the ongoing connection indicator.\n• Background Usage: Keeps the connection alive.\n• Storage: Required for wallpaper sync (still images only).",
                links = listOf("Privacy Policy" to "https://www.sameerasw.com/airsync/privacy")
            ),
            HelpSection(
                title = "ADB & Mirroring",
                iconRes = R.drawable.rounded_laptop_mac_24,
                content = "Install 'android-platform-tools' and 'scrcpy' on Mac via brew. Enable Wireless Debugging in Developer Options on Android. Use 'adb pair ip:port' with the code provided. Mirroring is an AirSync+ feature.",
                links = listOf("ADB Setup Guide" to "https://www.notion.so/2-5-ADB-and-mirroring-setup-2549c6099d408072b46cfa517ebf2719")
            ),
            HelpSection(
                title = "Re-connection",
                iconRes = R.drawable.rounded_sync_desktop_24,
                content = "Auto-reconnect tries for 1 minute (every 10s) and then every minute. Use the Quick Settings tile for instant one-tap reconnection to the last device.",
            ),
            HelpSection(
                title = "Updates",
                iconRes = R.drawable.rounded_notifications_active_24,
                content = "Update the Android app via Google Play Store. The macOS app has a built-in updater.",
            ),
            HelpSection(
                title = "Troubleshooting",
                iconRes = R.drawable.rounded_troubleshoot_24,
                content = "• IP N/A on Mac: Ensure LAN connection (not just internet).\n• Random Disconnects: Check battery/data saving policies and network stability.\n• Black Display: If 'Darken screen' is on during mirroring, use ⌥+Shift+O to reveal.",
                links = listOf("Full FAQ" to "https://airsync.notion.site")
            )
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    text = "Help & Support",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 8.dp, top = 8.dp)
                )
            }

            item {
                RoundedCardContainer {
                    sections.forEach { section ->
                        ExpandableHelpSection(section)
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Need more help?",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 32.dp, top = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    SupportButton(
                        text = "GitHub",
                        iconRes = R.drawable.brand_github,
                        onClick = {
                            openUrl(
                                context,
                                "https://github.com/sameerasw/airsync-android/issues"
                            )
                        }
                    )
                    SupportButton(
                        text = "Telegram",
                        iconRes = R.drawable.brand_telegram,
                        onClick = { openUrl(context, "https://t.me/tidwib") },
                        outlined = true
                    )
                }
            }
        }
    }
}

@Composable
private fun ExpandableHelpSection(section: HelpSection) {
    var expanded by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "arrow_rotation"
    )
    val context = LocalContext.current

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        color = if (expanded) MaterialTheme.colorScheme.surfaceBright else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(id = section.iconRes),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Text(
                    text = section.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )

                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.rotate(rotation)
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = section.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    section.links.forEach { (label, url) ->
                        TextButton(
                            onClick = { openUrl(context, url) },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(label, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SupportButton(
    text: String,
    iconRes: Int,
    onClick: () -> Unit,
    outlined: Boolean = false
) {
    if (outlined) {
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.padding(horizontal = 4.dp)
        ) {
            Icon(
                painterResource(id = iconRes),
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text)
        }
    } else {
        Button(
            onClick = onClick,
            modifier = Modifier.padding(horizontal = 4.dp)
        ) {
            Icon(
                painterResource(id = iconRes),
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text)
        }
    }
}

private fun openUrl(context: android.content.Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Could not open link", Toast.LENGTH_SHORT).show()
    }
}
