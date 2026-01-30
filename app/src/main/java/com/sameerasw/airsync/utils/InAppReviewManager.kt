package com.sameerasw.airsync.utils

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.play.core.review.ReviewManagerFactory

object InAppReviewManager {
    private const val TAG = "InAppReviewManager"

    /**
     * Triggers the In-App Review flow.
     * Note: This should be called with an Activity context.
     * 
     * @param context The context to use. Must be or contain an Activity.
     * @param onComplete Callback when the flow is finished (regardless of success/failure)
     */
    fun launchReviewFlow(context: Context, onComplete: () -> Unit = {}) {
        val manager = ReviewManagerFactory.create(context)
        val request = manager.requestReviewFlow()
        
        request.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val reviewInfo = task.result
                if (context is Activity) {
                    val flow = manager.launchReviewFlow(context, reviewInfo)
                    flow.addOnCompleteListener { _ ->
                        // Flow finished, we don't know if they actually rated or not
                        onComplete()
                    }
                } else {
                    Log.e(TAG, "Context is not an Activity, cannot launch review flow")
                    onComplete()
                }
            } else {
                Log.e(TAG, "Failed to request review flow: ${task.exception?.message}")
                onComplete()
            }
        }
    }
}
