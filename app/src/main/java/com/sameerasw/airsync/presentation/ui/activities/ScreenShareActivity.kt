package com.sameerasw.airsync.presentation.ui.activities

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.sameerasw.airsync.domain.model.MirroringOptions
import com.sameerasw.airsync.service.ScreenCaptureService
import com.sameerasw.airsync.utils.MirrorRequestHelper
import com.sameerasw.airsync.data.local.DataStoreManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ScreenShareActivity : ComponentActivity() {
    
    companion object {
        private const val TAG = "ScreenShareActivity"
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            if (data != null) {
                val mirroringOptions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(ScreenCaptureService.EXTRA_MIRRORING_OPTIONS, MirroringOptions::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(ScreenCaptureService.EXTRA_MIRRORING_OPTIONS)
                }

                mirroringOptions?.let {
                    Log.d(TAG, "Permission granted, starting ScreenCaptureService")
                    
                    // Store that we have permission for future auto-approve
                    CoroutineScope(Dispatchers.IO).launch {
                        val dataStore = DataStoreManager(this@ScreenShareActivity)
                        dataStore.setMirrorPermission(true)
                    }
                    
                    val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                        action = ScreenCaptureService.ACTION_START
                        putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                        putExtra(ScreenCaptureService.EXTRA_DATA, data)
                        putExtra(ScreenCaptureService.EXTRA_MIRRORING_OPTIONS, it)
                    }
                    startForegroundService(serviceIntent)
                }
            }
        } else {
            Log.d(TAG, "Permission denied by user")
        }
        
        // Reset pending flag and finish
        MirrorRequestHelper.resetPendingFlag()
        finish() // Finish the activity regardless of the result
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val screenCaptureIntent = mediaProjectionManager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(screenCaptureIntent)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Ensure pending flag is reset if activity is destroyed
        MirrorRequestHelper.resetPendingFlag()
    }
}
