package com.sameerasw.airsync.presentation.ui.components.buttons

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sameerasw.airsync.R
import com.sameerasw.airsync.utils.HapticUtil

@Composable
fun ListExpandToggleButton(
    isExpanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    title: Any = R.string.action_manual_connection,
    description: Any? = null,
    expandedText: String? = null,
    collapsedText: String? = null,
    iconRes: Int? = null,
) {
    val haptics = LocalHapticFeedback.current

    val rotationDegree by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        label = "list_expand_chevron_rotation",
    )

    val expText =
        expandedText ?: when (title) {
            is Int -> stringResource(title)
            is String -> title
            else -> title.toString()
        }
    val colText =
        collapsedText ?: when (description) {
            is Int -> stringResource(description)
            is String -> description
            null -> expText
            else -> description.toString()
        }

    Box(
        modifier = modifier.fillMaxWidth()
    ) {
        Button(
            onClick = {
                HapticUtil.performClick(haptics)
                onToggle()
            },
            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceBright,
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Icon(
                painter =
                    painterResource(
                        id = iconRes ?: R.drawable.rounded_keyboard_arrow_down_24,
                    ),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier
                        .size(22.dp)
                        .then(
                            if (iconRes == null) {
                                Modifier.graphicsLayer { rotationZ = rotationDegree }
                            } else {
                                Modifier
                            },
                        ),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = if (isExpanded) expText else colText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
