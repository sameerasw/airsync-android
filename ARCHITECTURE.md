# Remote Control Architecture

## System Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                          Mac Computer                            │
│                                                                   │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │                    AirSync Mac App                          │ │
│  │                                                              │ │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐ │ │
│  │  │ Video Player │  │ Input Handler│  │ Quality Settings │ │ │
│  │  │              │  │              │  │                  │ │ │
│  │  │ - Display    │  │ - Mouse      │  │ - FPS Slider    │ │ │
│  │  │ - Decode H264│  │ - Trackpad   │  │ - Quality Slider│ │ │
│  │  │ - Render     │  │ - Keyboard   │  │ - Resolution    │ │ │
│  │  └──────┬───────┘  └──────┬───────┘  └────────┬─────────┘ │ │
│  │         │                  │                    │           │ │
│  │         └──────────────────┼────────────────────┘           │ │
│  │                            │                                │ │
│  │                    ┌───────▼────────┐                       │ │
│  │                    │ WebSocket      │                       │ │
│  │                    │ Client         │                       │ │
│  │                    └───────┬────────┘                       │ │
│  └────────────────────────────┼──────────────────────────────┘ │
└─────────────────────────────────┼────────────────────────────────┘
                                  │
                                  │ WiFi Network
                                  │
┌─────────────────────────────────▼────────────────────────────────┐
│                        Android Device                             │
│                                                                   │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │                    AirSync Android App                      │ │
│  │                                                              │ │
│  │  ┌──────────────────────────────────────────────────────┐  │ │
│  │  │            WebSocketMessageHandler                    │  │ │
│  │  │                                                        │  │ │
│  │  │  - Receives input events                             │  │ │
│  │  │  - Receives quality settings                         │  │ │
│  │  │  - Sends responses                                   │  │ │
│  │  └──────┬───────────────────────────────────────┬───────┘  │ │
│  │         │                                        │          │ │
│  │         │ Input Events                           │ Video    │ │
│  │         │                                        │ Frames   │ │
│  │  ┌──────▼──────────────────┐          ┌────────▼────────┐ │ │
│  │  │ InputAccessibilityService│          │ScreenMirroring │ │ │
│  │  │                          │          │    Manager      │ │ │
│  │  │ - injectTap()           │          │                 │ │ │
│  │  │ - injectLongPress()     │          │ - MediaCodec    │ │ │
│  │  │ - injectSwipe()         │          │ - H264 Encoder  │ │ │
│  │  │ - injectScroll()        │          │ - Quality Ctrl  │ │ │
│  │  │ - performBack()         │          │                 │ │ │
│  │  │ - performHome()         │          └─────────────────┘ │ │
│  │  │ - performRecents()      │                              │ │
│  │  └──────┬──────────────────┘                              │ │
│  │         │                                                  │ │
│  │         │ Accessibility API                               │ │
│  │         │                                                  │ │
│  │  ┌──────▼──────────────────────────────────────────────┐ │ │
│  │  │            Android System Services                   │ │ │
│  │  │                                                       │ │ │
│  │  │  - Touch Input System                               │ │ │
│  │  │  - Navigation System                                │ │ │
│  │  │  - Display System                                   │ │ │
│  │  └───────────────────────────────────────────────────────┘ │ │
│  └────────────────────────────────────────────────────────────┘ │
└───────────────────────────────────────────────────────────────────┘
```

## Data Flow

### 1. Input Event Flow (Mac → Android)

```
User Action (Mac)
    │
    ├─ Mouse Click
    ├─ Trackpad Scroll
    ├─ Keyboard Shortcut
    │
    ▼
Coordinate Mapping
    │
    ├─ Video coordinates → Android coordinates
    ├─ Scale X = androidWidth / videoWidth
    ├─ Scale Y = androidHeight / videoHeight
    │
    ▼
WebSocket Message
    │
    ├─ JSON: { type: "inputEvent", data: {...} }
    │
    ▼
Android WebSocketMessageHandler
    │
    ├─ Parse JSON
    ├─ Validate input type
    ├─ Check service availability
    │
    ▼
InputAccessibilityService
    │
    ├─ Create gesture path
    ├─ Build GestureDescription
    ├─ Dispatch gesture
    │
    ▼
Android System
    │
    ├─ Execute touch event
    ├─ Execute navigation action
    │
    ▼
