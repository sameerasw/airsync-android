package com.sameerasw.airsync.presentation.ui.components.cards

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SignalCellular4Bar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.sameerasw.airsync.utils.BluetoothHelper
import com.sameerasw.airsync.utils.BluetoothSyncManager
import com.sameerasw.airsync.utils.HapticUtil

@Composable
fun BluetoothCard(
    modifier: Modifier = Modifier,
    onDeviceConnected: ((BluetoothDevice) -> Unit)? = null
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    
    var isExpanded by remember { mutableStateOf(false) }
    var isScanning by remember { mutableStateOf(false) }
    var hasPermissions by remember { mutableStateOf(false) }
    var permissionDeniedPermanently by remember { mutableStateOf(false) }
    var autoConnectEnabled by remember { mutableStateOf(true) }
    
    // BluetoothSyncManager states
    val connectionState by BluetoothSyncManager.connectionState.collectAsState()
    val pairingState by BluetoothSyncManager.pairingState.collectAsState()
    val pairedDevice by BluetoothSyncManager.pairedDevice.collectAsState()
    val connectedDeviceName by BluetoothSyncManager.connectedDeviceName.collectAsState()
    val isAdvertisingState by BluetoothSyncManager.isAdvertising.collectAsState()
    
    val discoveredDevices by BluetoothSyncManager.getHelper()?.discoveredDevices?.collectAsState() 
        ?: remember { mutableStateOf(emptyList()) }
    
    
    // Update isScanning based on connection state
    LaunchedEffect(connectionState) {
        isScanning = connectionState is BluetoothSyncManager.ConnectionState.Scanning
    }
    
    val requiredPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }
    
    fun checkPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    fun initBluetooth() {
        BluetoothSyncManager.initialize(context)
        BluetoothSyncManager.startAdvertising()
        BluetoothSyncManager.refreshConnectionState() // Ensure connection state is accurate
        autoConnectEnabled = BluetoothSyncManager.isAutoConnectEnabled()
    }
    
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        hasPermissions = allGranted
        if (allGranted) {
            initBluetooth()
            permissionDeniedPermanently = false
        } else {
            val anyPermanentlyDenied = permissions.keys.any { permission ->
                !permissions[permission]!! && 
                (context as? android.app.Activity)?.let { activity ->
                    !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
                } ?: false
            }
            permissionDeniedPermanently = anyPermanentlyDenied
        }
    }
    
    LaunchedEffect(Unit) {
        hasPermissions = checkPermissions()
        if (hasPermissions) {
            initBluetooth()
        }
    }
    
    DisposableEffect(Unit) {
        onDispose {
            BluetoothSyncManager.stopScanning()
            BluetoothSyncManager.stopAdvertising()
        }
    }
    
    // Pairing PIN Dialog
    if (pairingState is BluetoothSyncManager.PairingState.ConfirmationRequired) {
        val code = (pairingState as BluetoothSyncManager.PairingState.ConfirmationRequired).code
        PairingPinDialog(
            expectedCode = code,
            onConfirm = {
                HapticUtil.performClick(haptics)
                BluetoothSyncManager.acceptPairing()
            },
            onDismiss = {
                BluetoothSyncManager.rejectPairing()
            }
        )
    }
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        HapticUtil.performClick(haptics)
                        isExpanded = !isExpanded
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = when (connectionState) {
                            is BluetoothSyncManager.ConnectionState.Connected -> Icons.Default.BluetoothConnected
                            is BluetoothSyncManager.ConnectionState.Scanning -> Icons.Default.BluetoothSearching
                            else -> Icons.Default.Bluetooth
                        },
                        contentDescription = null,
                        tint = if (connectionState is BluetoothSyncManager.ConnectionState.Connected) 
                            MaterialTheme.colorScheme.primary 
                        else 
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Column {
                        Text(
                            text = "Bluetooth Connection",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = when (connectionState) {
                                is BluetoothSyncManager.ConnectionState.Connected -> 
                                    "Connected to ${connectedDeviceName ?: "device"}"
                                is BluetoothSyncManager.ConnectionState.Connecting -> "Connecting..."
                                is BluetoothSyncManager.ConnectionState.Scanning -> "Scanning..."
                                is BluetoothSyncManager.ConnectionState.Error -> "Error"
                                else -> pairedDevice?.let { "Paired: ${it.name}" } ?: "Tap to expand"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (connectionState is BluetoothSyncManager.ConnectionState.Connected)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) "Collapse" else "Expand"
                )
            }
            
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!hasPermissions) {
                        PermissionCard(
                            permissionDeniedPermanently = permissionDeniedPermanently,
                            onGrantPermissions = {
                                HapticUtil.performClick(haptics)
                                permissionLauncher.launch(requiredPermissions)
                            },
                            onOpenSettings = {
                                HapticUtil.performClick(haptics)
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            },
                            onCheckAgain = {
                                HapticUtil.performClick(haptics)
                                hasPermissions = checkPermissions()
                                if (hasPermissions) {
                                    permissionDeniedPermanently = false
                                    initBluetooth()
                                }
                            }
                        )
                    } else {
                        // Paired device section
                        pairedDevice?.let { paired ->
                            PairedDeviceCard(
                                deviceName = paired.name,
                                isConnected = connectionState is BluetoothSyncManager.ConnectionState.Connected,
                                isConnecting = connectionState is BluetoothSyncManager.ConnectionState.Connecting,
                                autoConnectEnabled = autoConnectEnabled,
                                onConnect = {
                                    HapticUtil.performClick(haptics)
                                    BluetoothSyncManager.tryAutoConnect()
                                },
                                onDisconnect = {
                                    HapticUtil.performClick(haptics)
                                    BluetoothSyncManager.disconnect()
                                },
                                onForget = {
                                    HapticUtil.performClick(haptics)
                                    BluetoothSyncManager.forgetPairedDevice()
                                },
                                onAutoConnectChanged = { enabled ->
                                    HapticUtil.performClick(haptics)
                                    autoConnectEnabled = enabled
                                    BluetoothSyncManager.setAutoConnectEnabled(enabled)
                                }
                            )
                        }
                        
                        // Scan controls
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    HapticUtil.performClick(haptics)
                                    if (isScanning) {
                                        BluetoothSyncManager.stopScanning()
                                    } else {
                                        BluetoothSyncManager.startScanning()
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                if (isScanning) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Stop Scan")
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.BluetoothSearching,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Scan Devices")
                                }
                            }
                            
                            OutlinedButton(
                                onClick = {
                                    HapticUtil.performClick(haptics)
                                    if (isAdvertisingState) {
                                        BluetoothSyncManager.stopAdvertising()
                                    } else {
                                        BluetoothSyncManager.startAdvertising()
                                    }
                                },
                                colors = if (isAdvertisingState) {
                                    ButtonDefaults.outlinedButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    ButtonDefaults.outlinedButtonColors()
                                }
                            ) {
                                if (isAdvertisingState) {
                                    Icon(
                                        imageVector = Icons.Default.BluetoothConnected,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Visible")
                                } else {
                                    Text("Make Visible")
                                }
                            }
                        }
                        
                        // Pairing state indicator
                        when (pairingState) {
                            is BluetoothSyncManager.PairingState.WaitingForConfirmation -> {
                                val code = (pairingState as BluetoothSyncManager.PairingState.WaitingForConfirmation).code
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Waiting for confirmation on other device...")
                                        }
                                        
                                        Text(
                                            text = code,
                                            style = MaterialTheme.typography.headlineMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                            is BluetoothSyncManager.PairingState.Success -> {
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer
                                    )
                                ) {
                                    Text(
                                        text = "✓ Pairing successful!",
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        textAlign = TextAlign.Center,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                            is BluetoothSyncManager.PairingState.Failed -> {
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer
                                    )
                                ) {
                                    Text(
                                        text = "✗ Pairing failed: ${(pairingState as BluetoothSyncManager.PairingState.Failed).reason}",
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        textAlign = TextAlign.Center,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                            else -> {}
                        }
                        
                        // Discovered devices
                        if (discoveredDevices.isNotEmpty()) {
                            Text(
                                text = "Discovered Devices (${discoveredDevices.size})",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            
                            LazyColumn(
                                modifier = Modifier.height(200.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(discoveredDevices) { device ->
                                    DiscoveredDeviceItem(
                                        device = device,
                                        onPair = {
                                            HapticUtil.performClick(haptics)
                                            BluetoothSyncManager.initiatePairing(device.device)
                                        }
                                    )
                                }
                            }
                        } else if (isScanning) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "Searching for AirSync devices...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            Text(
                                text = "Tap 'Scan Devices' to find nearby AirSync-enabled Macs",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PairingPinDialog(
    expectedCode: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Enter Pairing PIN",
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Enter the 6-digit code shown on your Mac",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                
                OutlinedTextField(
                    value = pin,
                    onValueChange = { 
                        if (it.length <= 6 && it.all { char -> char.isDigit() }) {
                            pin = it
                            error = null
                        }
                    },
                    label = { Text("PIN") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    isError = error != null
                )
                
                if (error != null) {
                    Text(
                        text = error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (pin == expectedCode) {
                        onConfirm()
                    } else {
                        error = "Incorrect PIN"
                    }
                },
                enabled = pin.length == 6
            ) {
                Text("Pair")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun PermissionCard(
    permissionDeniedPermanently: Boolean,
    onGrantPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onCheckAgain: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Bluetooth permissions required",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = if (permissionDeniedPermanently) 
                    "Permission was denied. Please enable in Settings."
                else 
                    "Grant permissions to scan for nearby devices",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
            )
            
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (permissionDeniedPermanently) {
                    Button(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("App Settings")
                    }
                } else {
                    Button(onClick = onGrantPermissions) {
                        Text("Grant Permissions")
                    }
                }
                
                TextButton(onClick = onCheckAgain) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Check Again")
                }
            }
        }
    }
}

@Composable
private fun PairedDeviceCard(
    deviceName: String,
    isConnected: Boolean,
    isConnecting: Boolean,
    autoConnectEnabled: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onForget: () -> Unit,
    onAutoConnectChanged: (Boolean) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isConnected) 
                MaterialTheme.colorScheme.primaryContainer 
            else 
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Device info row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (isConnected) Icons.Default.BluetoothConnected else Icons.Default.Link,
                    contentDescription = null,
                    tint = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column {
                    Text(
                        text = deviceName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = if (isConnected) "Connected" else if (isConnecting) "Connecting..." else "Paired",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            // Action buttons row
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Connect/Disconnect button
                if (isConnected) {
                    OutlinedButton(
                        onClick = onDisconnect,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Disconnect")
                    }
                } else if (isConnecting) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Connecting...")
                    }
                } else {
                    Button(
                        onClick = onConnect,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Connect")
                    }
                }
                
                // Remove Paired Device button
                OutlinedButton(
                    onClick = onForget,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Remove Paired Device")
                }
            }
            
            // Auto-connect toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Auto-connect on launch",
                    style = MaterialTheme.typography.bodySmall
                )
                Switch(
                    checked = autoConnectEnabled,
                    onCheckedChange = onAutoConnectChanged
                )
            }
        }
    }
}

@Composable
private fun DiscoveredDeviceItem(
    device: BluetoothHelper.DiscoveredDevice,
    onPair: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Device info row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Computer,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = device.name.ifEmpty { "AirSync Device" },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        device.deviceInfo?.let { info ->
                            Text(
                                text = info.deviceModel ?: info.deviceType,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                
                // Signal strength
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val signalStrength = when {
                        device.rssi > -50 -> "Strong"
                        device.rssi > -70 -> "Good"
                        else -> "Weak"
                    }
                    Text(
                        text = signalStrength,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Icon(
                        imageVector = Icons.Default.SignalCellular4Bar,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = when {
                            device.rssi > -50 -> MaterialTheme.colorScheme.primary
                            device.rssi > -70 -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.error
                        }
                    )
                }
            }
            
            // Pair button on its own row
            Button(
                onClick = onPair,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("Pair Device")
            }
        }
    }
}
