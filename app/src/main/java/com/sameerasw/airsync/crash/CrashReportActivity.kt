package com.sameerasw.airsync.crash

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.sameerasw.airsync.ui.theme.AirSyncTheme
import java.io.File

class CrashReportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val showReport = intent.getBooleanExtra("show_report", false)
        if (!showReport) {
            finish()
            return
        }

        val reportFile = File(filesDir, "last_crash.log")
        val reportText = if (reportFile.exists()) {
            reportFile.readText()
        } else {
            "No crash log found."
        }

        setContent {
            val viewModel: com.sameerasw.airsync.presentation.viewmodel.AirSyncViewModel =
                androidx.lifecycle.viewmodel.compose.viewModel {
                    com.sameerasw.airsync.presentation.viewmodel.AirSyncViewModel.create(this@CrashReportActivity)
                }
            val uiState by viewModel.uiState.collectAsState()

            AirSyncTheme(pitchBlackTheme = uiState.isPitchBlackThemeEnabled) {
                CrashReportBottomSheet(
                    report = reportText,
                    onDismiss = {
                        finish()
                    }
                )
            }
        }
    }
}
