package com.sameerasw.airsync.presentation.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import com.sameerasw.airsync.utils.HealthConnectUtil
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthDataScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var isHealthConnectAvailable by remember { mutableStateOf(false) }
    var hasPermissions by remember { mutableStateOf(false) }
    var healthSummary by remember { mutableStateOf<com.sameerasw.airsync.models.HealthSummary?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    // Health Connect permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        scope.launch {
            hasPermissions = HealthConnectUtil.hasPermissions(context)
            if (hasPermissions) {
                loadHealthData(context) { summary, error ->
                    healthSummary = summary
                    errorMessage = error
                    isLoading = false
                }
            }
        }
    }
    
    // Check Health Connect availability and permissions
    LaunchedEffect(Unit) {
        isHealthConnectAvailable = HealthConnectUtil.isAvailable(context)
        if (isHealthConnectAvailable) {
            hasPermissions = HealthConnectUtil.hasPermissions(context)
            if (hasPermissions) {
                isLoading = true
                loadHealthData(context) { summary, error ->
                    healthSummary = summary
                    errorMessage = error
                    isLoading = false
                }
            }
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Health & Fitness") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Health Connect Status Card
            item {
                HealthConnectStatusCard(
                    isAvailable = isHealthConnectAvailable,
                    hasPermissions = hasPermissions,
                    onRequestPermissions = {
                        val permissions = HealthConnectUtil.PERMISSIONS
                        permissionLauncher.launch(permissions)
                    }
                )
            }
            
            if (hasPermissions && healthSummary != null) {
                // Today's Summary
                item {
                    TodaySummaryCard(healthSummary!!)
                }
                
                // Activity Metrics
                item {
                    ActivityMetricsCard(healthSummary!!)
                }
                
                // Health Metrics
                item {
                    HealthMetricsCard(healthSummary!!)
                }
            }
            
            if (isLoading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
            
            if (errorMessage != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = errorMessage!!,
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HealthConnectStatusCard(
    isAvailable: Boolean,
    hasPermissions: Boolean,
    onRequestPermissions: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (!isAvailable) {
                MaterialTheme.colorScheme.errorContainer
            } else if (!hasPermissions) {
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
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (hasPermissions) Icons.Default.CheckCircle else Icons.Default.FavoriteBorder,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp)
                )
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (!isAvailable) {
                            "Health Connect Not Available"
                        } else if (!hasPermissions) {
                            "Permissions Needed"
                        } else {
                            "Connected to Health Connect"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Text(
                        text = if (!isAvailable) {
                            "Install Health Connect from Play Store"
                        } else if (!hasPermissions) {
                            "Grant permissions to sync health data"
                        } else {
                            "Syncing data from health apps"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            
            if (isAvailable && !hasPermissions) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onRequestPermissions,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Security, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Grant Permissions")
                }
            }
        }
    }
}

@Composable
fun TodaySummaryCard(summary: com.sameerasw.airsync.models.HealthSummary) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Today's Summary",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                SummaryItem(
                    icon = Icons.Default.DirectionsWalk,
                    value = summary.steps?.toString() ?: "—",
                    label = "Steps",
                    color = MaterialTheme.colorScheme.primary
                )
                
                SummaryItem(
                    icon = Icons.Default.LocalFireDepartment,
                    value = summary.calories?.toString() ?: "—",
                    label = "Calories",
                    color = MaterialTheme.colorScheme.tertiary
                )
                
                SummaryItem(
                    icon = Icons.Default.FavoriteBorder,
                    value = summary.heartRateAvg?.toString() ?: "—",
                    label = "Heart Rate",
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
fun ActivityMetricsCard(summary: com.sameerasw.airsync.models.HealthSummary) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Activity",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            MetricRow(
                icon = Icons.Default.DirectionsRun,
                label = "Distance",
                value = summary.distance?.let { "%.2f km".format(it) } ?: "—"
            )
            
            MetricRow(
                icon = Icons.Default.Timer,
                label = "Active Minutes",
                value = summary.activeMinutes?.let { "$it min" } ?: "—"
            )
        }
    }
}

@Composable
fun HealthMetricsCard(summary: com.sameerasw.airsync.models.HealthSummary) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Health",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            MetricRow(
                icon = Icons.Default.Bedtime,
                label = "Sleep",
                value = summary.sleepDuration?.let { 
                    val hours = it / 60
                    val minutes = it % 60
                    "${hours}h ${minutes}m"
                } ?: "—"
            )
            
            if (summary.heartRateMin != null && summary.heartRateMax != null) {
                MetricRow(
                    icon = Icons.Default.FavoriteBorder,
                    label = "Heart Rate Range",
                    value = "${summary.heartRateMin} - ${summary.heartRateMax} bpm"
                )
            }
        }
    }
}

@Composable
fun SummaryItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    label: String,
    color: androidx.compose.ui.graphics.Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = color
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun MetricRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
        
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

private suspend fun loadHealthData(
    context: android.content.Context,
    onResult: (com.sameerasw.airsync.models.HealthSummary?, String?) -> Unit
) {
    try {
        val summary = HealthConnectUtil.getTodaySummary(context)
        onResult(summary, null)
    } catch (e: Exception) {
        onResult(null, "Error loading health data: ${e.message}")
    }
}
