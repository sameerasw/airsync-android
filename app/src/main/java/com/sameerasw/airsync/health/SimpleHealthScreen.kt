package com.sameerasw.airsync.health

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Scale
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stairs
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleHealthScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val healthManager = remember { SimpleHealthConnectManager(context) }
    
    var isAvailable by remember { mutableStateOf(false) }
    var hasPermissions by remember { mutableStateOf(false) }
    var healthStats by remember { mutableStateOf(HealthStats()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedDate by remember { mutableStateOf(java.time.LocalDate.now()) }
    var showDatePicker by remember { mutableStateOf(false) }
    
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract()
    ) { granted: Set<String> ->
        scope.launch {
            hasPermissions = healthManager.hasPermissions()
            if (hasPermissions) {
                isLoading = true
                healthStats = healthManager.getStatsForDate(selectedDate)
                isLoading = false
            }
        }
    }
    
    // Load data when date changes
    LaunchedEffect(selectedDate) {
        if (isAvailable && hasPermissions) {
            android.util.Log.d("HealthScreen", "Date changed to: $selectedDate, loading data...")
            isLoading = true
            healthStats = healthManager.getStatsForDate(selectedDate)
            android.util.Log.d("HealthScreen", "Date change - Loaded stats: steps=${healthStats.steps}, calories=${healthStats.calories}")
            isLoading = false
        }
    }
    
    LaunchedEffect(Unit) {
        isAvailable = healthManager.isAvailable()
        android.util.Log.d("HealthScreen", "Health Connect available: $isAvailable")
        
        if (isAvailable) {
            hasPermissions = healthManager.hasPermissions()
            android.util.Log.d("HealthScreen", "Has basic permissions: $hasPermissions")
            
            val allPermissions = healthManager.hasAllPermissions()
            android.util.Log.d("HealthScreen", "Has all permissions: $allPermissions")
            
            if (hasPermissions) {
                android.util.Log.d("HealthScreen", "Loading health stats for date: $selectedDate")
                healthStats = healthManager.getStatsForDate(selectedDate)
                android.util.Log.d("HealthScreen", "Loaded health stats: steps=${healthStats.steps}, calories=${healthStats.calories}, heartRate=${healthStats.heartRate}")
            }
        }
        isLoading = false
        
        // Clean old cache on startup
        HealthDataCache.cleanOldCache(context)
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header with actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Health & Fitness",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            
            if (hasPermissions) {
                Row {
                    IconButton(
                        onClick = { showDatePicker = true }
                    ) {
                        Icon(Icons.Default.CalendarToday, "Select Date")
                    }
                    IconButton(
                        onClick = {
                            scope.launch {
                                android.util.Log.d("HealthScreen", "Manual refresh triggered")
                                isLoading = true
                                healthStats = healthManager.getStatsForDate(selectedDate, forceRefresh = true)
                                android.util.Log.d("HealthScreen", "Manual refresh completed: steps=${healthStats.steps}")
                                isLoading = false
                            }
                        }
                    ) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                }
            }
        }
        
        when {
            !isAvailable -> {
                HealthConnectNotAvailableCard()
            }
            !hasPermissions -> {
                PermissionsNeededCard(
                    onRequestPermissions = {
                        permissionLauncher.launch(SimpleHealthConnectManager.PERMISSIONS)
                    }
                )
            }
            else -> {
                // Check if all permissions are granted
                var hasAllPermissions by remember { mutableStateOf(true) }
                
                LaunchedEffect(Unit) {
                    hasAllPermissions = healthManager.hasAllPermissions()
                }
                
                // Show info card if not all permissions granted
                if (!hasAllPermissions) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "Additional Metrics Available",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Grant additional permissions to see more health metrics like blood pressure, oxygen saturation, and body temperature.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            TextButton(
                                onClick = {
                                    permissionLauncher.launch(SimpleHealthConnectManager.PERMISSIONS)
                                }
                            ) {
                                Text("Grant More Permissions")
                            }
                        }
                    }
                }
                
                // Date navigation card
                DateNavigationCard(
                    selectedDate = selectedDate,
                    onPreviousDay = {
                        selectedDate = selectedDate.minusDays(1)
                    },
                    onNextDay = {
                        if (selectedDate < java.time.LocalDate.now()) {
                            selectedDate = selectedDate.plusDays(1)
                        }
                    },
                    onToday = {
                        selectedDate = java.time.LocalDate.now()
                    }
                )
                
                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    HealthStatsCards(healthStats, selectedDate)
                }
            }
        }
    }
    
    // Date picker dialog (outside the scrollable column)
    if (showDatePicker) {
        DatePickerDialog(
            selectedDate = selectedDate,
            onDateSelected = { date ->
                selectedDate = date
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false }
        )
    }
}

