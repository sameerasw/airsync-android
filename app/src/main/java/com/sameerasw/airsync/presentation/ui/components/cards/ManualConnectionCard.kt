package com.sameerasw.airsync.presentation.ui.components.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sameerasw.airsync.R
import com.sameerasw.airsync.domain.model.UiState
import com.sameerasw.airsync.utils.HapticUtil

@Composable
fun ManualConnectionCard(
    isConnected: Boolean,
    lastConnected: Boolean,
    uiState: UiState,
    onIpChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onPcNameChange: (String) -> Unit,
    onIsPlusChange: (Boolean) -> Unit,
    onSymmetricKeyChange: (String) -> Unit,
    onConnect: () -> Unit,
    onQrScanClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraSmall,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceBright
        )
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            if (onQrScanClick != null) {
                Button(
                    onClick = {
                        HapticUtil.performClick(haptics)
                        onQrScanClick()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.rounded_qr_code_scanner_24),
                        contentDescription = stringResource(R.string.scan_qr_code),
                        modifier = Modifier
                            .size(20.dp)
                            .padding(end = 8.dp)
                    )
                    Text(stringResource(R.string.scan_qr_code))
                }
            }
            OutlinedTextField(
                value = uiState.ipAddress,
                onValueChange = onIpChange,
                label = { Text(stringResource(R.string.label_ip_address)) },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            OutlinedTextField(
                value = uiState.port,
                onValueChange = onPortChange,
                label = { Text(stringResource(R.string.label_port)) },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            OutlinedTextField(
                value = uiState.manualPcName,
                onValueChange = onPcNameChange,
                label = { Text(stringResource(R.string.label_pc_name_optional)) },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            )
            OutlinedTextField(
                value = uiState.symmetricKey ?: "",
                onValueChange = onSymmetricKeyChange,
                label = { Text(stringResource(R.string.label_encryption_key)) },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.label_airsync_plus), color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = uiState.manualIsPlus,
                    onCheckedChange = { enabled ->
                        if (enabled) HapticUtil.performToggleOn(haptics) else HapticUtil.performToggleOff(
                            haptics
                        )
                        onIsPlusChange(enabled)
                    }
                )
            }
            Button(
                onClick = {
                    HapticUtil.performClick(haptics)
                    onConnect()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.connect))
            }
        }
    }
}
