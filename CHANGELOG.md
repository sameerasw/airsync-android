# AirSync Changelog

## Latest Session - Critical Fixes (October 30, 2025)

### Android Fixes

#### 1. ✅ Mac Media Control in Android Sidebar
**Problem**: 
- Mac media icon only showing when song is playing on Mac
- Android unable to control Mac music (play/pause/next/previous)
- Logs showed: "Skipping media control 'pause' - currently receiving playing media from Mac"

**Root Causes**:
1. `MacMediaPlayerService.sendMacMediaControl()` was checking `shouldSendMediaControl()` before sending ANY command, blocking user-initiated controls
2. `MacDeviceStatusManager` only showed notification when `isPlaying` was true, causing it to disappear when paused

**Solution**:
- **MacMediaPlayerService.kt**: Removed `shouldSendMediaControl()` check from control commands - user actions now always send to Mac
- **MacDeviceStatusManager.kt**: Changed visibility logic to show notification whenever there's title OR artist info (removed `isPlaying` requirement)

**Files Modified**:
- `app/src/main/java/com/sameerasw/airsync/service/MacMediaPlayerService.kt`
- `app/src/main/java/com/sameerasw/airsync/utils/MacDeviceStatusManager.kt`

#### 2. ✅ Screen Capture Service Invalid Parameters
**Problem**: Service crashed with "Invalid start parameters for screen capture. Stopping service."

**Root Cause**: Wrong intent extra key - using `"mirroringOptions"` instead of `EXTRA_MIRRORING_OPTIONS`

**Solution**: Fixed intent extra key in screen capture launcher callback

**Files Modified**:
- `app/src/main/java/com/sameerasw/airsync/presentation/ui/screens/AirSyncMainScreen.kt` (line 167)

#### 3. ✅ Expand Networking Text Overflow
**Problem**: Descriptive text overflowing into toggle button

**Solution**: 
- Added `weight(1f)` to Column to constrain width
- Added `padding(end = 8.dp)` for spacing
- Added `verticalAlignment` to Row for proper alignment

**Files Modified**:
- `app/src/main/java/com/sameerasw/airsync/presentation/ui/components/cards/ExpandNetworkingCard.kt`

#### 4. ✅ Mirror Request Not Showing When App Minimized
**Problem**: Mirror requests not appearing when app is in background - no notification shown

**Solution**: Added notification support in MirrorRequestHelper
- Shows high-priority notification when mirror request received
- Notification opens app with mirror request intent
- Prevents missed requests when app is minimized

**Files Modified**:
- `app/src/main/java/com/sameerasw/airsync/utils/MirrorRequestHelper.kt`

---

### macOS Fixes

#### 1. ✅ macOS Threading Crashes (Multiple Scenarios)
**Problem**: App crashed with `EXC_BAD_ACCESS` in multiple scenarios:
- File transfer complete (transferVerified)
- Status updates from Android
- Clipboard sync
- Device connection
- Mirror requests

**Root Cause**: Multiple AppState methods being called from background WebSocket thread instead of main thread

**Solution**: Wrapped ALL AppState calls in `DispatchQueue.main.async`:
- `AppState.shared.device = ...` (device connection)
- `AppState.shared.status = ...` (status updates)
- `AppState.shared.updateClipboardFromAndroid(...)` (clipboard)
- `AppState.shared.startIncomingTransfer(...)` (file init)
- `AppState.shared.updateIncomingProgress(...)` (file chunks)
- `AppState.shared.completeIncoming(...)` (file complete)
- `AppState.shared.completeOutgoingVerified(...)` (file verified)
- `AppState.shared.postNativeNotification(...)` (all notifications)
- `AppState.shared.scrcpyBitrate/scrcpyResolution` (scrcpy settings)
- Flexible message handlers (device/status)

**Files Modified**: 
- `airsync-mac/Core/WebSocket/WebSocketServer.swift` (10+ threading issues fixed)

#### 2. ✅ Mirror Request Button Stays Greyed After Cancel
**Problem**: Mac button stays disabled after user cancels Android permission dialog

**Root Cause**: `stopMirroring()` sets `isMirrorRequestPending = true` but never resets it when Android doesn't respond

**Solution**: Added 3-second timeout in `stopMirroring()` to reset `isMirrorRequestPending` state if no response received

**Files Modified**:
- `airsync-mac/Core/WebSocket/WebSocketServer.swift`

#### 3. ✅ Hardware Decoder Optimization
**Problem**: Poor FPS, high latency, frame drops during screen mirroring

