package com.sameerasw.airsync.presentation.ui.components.cards

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun DeviceInfoCard(
    deviceName: String,
    localIp: String,
    onDeviceNameChange: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("My Android", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Local IP: $localIp", style = MaterialTheme.typography.bodyMedium)

            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = deviceName,
                onValueChange = onDeviceNameChange,
                label = { Text("Device Name") },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            )
        }
    }
}
