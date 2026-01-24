package com.sameerasw.airsync.presentation.ui.components

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sameerasw.airsync.models.PermissionCategory
import com.sameerasw.airsync.models.PermissionGroup
import com.sameerasw.airsync.models.PermissionInfo
import com.sameerasw.airsync.utils.PermissionUtil

@Composable
fun PermissionsOverviewCard(
    permissionGroups: List<PermissionGroup>,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current
    val missingCount = permissionGroups.flatMap { it.permissions }.count { !it.isGranted }
    val requiredMissingCount = permissionGroups.flatMap { it.permissions }.count { !it.isGranted && it.isRequired }
    
    // Permission launcher for runtime permissions
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        onRefresh()
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (requiredMissingCount > 0) {
                MaterialTheme.colorScheme.errorContainer
            } else if (missingCount > 0) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = if (missingCount == 0) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = if (requiredMissingCount > 0) {
                        MaterialTheme.colorScheme.error
                    } else if (missingCount > 0) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (missingCount == 0) "All Permissions Granted" else "Permissions Needed",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    
                    if (missingCount > 0) {
                        Text(
                            text = if (requiredMissingCount > 0) {
                                "$requiredMissingCount required, ${missingCount - requiredMissingCount} optional"
                            } else {
                                "$missingCount optional permissions"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                
                if (missingCount > 0) {
                    TextButton(
                        onClick = {
                            // Request all runtime permissions
                            val runtimePermissions = PermissionUtil.getRuntimePermissionsToRequest(context)
                            if (runtimePermissions.isNotEmpty()) {
                                permissionLauncher.launch(runtimePermissions.toTypedArray())
                            }
                        }
                    ) {
                        Text("Grant All")
                    }
                }
            }
            
            if (missingCount > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Some features require additional permissions to work properly.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
fun PermissionGroupsList(
    permissionGroups: List<PermissionGroup>,
    onRefresh: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(permissionGroups.filter { it.permissions.isNotEmpty() }) { group ->
            PermissionGroupCard(
                group = group,
                onRefresh = onRefresh
            )
        }
    }
}

@Composable
fun PermissionGroupCard(
    group: PermissionGroup,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current
    val missingCount = group.permissions.count { !it.isGranted }
    
    // Permission launcher for runtime permissions
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        onRefresh()
    }
    
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = getCategoryIcon(group.category),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = group.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                if (missingCount > 0) {
                    Badge {
                        Text(missingCount.toString())
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            group.permissions.forEach { permission ->
                PermissionItem(
                    permission = permission,
                    onRequestPermission = {
                        if (permission.requiresSpecialHandling) {
                            handleSpecialPermission(context, permission.permission)
                        } else {
                            permissionLauncher.launch(arrayOf(permission.permission))
                        }
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
fun PermissionItem(
    permission: PermissionInfo,
    onRequestPermission: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector = if (permission.isGranted) Icons.Default.CheckCircle else Icons.Default.Info,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (permission.isGranted) {
                MaterialTheme.colorScheme.primary
            } else if (permission.isRequired) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = permission.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (permission.isRequired) FontWeight.Bold else FontWeight.Normal
                )
                if (permission.isRequired) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Required",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Text(
                text = permission.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        if (!permission.isGranted) {
            TextButton(onClick = onRequestPermission) {
                Text("Grant")
            }
        } else {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Granted",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private fun getCategoryIcon(category: PermissionCategory) = when (category) {
    PermissionCategory.CORE -> Icons.Default.Settings
    PermissionCategory.MESSAGING -> Icons.Default.Message
    PermissionCategory.CALLS -> Icons.Default.Phone
    PermissionCategory.HEALTH -> Icons.Default.FavoriteBorder
    PermissionCategory.LOCATION -> Icons.Default.LocationOn
    PermissionCategory.STORAGE -> Icons.Default.Folder
    PermissionCategory.SPECIAL -> Icons.Default.Security
}

private fun handleSpecialPermission(context: android.content.Context, permission: String) {
    when (permission) {
        PermissionUtil.NOTIFICATION_ACCESS -> {
            PermissionUtil.openNotificationListenerSettings(context)
        }
        PermissionUtil.ACCESSIBILITY_SERVICE -> {
            PermissionUtil.openAccessibilitySettings(context)
        }
        PermissionUtil.BACKGROUND_APP_USAGE -> {
            PermissionUtil.openBatteryOptimizationSettings(context)
        }
        PermissionUtil.HEALTH_CONNECT -> {
            PermissionUtil.openHealthConnectPermissions(context)
        }
        "MANAGE_EXTERNAL_STORAGE" -> {
            PermissionUtil.openManageExternalStorageSettings(context)
        }
        "SYSTEM_ALERT_WINDOW" -> {
            PermissionUtil.openOverlaySettings(context)
        }
        else -> {
            PermissionUtil.openAppSettings(context)
        }
    }
}