@Composable
private fun HealthConnectNotAvailableCard() {
    val context = LocalContext.current
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                Text(
                    "Health Connect Not Available",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Text(
                "Health Connect is required to access health data from Google Fit, Samsung Health, and other apps.",
                style = MaterialTheme.typography.bodyMedium
            )
            
            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("market://details?id=com.google.android.apps.healthdata")
                    }
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Download, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Install Health Connect")
            }
        }
    }
}

@Composable
private fun PermissionsNeededCard(onRequestPermissions: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Security,
                    contentDescription = null
                )
                Text(
                    "Permissions Needed",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Text(
                "Grant permissions to access your health data from Health Connect.",
                style = MaterialTheme.typography.bodyMedium
            )
            
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

@Composable
private fun DateNavigationCard(
    selectedDate: java.time.LocalDate,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onToday: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPreviousDay) {
                Icon(Icons.Default.ChevronLeft, "Previous Day")
            }
            
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = when {
                        selectedDate == java.time.LocalDate.now() -> "Today"
                        selectedDate == java.time.LocalDate.now().minusDays(1) -> "Yesterday"
                        else -> selectedDate.format(java.time.format.DateTimeFormatter.ofPattern("MMM dd, yyyy"))
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (selectedDate != java.time.LocalDate.now()) {
                    TextButton(onClick = onToday) {
                        Text("Go to Today")
                    }
                }
            }
            
            IconButton(
                onClick = onNextDay,
                enabled = selectedDate < java.time.LocalDate.now()
            ) {
                Icon(Icons.Default.ChevronRight, "Next Day")
            }
        }
    }
}

@Composable
private fun DatePickerDialog(
    selectedDate: java.time.LocalDate,
    onDateSelected: (java.time.LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = selectedDate.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    )
    
    androidx.compose.material3.DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        val date = java.time.Instant.ofEpochMilli(millis)
                            .atZone(java.time.ZoneId.systemDefault())
                            .toLocalDate()
                        onDateSelected(date)
                    }
                }
            ) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    ) {
        DatePicker(state = datePickerState)
    }
}

