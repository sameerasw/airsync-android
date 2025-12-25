package com.sameerasw.airsync.presentation.ui.components.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sameerasw.airsync.R

enum class PermissionType {
    NOTIFICATION_ACCESS,
    POST_NOTIFICATIONS,
    BACKGROUND_USAGE,
    WALLPAPER_ACCESS,
    CALL_LOG,
    CONTACTS,
    PHONE,
    ANSWER_PHONE_CALLS
}

data class PermissionInfo(
    val title: String,
    val icon: Int,
    val description: String,
    val whyNeeded: String,
    val buttonText: String
)

@Composable
fun PermissionExplanationDialog(
    permissionType: PermissionType,
    onDismiss: () -> Unit,
    onGrantPermission: () -> Unit
) {
    val permissionInfo = getPermissionInfo(permissionType)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        painter = painterResource(id = permissionInfo.icon),
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = permissionInfo.title,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }

                // Description
                Text(
                    text = permissionInfo.description,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Why we need this permission
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Why AirSync needs this permission:",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = permissionInfo.whyNeeded,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {

                    Spacer(modifier = Modifier.weight(1f))

                    OutlinedButton(
                        onClick = onDismiss,
                    ) {
                        Text("Dismiss")
                    }

                    Button(
                        onClick = {
                            onGrantPermission()
                            onDismiss()
                        },
                    ) {
                        Text(permissionInfo.buttonText)
                    }
                }
            }
        }
    }
}

private fun getPermissionInfo(permissionType: PermissionType): PermissionInfo {
    return when (permissionType) {
        PermissionType.NOTIFICATION_ACCESS -> PermissionInfo(
            title = "Notification Access",
            icon = R.drawable.rounded_notification_settings_24,
            description = "AirSync needs access to read your device notifications to sync them with your mac in real-time.",
            whyNeeded = "This is the core functionality of AirSync. Without notification access, the app cannot read incoming notifications from other apps and forward them to your mac. \nThe media now playing status also relies on this permission. \nThe app will use this permission only for the explained use cases and will always sync these data via an encrypted local network",
            buttonText = "Grant Notification Access"
        )

        PermissionType.POST_NOTIFICATIONS -> PermissionInfo(
            title = "Post Notifications",
            icon = R.drawable.rounded_notifications_active_24,
            description = "On Android 13+, apps need explicit permission to display notifications. AirSync uses this to show connection status and sync confirmations.",
            whyNeeded = "AirSync needs to show you important notifications about connection status, sync progress, and any errors that occur during operation.",
            buttonText = "Allow Notifications"
        )

        PermissionType.BACKGROUND_USAGE -> PermissionInfo(
            title = "Background App Usage",
            icon = R.drawable.rounded_local_laundry_service_24,
            description = "Android's battery optimization can kill background apps. Disabling this for AirSync ensures reliable notification syncing even when you're not actively using the app.",
            whyNeeded = "Modern Android aggressively kills background apps to save battery. AirSync needs to run continuously to sync notifications in real-time. \nThis may result in minor battery drain increases but will ensure the connection stays stable and functions when the app is not in focus like QS tiles work well.",
            buttonText = "Disable Battery Optimization"
        )

        PermissionType.WALLPAPER_ACCESS -> PermissionInfo(
            title = "Wallpaper & File Access",
            icon = R.drawable.rounded_folder_managed_24,
            description = "This optional permission allows AirSync to sync your phone's wallpaper to your mac.",
            whyNeeded = "To read your current wallpaper which is not accessible with regular privileges,  AirSync needs external storage permissions. \nBut the app will only use the permission for the given explained use cases and will not alter or read any other files on the storage.",
            buttonText = "Grant Storage Access"
        )

        PermissionType.CALL_LOG -> PermissionInfo(
            title = "Call Log Access",
            icon = R.drawable.rounded_call_log_24,
            description = "AirSync needs call log access to identify the caller's phone number for real-time alerts on your Mac.",
            whyNeeded = "To provide accurate call notifications on your Mac, Android requires the Call Log permission to see the phone number of an incoming call. \n\nWithout this, your Mac will only show 'Unknown Caller'. This permission is used strictly to identify the caller in real-time and provide alerts for missed or incoming calls. \n\nThis data is only shared with your own Mac over your secure local network and is never stored on our servers.",
            buttonText = "Grant Call Log Access"
        )

        PermissionType.CONTACTS -> PermissionInfo(
            title = "Contacts Access",
            icon = R.drawable.rounded_contacts_24,
            description = "AirSync needs access to your contacts to match phone numbers with names on your Mac.",
            whyNeeded = "To provide a seamless companion experience, AirSync uses your contact names so you can see who is calling or who sent a notification directly on your Mac desktop. \n\nWithout this, you would only see phone numbers. Your contacts are never uploaded to any external servers; they are only used locally to enhance the connection between your phone and your Mac.",
            buttonText = "Grant Contacts Access"
        )

        PermissionType.PHONE -> PermissionInfo(
            title = "Phone Access",
            icon = R.drawable.rounded_settings_phone_24,
            description = "AirSync needs to detect your phone's state to notify you of incoming calls in real-time.",
            whyNeeded = "This permission allows AirSync to detect when your phone is ringing, when you answer, or when a call ends, so it can display a live call status on your Mac. \n\nAirSync NEVER accesses your call audio or records conversations. This is used solely to facilitate the remote call notification feature as a device companion.",
            buttonText = "Grant Phone Access"
        )

        PermissionType.ANSWER_PHONE_CALLS -> PermissionInfo(
            title = "Answer Calls",
            icon = R.drawable.outline_call_end_24,
            description = "AirSync needs this permission to end or reject calls from your Mac.",
            whyNeeded = "To allow you to decline or hang up calls directly from your Mac, Android requires this specific permission. \n\nWithout it, the 'End Call' button on your Mac will not work. This permission is strictly used for call control actions you initiate.",
            buttonText = "Grant Call Control"
        )
    }
}
