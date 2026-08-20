# AirSync Active: Background Toggle Feature

This document explains the implementation and usage of the **AirSync Active** background toggle feature, designed to give users complete control over background energy consumption when synchronization with macOS is not needed.

---

## 🔋 Problem Statement & Motivation
AirSync mirrors notifications, clipboard entries, and media controls by keeping a persistent connection between macOS and Android. To facilitate this:
1. **Bluetooth LE (BLE) Scanning**: Android background loops scan continuously to detect the Mac.
2. **Bonjour (mDNS) Discovery**: The app runs multicast socket queries to resolve local network hosts.
3. **WebSocket Retries**: Reconnection threads attempt TCP handshakes periodically.

While essential for seamless auto-pairing, these continuous background tasks cause battery drain when the user is away from their Mac or wishes to pause synchronization.

---

## 🛠 Feature Overview
The **AirSync Active** toggle (located in **Settings**) provides a master hardware-level connection lock:
- **Toggle ON (Default)**: Normal background connectivity, scanning, and automatic reconnection loops.
- **Toggle OFF**: Completely suspends and blocks all background scanning, BLE advertiser broadcasts, and WebSocket client attempts, placing the background service in a low-power idle state.

---

## 💻 Code Changes & Implementation Details

1. **Preference Persistence (`DataStoreManager.kt`)**
   - Registered a new boolean configuration key: `AIRSYNC_ACTIVE`.
   - Defaults to `true` to preserve out-of-the-box auto-pairing behavior.

2. **UI Settings Screen (`SettingsView.kt`)**
   - Implemented a graphical toggle switch in the settings list showing the active/inactive state of background scanning.
   - Triggers state updates in the shared view model.

3. **Background Scanning Gates (`AirSyncService.kt` / `BleConnectionManager.kt` / `DiscoveryOrchestrator.kt`)**
   - Connected preference listener callbacks to interrupt connection attempts.
   - When disabled:
     - Terminates active BLE discovery channels.
     - Interrupts WebSocket reconnection timers.
     - Closes existing client connections immediately.

---

## ⚠️ Known Limitations & Integration
- **Manual Reconnect**: Auto-reconnection will remain suspended until the user toggles the switch back on.
- **Upstream Project Status**: This feature is currently submitted as a pull request ([sameerasw/airsync-android#132](https://github.com/sameerasw/airsync-android/pull/132)) linked to feature request ([sameerasw/airsync-android#131](https://github.com/sameerasw/airsync-android/issues/131)).
