# Rokid Scan Vision

A green monochrome computer-vision HUD experiment for Rokid glasses, using the proven phone + glasses architecture from InTheSky-Rokid-Radar.

## v0.2 goals

- Dedicated Android phone and glasses modules
- Rokid CXR messaging between phone and glasses
- Fullscreen green-on-black HUD for the glasses
- Animated scan line and central targeting reticle
- Detection boxes, object labels and confidence values
- Compact JSON detection protocol
- GitHub Actions debug APK builds
- Rokid glasses-camera capture through CXR-L, with phone-camera diagnostic fallback
- On-device ML Kit stream-mode object detection
- Normalized detection packets streamed back to the glasses HUD

## Architecture

`Rokid camera / vision source -> phone processing -> compact detection packet -> CXR -> glasses HUD`

The v0.1 milestone validated the HUD, connection and packet protocol. v0.2 captures the wearer's view from the camera built into the Rokid glasses at 1024x768, performs inference on the phone, and returns only lightweight detection data to the HUD. Rokid exposes callback-driven photo capture rather than a continuous video stream, so the app waits for the confirmed CXR session and requests the next frame only after processing completes.

## Modules

- `phone` - connection and detection controller
- `glasses` - lightweight HUD renderer

## Display design

The glasses renderer uses only black and green tones. Black pixels are left visually unobtrusive while the HUD renders high-contrast green graphics suitable for the Rokid monochrome display.

## Detection packet

```json
{
  "type": "scan_state",
  "frameWidth": 640,
  "frameHeight": 480,
  "detections": [
    {"label": "PERSON", "confidence": 0.96, "left": 0.20, "top": 0.18, "right": 0.55, "bottom": 0.86}
  ]
}
```

Coordinates are normalized from 0.0 to 1.0 so the phone's camera resolution does not have to match the glasses display resolution.

## Status

v0.2 implementation in progress. Hardware validation on the phone and Rokid glasses remains required.