Response Message
    │
    ├─ JSON: { type: "inputEventResponse", data: {...} }
    │
    ▼
Mac receives confirmation
```

### 2. Video Stream Flow (Android → Mac)

```
Android Screen
    │
    ▼
MediaProjection API
    │
    ├─ Capture screen buffer
    │
    ▼
VirtualDisplay
    │
    ├─ Render to surface
    │
    ▼
MediaCodec (H.264 Encoder)
    │
    ├─ Apply quality settings (fps, bitrate, resolution)
    ├─ Encode to H.264
    ├─ Generate SPS/PPS config
    │
    ▼
ScreenMirroringManager
    │
    ├─ Add Annex B start codes
    ├─ Prepend SPS/PPS to keyframes
    ├─ Base64 encode
    │
    ▼
WebSocket Message
    │
    ├─ JSON: { type: "mirrorFrame", data: {...} }
    │
    ▼
Mac WebSocket Client
    │
    ├─ Base64 decode
    ├─ Parse H.264 NAL units
    │
    ▼
Video Decoder
    │
    ├─ Decode H.264
    ├─ Render to screen
    │
    ▼
Display on Mac
```

### 3. Quality Settings Flow (Mac → Android)

```
User adjusts quality (Mac)
    │
    ├─ FPS slider
    ├─ Quality slider
    ├─ Resolution selector
    │
    ▼
Quality Settings Object
    │
    ├─ fps: 30-60
    ├─ quality: 0.6-0.9
    ├─ maxWidth: 720-1920
    ├─ bitrateKbps: 6000-20000
    │
    ▼
Mirror Request Message
    │
    ├─ JSON: { type: "mirrorRequest", data: { options: {...} } }
    │
    ▼
Android ScreenCaptureService
    │
    ├─ Parse MirroringOptions
    ├─ Create MediaFormat with settings
    │
    ▼
ScreenMirroringManager
    │
    ├─ Configure MediaCodec
    ├─ Set bitrate mode (CBR)
    ├─ Set profile (Baseline)
    ├─ Set level (3.1)
    │
    ▼
Video stream with new quality
```

## Component Responsibilities

### Mac Side (To Be Implemented)

| Component | Responsibility |
|-----------|---------------|
| Video Player | Decode and display H.264 stream |
| Input Handler | Capture mouse/trackpad/keyboard events |
| Coordinate Mapper | Convert Mac coordinates to Android coordinates |
| Gesture Recognizer | Detect swipes, long press, etc. |
| Quality Controller | Manage quality settings UI |
| WebSocket Client | Send/receive messages |

### Android Side (✅ Implemented)

| Component | Responsibility |
|-----------|---------------|
| WebSocketMessageHandler | Parse and route incoming messages |
| InputAccessibilityService | Inject touch and navigation events |
| ScreenMirroringManager | Encode screen to H.264 |
| ScreenCaptureService | Manage mirroring lifecycle |
| JsonUtil | Create JSON messages |

## Message Protocol

### Input Event Messages

```json
// Tap
{
  "type": "inputEvent",
  "data": {
    "inputType": "tap",
    "x": 500.0,
    "y": 800.0
  }
}

// Swipe
{
  "type": "inputEvent",
  "data": {
    "inputType": "swipe",
    "startX": 500.0,
    "startY": 1000.0,
    "endX": 500.0,
    "endY": 300.0,
    "duration": 300
  }
}

// Navigation
{
  "type": "inputEvent",
  "data": {
    "inputType": "back"
  }
}
```

### Response Messages

```json
{
  "type": "inputEventResponse",
  "data": {
    "inputType": "tap",
    "success": true,
    "message": "Tap injected at (500.0, 800.0)"
  }
}
```

### Video Frame Messages

```json
{
  "type": "mirrorFrame",
  "data": {
    "frame": "AAAAHGZ0eXBpc29tAAACAGlzb21pc28yYXZjMW1wNDE...",
    "pts": 1234567890,
    "isConfig": false
  }
}
```

### Quality Settings Messages

```json
{
  "type": "mirrorRequest",
  "data": {
    "options": {
      "fps": 60,
      "quality": 0.9,
      "maxWidth": 1920,
      "bitrateKbps": 20000
    }
  }
}
```

## State Management

### Android Service States

```
┌─────────────┐
│   Stopped   │
└──────┬──────┘
       │ Start mirroring
       ▼