@Composable
private fun HealthStatsCards(stats: HealthStats, selectedDate: java.time.LocalDate) {
    // Summary Card
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                when {
                    selectedDate == java.time.LocalDate.now() -> "Today's Summary"
                    selectedDate == java.time.LocalDate.now().minusDays(1) -> "Yesterday's Summary"
                    else -> "Summary for ${selectedDate.format(java.time.format.DateTimeFormatter.ofPattern("MMM dd"))}"
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(
                    icon = "👣",
                    value = if (stats.steps > 0) stats.steps.toString() else "--",
                    label = "Steps"
                )
                StatItem(
                    icon = "🔥",
                    value = if (stats.calories > 0) String.format("%.0f", stats.calories) else "--",
                    label = "Calories"
                )
                StatItem(
                    icon = "❤️",
                    value = if (stats.heartRate > 0) stats.heartRate.toString() else "--",
                    label = "Heart Rate"
                )
            }
        }
    }
    
    // Activity Card
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Activity",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            if (stats.distance > 0) {
                StatRow(
                    icon = Icons.Default.DirectionsRun,
                    label = "Distance",
                    value = String.format("%.2f km", stats.distance)
                )
            }
            
            if (stats.activeMinutes > 0) {
                StatRow(
                    icon = Icons.Default.Timer,
                    label = "Active Minutes",
                    value = "${stats.activeMinutes} min"
                )
            }
            
            if (stats.distance == 0.0 && stats.activeMinutes == 0L) {
                Text(
                    "No activity data available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    
    // Sleep & Lifestyle Card
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Sleep & Lifestyle",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            if (stats.sleepHours > 0) {
                StatRow(
                    icon = Icons.Default.Bedtime,
                    label = "Sleep",
                    value = String.format("%.1f hours", stats.sleepHours)
                )
            }
            
            if (stats.floorsClimbed > 0) {
                StatRow(
                    icon = Icons.Default.Stairs,
                    label = "Floors Climbed",
                    value = "${stats.floorsClimbed}"
                )
            }
            
            if (stats.hydration > 0) {
                StatRow(
                    icon = Icons.Default.WaterDrop,
                    label = "Hydration",
                    value = String.format("%.2f L", stats.hydration)
                )
            }
            
            if (stats.sleepHours == 0.0 && stats.floorsClimbed == 0L && stats.hydration == 0.0) {
                Text(
                    "No sleep or lifestyle data available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    
    // Vitals Card
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Vitals",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            if (stats.heartRateMin > 0 && stats.heartRateMax > 0) {
                StatRow(
                    icon = Icons.Default.Favorite,
                    label = "Heart Rate Range",
                    value = "${stats.heartRateMin} - ${stats.heartRateMax} bpm"
                )
            }
            
            if (stats.restingHeartRate > 0) {
                StatRow(
                    icon = Icons.Default.FavoriteBorder,
                    label = "Resting Heart Rate",
                    value = "${stats.restingHeartRate} bpm"
                )
            }
            
            if (stats.bloodPressureSystolic > 0 && stats.bloodPressureDiastolic > 0) {
                StatRow(
                    icon = Icons.Default.MonitorHeart,
                    label = "Blood Pressure",
                    value = "${stats.bloodPressureSystolic}/${stats.bloodPressureDiastolic} mmHg"
                )
            }
            
            if (stats.oxygenSaturation > 0) {
                StatRow(
                    icon = Icons.Default.Air,
                    label = "Blood Oxygen",
                    value = String.format("%.1f%%", stats.oxygenSaturation)
                )
            }
            
            if (stats.heartRateMin == 0L && stats.heartRateMax == 0L && stats.restingHeartRate == 0L &&
                stats.bloodPressureSystolic == 0 && stats.oxygenSaturation == 0.0) {
                Text(
                    "No vitals data available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    
    // Body Metrics Card
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Body Metrics",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            if (stats.weight > 0) {
                StatRow(
                    icon = Icons.Default.Scale,
                    label = "Weight",
                    value = String.format("%.1f kg", stats.weight)
                )
            }
            
            if (stats.bodyTemperature > 0) {
                StatRow(
                    icon = Icons.Default.Thermostat,
                    label = "Body Temperature",
                    value = String.format("%.1f°C", stats.bodyTemperature)
                )
            }
            
            if (stats.bloodGlucose > 0) {
                StatRow(
                    icon = Icons.Default.Bloodtype,
                    label = "Blood Glucose",
                    value = String.format("%.1f mmol/L", stats.bloodGlucose)
                )
            }
            
            if (stats.vo2Max > 0) {
                StatRow(
                    icon = Icons.Default.FitnessCenter,
                    label = "VO2 Max",
                    value = String.format("%.1f mL/kg/min", stats.vo2Max)
                )
            }
            
            if (stats.weight == 0.0 && stats.bodyTemperature == 0.0 && 
                stats.bloodGlucose == 0.0 && stats.vo2Max == 0.0) {
                Text(
                    "No body metrics data available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    
    // Add bottom padding for scrolling (extra space for navigation bar)
    Spacer(modifier = Modifier.height(100.dp))
}

@Composable
private fun StatItem(
    icon: String,
    value: String,
    label: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            icon,
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null)
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
    }
}
