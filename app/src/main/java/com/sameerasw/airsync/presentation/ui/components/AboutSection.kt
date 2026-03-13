package com.sameerasw.airsync.presentation.ui.components

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.sameerasw.airsync.R

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AboutSection(
    modifier: Modifier = Modifier,
    onAvatarLongClick: () -> Unit = {},
    appName: String = "AirSync",
    developerName: String = "Sameera Wijerathna",
    description: String = "AirSync enables seamless synchronization between your Android device and Mac. Share notifications, clipboard content, and device status wirelessly over your local network."
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var showAppLogo by remember { mutableStateOf(false) }

    val versionName = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    } catch (_: Exception) {
        "Unknown"
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceBright
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "$appName v$versionName",
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center
            )

            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            AnimatedContent(
                targetState = showAppLogo,
                transitionSpec = {
                    fadeIn() togetherWith fadeOut()
                },
                label = "AvatarLogoToggle"
            ) { isLogoVisible ->
                if (isLogoVisible) {
                    RotatingAppIcon(
                        haptics = haptics,
                        modifier = Modifier
                            .size(200.dp)
                            .combinedClickable(
                                onClick = { showAppLogo = false },
                                onLongClick = { onAvatarLongClick() }
                            ),
                        isVisible = true
                    )
                } else {
                    Image(
                        painter = painterResource(id = R.drawable.avatar),
                        contentDescription = "Developer Avatar",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(120.dp)
                            .clip(RoundedCornerShape(32.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .combinedClickable(
                                onClick = { showAppLogo = true },
                                onLongClick = { onAvatarLongClick() }
                            )
                    )
                }
            }

            Text(
                text = "Developed by $developerName",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = 3
            ) {
                ActionButton(
                    text = "Website",
                    iconRes = R.drawable.rounded_web_traffic_24,
                    onClick = { openUrl(context, "https://www.sameerasw.com/airsync") }
                )
                ActionButton(
                    text = "Help",
                    iconRes = R.drawable.rounded_info_24,
                    onClick = { openUrl(context, "https://airsync.notion.site") }
                )
                ActionButton(
                    text = "GitHub",
                    iconRes = R.drawable.brand_github,
                    onClick = { openUrl(context, "https://github.com/sameerasw/airsync-android") }
                )
                ActionButton(
                    text = "Telegram",
                    iconRes = R.drawable.brand_telegram,
                    onClick = { openUrl(context, "https://t.me/tidwib") },
                    outlined = true
                )
                ActionButton(
                    text = "Support",
                    iconRes = R.drawable.rounded_heart_smile_24,
                    onClick = { openUrl(context, "https://buymeacoffee.com/sameerasw") },
                    outlined = true
                )
                ActionButton(
                    text = "AirSync+",
                    iconRes = R.drawable.rounded_devices_24,
                    onClick = { openUrl(context, "https://store.sameerasw.com") },
                    outlined = true
                )
            }

            val creditText = stringResource(id = R.string.label_app_icon_credits)
            val annotatedString = buildAnnotatedString {
                append(creditText)
                val startIndex = creditText.indexOf("@Syntrop2k2")
                val endIndex = startIndex + "@Syntrop2k2".length
                if (startIndex != -1) {
                    addStringAnnotation(
                        tag = "URL",
                        annotation = stringResource(id = R.string.url_syntrop_telegram),
                        start = startIndex,
                        end = endIndex
                    )
                    addStyle(
                        style = SpanStyle(
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            textDecoration = TextDecoration.Underline
                        ),
                        start = startIndex,
                        end = endIndex
                    )
                }
            }

            ClickableText(
                text = annotatedString,
                style = MaterialTheme.typography.bodyMedium.copy(
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                onClick = { offset ->
                    annotatedString.getStringAnnotations(tag = "URL", start = offset, end = offset)
                        .firstOrNull()?.let { annotation ->
                            openUrl(context, annotation.item)
                        }
                }
            )

            Text(
                text = "Other Apps",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = 3
            ) {
                OtherAppButton(
                    text = "Essentials",
                    iconRes = R.drawable.essentials_icon,
                    onClick = { openUrl(context, "https://github.com/sameerasw/essentials") }
                )
                OtherAppButton(
                    text = "ZenZero",
                    iconRes = R.drawable.rounded_web_24,
                    onClick = { openUrl(context, "https://sameerasw.com/zen") }
                )
                OtherAppButton(
                    text = "Canvas",
                    iconRes = R.drawable.rounded_draw_24,
                    onClick = { openUrl(context, "https://github.com/sameerasw/canvas") }
                )
                OtherAppButton(
                    text = "Tasks",
                    iconRes = R.drawable.rounded_task_alt_24,
                    onClick = { openUrl(context, "https://github.com/sameerasw/tasks") }
                )
            }

            Text(
                text = "With ❤️ from 🇱🇰",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ActionButton(
    text: String,
    iconRes: Int,
    onClick: () -> Unit,
    outlined: Boolean = false
) {
    if (outlined) {
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.padding(horizontal = 4.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Icon(
                painterResource(id = iconRes),
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    } else {
        Button(
            onClick = onClick,
            modifier = Modifier.padding(horizontal = 4.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Icon(
                painterResource(id = iconRes),
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun OtherAppButton(
    text: String,
    iconRes: Int,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 4.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Icon(
            painterResource(id = iconRes),
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

private fun openUrl(context: android.content.Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
        context.startActivity(intent)
    } catch (_: Exception) {
    }
}
