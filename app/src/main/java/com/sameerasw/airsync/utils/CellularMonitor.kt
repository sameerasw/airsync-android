package com.sameerasw.airsync.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

object CellularMonitor {
    var currentNetworkState: String? = null
    private var lastKnownBaseNetworkType: Int = TelephonyManager.NETWORK_TYPE_UNKNOWN
    private var lastKnownOverrideType: Int = TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE
    private var currentServiceState: Int = android.telephony.ServiceState.STATE_IN_SERVICE
    private var telephonyCallback: TelephonyCallback? = null
    private var activeDataSubId: Int = android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID
    private var subscriptionListener: android.telephony.SubscriptionManager.OnSubscriptionsChangedListener? = null

    fun start(context: Context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val subManager = context.getSystemService(android.telephony.SubscriptionManager::class.java)
        subscriptionListener = object : android.telephony.SubscriptionManager.OnSubscriptionsChangedListener() {
            override fun onSubscriptionsChanged() {
                val newSubId = android.telephony.SubscriptionManager.getDefaultDataSubscriptionId()
                if (newSubId != activeDataSubId && newSubId != android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    bindToSubscription(context, newSubId)
                }
            }
        }
        subManager.addOnSubscriptionsChangedListener(context.mainExecutor, subscriptionListener!!)

        val initialSubId = android.telephony.SubscriptionManager.getDefaultDataSubscriptionId()
        bindToSubscription(context, initialSubId)
    }

    private fun bindToSubscription(context: Context, subId: Int) {
        val defaultTm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let { callback ->
                val oldTm = if (activeDataSubId != android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    defaultTm.createForSubscriptionId(activeDataSubId)
                } else {
                    defaultTm
                }
                oldTm.unregisterTelephonyCallback(callback)
            }

            val newTm = if (subId != android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                defaultTm.createForSubscriptionId(subId)
            } else {
                defaultTm
            }

            telephonyCallback = object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener, TelephonyCallback.ServiceStateListener {
                override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
                    lastKnownBaseNetworkType = telephonyDisplayInfo.networkType
                    lastKnownOverrideType = telephonyDisplayInfo.overrideNetworkType
                    evaluateAndPushState(context)
                }

                override fun onServiceStateChanged(serviceState: android.telephony.ServiceState) {
                    currentServiceState = serviceState.state
                    evaluateAndPushState(context)
                }
            }

            newTm.registerTelephonyCallback(context.mainExecutor, telephonyCallback!!)
            activeDataSubId = subId
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                lastKnownBaseNetworkType = newTm.dataNetworkType
            }
            evaluateAndPushState(context)
        }
    }

    fun stop(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val defaultTm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                if (defaultTm != null) {
                    val telephonyManager = if (activeDataSubId != android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                        defaultTm.createForSubscriptionId(activeDataSubId)
                    } else {
                        defaultTm
                    }

                    telephonyCallback?.let {
                        telephonyManager.unregisterTelephonyCallback(it)
                    }
                }
            }

            subscriptionListener?.let {
                val subManager = context.getSystemService(android.telephony.SubscriptionManager::class.java)
                subManager?.removeOnSubscriptionsChangedListener(it)
            }
        } finally {
            telephonyCallback = null
            subscriptionListener = null
            activeDataSubId = android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID
            currentNetworkState = null
        }
    }

    private fun evaluateAndPushState(context: Context) {
        val newState = if (currentServiceState == android.telephony.ServiceState.STATE_OUT_OF_SERVICE || 
                           currentServiceState == android.telephony.ServiceState.STATE_EMERGENCY_ONLY || 
                           currentServiceState == android.telephony.ServiceState.STATE_POWER_OFF) {
            "NO_SIGNAL"
        } else if (lastKnownBaseNetworkType == TelephonyManager.NETWORK_TYPE_NR) {
            "5G_SA"
        } else if (lastKnownOverrideType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED) {
            "5G_NSA+"
        } else if (lastKnownOverrideType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA) {
            "5G_NSA"
        } else if (lastKnownBaseNetworkType == TelephonyManager.NETWORK_TYPE_LTE) {
            "LTE"
        } else {
            "4G/Below"
        }

        if (currentNetworkState != newState) {
            currentNetworkState = newState
            SyncManager.checkAndSyncDeviceStatus(context, forceSync = true)
        }
    }
}
