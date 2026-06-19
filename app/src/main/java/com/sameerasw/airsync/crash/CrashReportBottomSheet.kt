package com.sameerasw.airsync.crash

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sameerasw.airsync.R
import com.sameerasw.airsync.presentation.ui.components.RoundedCardContainer
import com.sameerasw.airsync.presentation.ui.components.cards.IconToggleItem
import java.io.OutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrashReportBottomSheet(
    report: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.crash_report_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 8.dp, top = 8.dp)
                )
            }

            item {
                RoundedCardContainer {
                    // Copy report
                    IconToggleItem(
                        title = stringResource(R.string.crash_copy),
                        description = stringResource(R.string.crash_copy_desc),
                        iconRes = R.drawable.ic_clipboard_24,
                        showToggle = false,
                        onClick = {
                            copyToClipboard(context, report)
                        }
                    )

                    // Save to file
                    IconToggleItem(
                        title = stringResource(R.string.crash_save_file),
                        description = stringResource(R.string.crash_save_file_desc),
                        iconRes = R.drawable.outline_downloading_24,
                        showToggle = false,
                        onClick = {
                            saveLogToFile(context, report, showToast = true)
                        }
                    )

                    // Email report
                    IconToggleItem(
                        title = stringResource(R.string.crash_email),
                        description = stringResource(R.string.crash_email_desc),
                        iconRes = R.drawable.outline_feedback_24,
                        showToggle = false,
                        onClick = {
                            emailReport(context, report)
                        }
                    )

                    // Open GitHub issue
                    IconToggleItem(
                        title = stringResource(R.string.crash_github),
                        description = stringResource(R.string.crash_github_desc),
                        iconRes = R.drawable.brand_github,
                        showToggle = false,
                        onClick = {
                            openGitHubIssue(context, report)
                        }
                    )
                }
            }

            item {
                Box(modifier = Modifier.padding(bottom = 32.dp))
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("AirSync Crash Report", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Report copied to clipboard", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Failed to copy report", Toast.LENGTH_SHORT).show()
    }
}

private fun saveLogToFile(context: Context, text: String, showToast: Boolean): Uri? {
    try {
        val filename = "airsync_crash_${System.currentTimeMillis()}.log"
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        if (uri != null) {
            val outputStream: OutputStream? = resolver.openOutputStream(uri)
            outputStream?.use {
                it.write(text.toByteArray())
            }
            if (showToast) {
                Toast.makeText(context, context.getString(R.string.crash_file_saved), Toast.LENGTH_LONG).show()
            }
            return uri
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    Toast.makeText(context, context.getString(R.string.crash_file_save_failed), Toast.LENGTH_SHORT).show()
    return null
}

private fun emailReport(context: Context, text: String) {
    try {
        val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:mail@sameerasw.com")
            putExtra(Intent.EXTRA_SUBJECT, "AirSync Android Crash Report")
            val bodyText = if (text.length > 4000) text.substring(0, 4000) + "\n...[truncated]" else text
            putExtra(Intent.EXTRA_TEXT, bodyText)
        }
        context.startActivity(Intent.createChooser(emailIntent, "Send Email"))
    } catch (e: Exception) {
        Toast.makeText(context, "No email client found", Toast.LENGTH_SHORT).show()
    }
}

private fun openGitHubIssue(context: Context, text: String) {
    try {
        val uri = saveLogToFile(context, text, showToast = false)
        if (uri != null) {
            Toast.makeText(context, context.getString(R.string.crash_github_hint), Toast.LENGTH_LONG).show()
        }
        val url = "https://github.com/sameerasw/airsync-android/issues/new"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Could not open link", Toast.LENGTH_SHORT).show()
    }
}
