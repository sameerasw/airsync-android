package com.sameerasw.airsync.presentation.ui.components.cards

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sameerasw.airsync.utils.FileReceiveManager
import kotlinx.coroutines.delay

/**
 * Card showing current incoming file transfer progress
 * Automatically observes FileReceiveManager's active transfers
 */
@Composable
fun IncomingFileTransferCard(
    modifier: Modifier = Modifier
) {
    val activeTransfers by FileReceiveManager.activeTransfersFlow.collectAsState()
    
    // Force recomposition every second to update elapsed time
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(activeTransfers.isNotEmpty()) {
        while (activeTransfers.isNotEmpty()) {
            delay(1000)
            tick = System.currentTimeMillis()
        }
    }
    
    // Convert to display format
    val transfers = activeTransfers.map { (id, state) ->
        FileTransferInfo(
            id = id,
            fileName = state.fileName,
            totalSize = state.fileSize,
            receivedSize = state.bytesReceived,
            progress = if (state.fileSize > 0) state.bytesReceived.toFloat() / state.fileSize.toFloat() else 0f,
            status = when (state.status) {
                FileReceiveManager.TransferStatus.PENDING -> TransferStatus.PENDING
                FileReceiveManager.TransferStatus.TRANSFERRING -> TransferStatus.TRANSFERRING
                FileReceiveManager.TransferStatus.COMPLETED -> TransferStatus.COMPLETED
                FileReceiveManager.TransferStatus.FAILED -> TransferStatus.FAILED
                FileReceiveManager.TransferStatus.CANCELLED -> TransferStatus.CANCELLED
            },
            elapsedTimeMs = state.elapsedTimeMs,
            transferSpeed = state.transferSpeed
        )
    }
    
    AnimatedVisibility(
        visible = transfers.isNotEmpty(),
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .animateContentSize(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
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
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudDownload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Receiving Files",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                // Transfer items
                transfers.forEach { transfer ->
                    FileTransferItem(transfer)
                }
            }
        }
    }
}

@Composable
private fun FileTransferItem(transfer: FileTransferInfo) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // File name row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = transfer.fileName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            
            // Status icon
            when (transfer.status) {
                TransferStatus.COMPLETED -> {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Completed",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                TransferStatus.FAILED -> {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = "Failed",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }
                TransferStatus.CANCELLED -> {
                    Icon(
                        imageVector = Icons.Default.Cancel,
                        contentDescription = "Cancelled",
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(20.dp)
                    )
                }
                else -> {
                    Text(
                        text = "${(transfer.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        
        // Progress info row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Size info
            Text(
                text = "${formatFileSize(transfer.receivedSize)} / ${formatFileSize(transfer.totalSize)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            // Duration and speed
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Elapsed time
                Text(
                    text = formatDuration(transfer.elapsedTimeMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                // Transfer speed (only when transferring)
                if (transfer.status == TransferStatus.TRANSFERRING && transfer.transferSpeed > 0) {
                    Text(
                        text = "• ${formatSpeed(transfer.transferSpeed)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        
        // Progress bar
        if (transfer.status == TransferStatus.TRANSFERRING) {
            LinearProgressIndicator(
                progress = { transfer.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}

data class FileTransferInfo(
    val id: String,
    val fileName: String,
    val totalSize: Long,
    val receivedSize: Long,
    val progress: Float,
    val status: TransferStatus,
    val elapsedTimeMs: Long = 0,
    val transferSpeed: Long = 0 // bytes per second
)

enum class TransferStatus {
    PENDING, TRANSFERRING, COMPLETED, FAILED, CANCELLED
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    
    return when {
        hours > 0 -> String.format("%d:%02d:%02d", hours, minutes % 60, seconds % 60)
        minutes > 0 -> String.format("%d:%02d", minutes, seconds % 60)
        else -> String.format("0:%02d", seconds)
    }
}

private fun formatSpeed(bytesPerSecond: Long): String {
    val kbps = bytesPerSecond / 1024.0
    val mbps = kbps / 1024.0
    
    return when {
        mbps >= 1 -> String.format("%.1f MB/s", mbps)
        kbps >= 1 -> String.format("%.0f KB/s", kbps)
        else -> "$bytesPerSecond B/s"
    }
}