**Root Cause**: VideoToolbox decoder not explicitly requesting hardware acceleration

**Solution**: Enhanced H264Decoder.swift
- Added `kVTVideoDecoderSpecification_EnableHardwareAcceleratedVideoDecoder: true`
- Added `kVTVideoDecoderSpecification_RequireHardwareAcceleratedVideoDecoder: true`
- Forces hardware acceleration for better performance and lower latency

**Files Modified**:
- `airsync-mac/Screens/Settings/H264Decoder.swift`

---

## Previous Session - UI and Mirroring Fixes

### 1. ✅ Stop Mirroring Button Opening Start Dialog
**Problem**: Clicking "Stop Mirroring" was opening the start mirroring permission dialog

**Root Cause**: Duplicate mirroring button in ConnectionStatusCard conflicting with standalone button

**Solution**:
- Removed mirroring button from ConnectionStatusCard
- Kept only the standalone button below connection card
- Button now properly shows:
  - "📱 Start Mirroring" when idle (OutlinedButton)
  - "⏹ Stop Mirroring" when active (Error-colored Button)

**Files Modified**:
- `app/src/main/java/com/sameerasw/airsync/presentation/ui/screens/AirSyncMainScreen.kt`
- `app/src/main/java/com/sameerasw/airsync/presentation/ui/components/cards/ConnectionStatusCard.kt`

---

## Latest Hotfixes (October 30, 2025 - Evening)

### Android
- ✅ **Fixed screen capture invalid parameters** - Added default MirroringOptions when null
- ✅ **Added file transfer rate limiting** - 10ms delay every 10 chunks to prevent network overload
- ✅ **Fixed file transfer screen crash** - Send files sequentially, update UI on main thread

### macOS  
- ✅ **Fixed mirror button stuck disabled** - Reset mirror state on disconnect
- ✅ **Fixed large file checksum mismatch** - Added serial queue for file operations, wait for all chunks before verification
- ✅ **Improved file transfer reliability** - Thread-safe chunk writing with proper synchronization
- ✅ **Fixed Mac media control** - Use CGEvent instead of AppleScript (no permissions needed)
- ✅ **Fixed mirror request crash** - Read AppState on main thread before processing
- ✅ **Android can now control Mac music** - Play/pause/next/previous working

### Known Issues
- Mac media info (album art) may not display on Android - investigating
- Scrolling during mirroring may be glitchy - performance optimization needed

## Known Issues / TODO

1. **File Transfer Cancel**: Cancel button not yet implemented
2. **Multiple File Transfer**: Multiple file selection/transfer not yet implemented
3. **Closing mirror panel**: Needs to immediately clean up Mac side (currently only sends stop request to Android)
4. **Background sync toggle**: May appear off when permissions are revoked (this is correct behavior)

---

## Testing Checklist

- [x] Start mirroring from Android button - shows permission dialog
- [x] Grant permission - starts mirroring
- [x] Stop mirroring from Android button - stops (doesn't show dialog)
- [x] Send file from Mac to Android - doesn't crash
- [x] Mac media icon appears in Android sidebar when Mac has media info
- [x] Android can control Mac music (play/pause/next/previous) at any time
- [x] Mac media controls persist even when music is paused
- [x] Mirror request shows notification when app is minimized
- [x] Mirror button resets after cancel timeout
- [ ] Verify mirroring quality with hardware decoder
- [ ] Test closing mirror panel stops mirroring properly

---

## Build Instructions

### Android
```bash
cd airsync-android
./gradlew assembleDebug
```

### macOS
```bash
cd airsync-mac
xcodebuild -project AirSync.xcodeproj -scheme AirSync -configuration Debug
```

---

## Architecture Notes

### Threading Model
- **Android**: All WebSocket messages handled on IO dispatcher, UI updates on Main dispatcher
- **macOS**: All WebSocket messages handled on background thread, ALL AppState updates MUST be wrapped in `DispatchQueue.main.async`

### Media Control Flow
1. Mac sends status updates with music info
2. Android shows Mac media notification (always visible when title/artist present)
3. User taps control in Android notification
4. Android sends macMediaControl message to Mac
5. Mac executes control via MediaPlayer API
6. Mac sends updated status back to Android

### Screen Mirroring Flow
1. Mac sends mirrorRequest to Android
2. Android shows permission dialog (or notification if minimized)
3. User grants permission
4. Android starts ScreenCaptureService with MediaProjection
5. Android encodes frames as H.264 and sends to Mac
6. Mac decodes with VideoToolbox hardware decoder
7. Mac displays in mirror window
