package com.sameerasw.airsync.island

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.sameerasw.airsync.data.local.DataStoreManager
import com.sameerasw.airsync.utils.MacDeviceStatusManager
import com.sameerasw.airsync.utils.MacRemoteActions
import com.sameerasw.airsync.utils.WebSocketUtil
import com.sameerasw.island.sdk.HostInfo
import com.sameerasw.island.sdk.IslandHostHandle
import com.sameerasw.island.sdk.compose.ComposeIslandProviderService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class AirSyncIslandService : ComposeIslandProviderService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var publisher: Job? = null
    private val state = MutableStateFlow(AirSyncIslandState())

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onAttached(host: IslandHostHandle, info: HostInfo) {
        publisher?.cancel()
        val store = DataStoreManager.getInstance(applicationContext)
        publisher = scope.launch {
            combine(
                MacDeviceStatusManager.macDeviceStatus,
                WebSocketUtil.connectionState,
                store.getLastConnectedDevice(),
                store.getIslandEnabled(),
                store.getIslandShowBattery(),
            ) { status, socketConnected, device, enabled, showBattery ->
                val statusName = status?.name?.takeIf { it.isNotBlank() && it != "Unknown" }
                AirSyncIslandState(
                    enabled = enabled,
                    connected = socketConnected || WebSocketUtil.isConnected(),
                    hasDevice = device != null,
                    name = statusName ?: device?.name.orEmpty(),
                    model = device?.model,
                    deviceType = device?.deviceType,
                    batteryLevel = status?.battery?.level ?: -1,
                    isCharging = status?.battery?.isCharging ?: false,
                    showBattery = showBattery,
                )
            }.distinctUntilChanged().collect { next ->
                state.value = next
                val content = AirSyncIslandPayload.build(applicationContext, next)
                host.publish(content.payload, content.assets)
            }
        }
    }

    override fun onHostDetached() {
        publisher?.cancel()
        publisher = null
        super.onHostDetached()
    }

    override fun hasExpandedContent(itemKey: String): Boolean = itemKey == AirSyncIslandPayload.ITEM_KEY

    @Composable
    override fun ExpandedContent(itemKey: String) {
        val current by state.collectAsState()
        AirSyncIslandExpanded(
            state = current,
            host = hostInfoState.value,
            onOpenApp = { host?.openApp(itemKey) },
            onLock = {
                host?.keepAlive(itemKey)
                MacRemoteActions.sendRemoteAction(MacRemoteActions.LOCK_SCREEN)
            },
            onScreensaver = {
                host?.keepAlive(itemKey)
                MacRemoteActions.sendRemoteAction(MacRemoteActions.SCREENSAVER)
            },
            onDisconnect = { MacRemoteActions.disconnect(applicationContext) },
            onReconnect = { scope.launch(Dispatchers.IO) { MacRemoteActions.reconnect(applicationContext) } },
            onHeight = { host?.resize(itemKey, it) },
            onSnapshot = { host?.snapshot(itemKey, it) },
        )
    }
}
