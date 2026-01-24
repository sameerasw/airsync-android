package com.sameerasw.airsync.presentation.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sameerasw.airsync.utils.FileTransferUtil
import com.sameerasw.airsync.utils.WebSocketUtil
import kotlinx.coroutines.launch

data class TransferItem(
    val id: String,
    val name: String,
    val size: Long,
    val progress: Float,
    val status: TransferStatus
)

enum class TransferStatus {
    PENDING, TRANSFERRING, COMPLETED, FAILED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileTransferScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var transferItems by remember { mutableStateOf<List<TransferItem>>(emptyList()) }
    var selectedFiles by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var isConnected by remember { mutableStateOf(WebSocketUtil.isConnected()) }
    var showError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    
    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            selectedFiles = uris
            android.util.Log.d("FileTransferScreen", "Selected ${uris.size} files")
        }
    }
    
    // Function to send selected files
    fun sendSelectedFiles() {
        if (selectedFiles.isEmpty()) return
        
        scope.launch {
            if (!WebSocketUtil.isConnected()) {
                errorMessage = "Not connected to Mac. Please connect first."
                showError = true
                return@launch
            }
            
            isSending = true
            
            // Send files sequentially to avoid state conflicts
            for (uri in selectedFiles) {
                try {
                    val fileName = FileTransferUtil.getFileName(context, uri)
                    val fileSize = FileTransferUtil.getFileSize(context, uri)
                    val id = uri.toString()
                    
                    // Add to transfer list on main thread
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        transferItems = transferItems + TransferItem(
                            id = id,
                            name = fileName,
                            size = fileSize,
                            progress = 0f,
                            status = TransferStatus.PENDING
                        )
                    }
                    
                    // Send file
                    FileTransferUtil.sendFile(context, uri) { progress, status ->
                        // Update transfer item progress on main thread
                        scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                            transferItems = transferItems.map { item ->
                                if (item.id == id) {
                                    item.copy(progress = progress, status = status)
                                } else {
                                    item
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        errorMessage = "Failed to send file: ${e.message}"
                        showError = true
                    }
                    android.util.Log.e("FileTransferScreen", "Error sending file", e)
                }
            }
            
            // Clear selected files after sending
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                selectedFiles = emptyList()
                isSending = false
            }
        }
    }
    
    // Check connection status periodically
    LaunchedEffect(Unit) {
        while (true) {
            isConnected = WebSocketUtil.isConnected()
            kotlinx.coroutines.delay(1000)
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("File Transfer") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Connection status card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isConnected) 
                        MaterialTheme.colorScheme.primaryContainer 
                    else 
                        MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (isConnected) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null
                        )
                        Text(
                            if (isConnected) "Connected to Mac" else "Not Connected",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            
            // Transfer options
            Text(
                "Send to Mac",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            
            Button(
                onClick = { filePickerLauncher.launch("*/*") },
                modifier = Modifier.fillMaxWidth(),
                enabled = isConnected && !isSending
            ) {
                Icon(Icons.Default.AttachFile, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Select Files")
            }
            
            // Show selected files
            if (selectedFiles.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "${selectedFiles.size} file(s) selected",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        
                        selectedFiles.take(3).forEach { uri ->
                            Text(
                                "• ${FileTransferUtil.getFileName(context, uri)}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        
                        if (selectedFiles.size > 3) {
                            Text(
                                "... and ${selectedFiles.size - 3} more",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { sendSelectedFiles() },
                                modifier = Modifier.weight(1f),
                                enabled = !isSending
                            ) {
                                if (isSending) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Text(if (isSending) "Sending..." else "Send Now")
                            }
                            
                            OutlinedButton(
                                onClick = { selectedFiles = emptyList() },
                                modifier = Modifier.weight(1f),
                                enabled = !isSending
                            ) {
                                Text("Cancel")
                            }
                        }
                    }
                }
            }
            
            if (!isConnected) {
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
                            "Connection Required",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Please connect to your Mac from the main screen to enable file transfer.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            
            // Transfer list
            if (transferItems.isNotEmpty()) {
                Text(
                    "Transfers",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(transferItems) { item ->
                        TransferItemCard(item)
                    }
                }
            }
        }
        
        // Error dialog
        if (showError) {
            AlertDialog(
                onDismissRequest = { showError = false },
                title = { Text("Error") },
                text = { Text(errorMessage) },
                confirmButton = {
                    TextButton(onClick = { showError = false }) {
                        Text("OK")
                    }
                }
            )
        }
    }
}

@Composable
private fun TransferItemCard(item: TransferItem) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        formatFileSize(item.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Icon(
                    when (item.status) {
                        TransferStatus.PENDING -> Icons.Default.Schedule
                        TransferStatus.TRANSFERRING -> Icons.Default.CloudUpload
                        TransferStatus.COMPLETED -> Icons.Default.CheckCircle
                        TransferStatus.FAILED -> Icons.Default.Error
                    },
                    contentDescription = null,
                    tint = when (item.status) {
                        TransferStatus.COMPLETED -> MaterialTheme.colorScheme.primary
                        TransferStatus.FAILED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            
            if (item.status == TransferStatus.TRANSFERRING) {
                LinearProgressIndicator(
                    progress = { item.progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "${(item.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024 * 1024 * 1024)} GB"
    }
}