┌─────────────┐
│  Starting   │
└──────┬──────┘
       │ MediaCodec configured
       ▼
┌─────────────┐
│  Streaming  │◄──┐
└──────┬──────┘   │
       │          │ Quality change
       │          │
       │ Stop     │
       ▼          │
┌─────────────┐   │
│  Stopping   │───┘
└──────┬──────┘
       │
       ▼
┌─────────────┐
│   Stopped   │
└─────────────┘
```

### Accessibility Service States

```
┌─────────────┐
│  Disabled   │
└──────┬──────┘
       │ User enables in Settings
       ▼
┌─────────────┐
│  Connected  │
└──────┬──────┘
       │ Ready to inject events
       ▼
┌─────────────┐
│   Active    │
└──────┬──────┘
       │ User disables or app uninstalled
       ▼
┌─────────────┐
│ Disconnected│
└─────────────┘
```

## Performance Considerations

### Latency Budget

```
Total Latency = Capture + Encode + Network + Decode + Render + Input

Capture:  ~16ms (60fps) or ~33ms (30fps)
Encode:   ~10-20ms (hardware) or ~50-100ms (software)
Network:  ~5-50ms (WiFi, depends on conditions)
Decode:   ~5-10ms (hardware)
Render:   ~16ms (60Hz display)
Input:    ~1-10ms (gesture duration)

Target:   <100ms for good experience
Optimal:  <50ms for excellent experience
```

### Bandwidth Usage

```
Resolution | FPS | Bitrate  | Bandwidth
-----------|-----|----------|----------
720p       | 30  | 6 Mbps   | 750 KB/s
1080p      | 30  | 12 Mbps  | 1.5 MB/s
1080p      | 60  | 15 Mbps  | 1.9 MB/s
1440p      | 30  | 15 Mbps  | 1.9 MB/s
1920p      | 60  | 20 Mbps  | 2.5 MB/s
```

## Security Model

### Permissions Required

```
Android:
- BIND_ACCESSIBILITY_SERVICE (user must grant)
- FOREGROUND_SERVICE_MEDIA_PROJECTION (declared)
- INTERNET (declared)

Mac:
- Network access (system)
- Screen recording (if capturing Mac screen)
```

### Trust Model

```
┌──────────┐                    ┌──────────┐
│   Mac    │◄──── WiFi ────────►│ Android  │
└──────────┘                    └──────────┘
     │                               │
     │ 1. User pairs devices        │
     │ 2. Symmetric key exchange    │
     │ 3. WebSocket connection      │
     │ 4. Encrypted communication   │
     │                               │
     └───────────────────────────────┘
```

## Error Handling

### Error Flow

```
Error Occurs
    │
    ├─ Service not available
    ├─ Invalid coordinates
    ├─ Gesture cancelled
    ├─ Network error
    │
    ▼
Log Error
    │
    ├─ Log.e(TAG, message)
    │
    ▼
Create Error Response
    │
    ├─ success: false
    ├─ message: error description
    │
    ▼
Send to Mac
    │
    ▼
Mac displays error to user
```

## Testing Strategy

### Unit Tests (Recommended)

```
InputAccessibilityService:
- Test gesture creation
- Test coordinate validation
- Test navigation actions

WebSocketMessageHandler:
- Test message parsing
- Test input type routing
- Test error handling

ScreenMirroringManager:
- Test quality settings application
- Test encoder configuration
- Test frame generation
```

### Integration Tests (Recommended)

```
End-to-End:
- Mac sends tap → Android executes → Response received
- Quality change → Encoder reconfigures → Stream quality improves
- Navigation action → System responds → Confirmation sent
```

### Manual Tests (Required)

```
User Scenarios:
- Tap on app icon → App opens
- Swipe to scroll → Content scrolls
- Back button → Navigate back
- Quality change → Video improves
```

## Deployment Checklist

- [ ] Android app compiled without errors
- [ ] Accessibility service enabled by user
- [ ] Mac app can connect via WebSocket
- [ ] Video stream displays correctly
- [ ] Input events work correctly
- [ ] Quality settings apply correctly
- [ ] Error handling works
- [ ] Performance is acceptable
- [ ] Documentation is complete
- [ ] User guide is available
