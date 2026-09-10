# Rokid Scan Vision

## Current architecture: standalone glasses

Rokid Scan Vision now runs its vision pipeline directly on the Rokid glasses. The phone companion is no longer used as a camera, detector, or data source.

Pipeline:

`Rokid camera -> CameraX -> on-device EfficientDet Lite -> HUD detections -> calibrated numbered targets`

The Android project therefore contains only the `glasses` application module.

### Vision

- Camera frames are captured locally on the glasses with CameraX.
- Object detection runs locally with MediaPipe Tasks and `efficientdet_lite0.tflite`.
- Detection inference is rate-limited for wearable performance.
- The camera image is cropped to a central scan region intended to correspond to the optical HUD field of view.
- Detection coordinates are mapped into the HUD for numbered target markers.
- Current calibration constants live in `OnDeviceVision.java`.

### Reference build

The known-good reference supplied during development is `glasses-debug.apk`. The source of truth for future development is the `glasses` module in this repository. Do not reintroduce the former phone-to-glasses camera/detection pipeline unless deliberately creating a separate companion mode.

### Build

Build the glasses debug APK with:

```bash
./gradlew :glasses:assembleDebug
```

On Windows:

```powershell
.\gradlew.bat :glasses:assembleDebug
```

The resulting APK is normally under `glasses/build/outputs/apk/debug/`.

## Project direction

This project is a wearable green-HUD object detection interface for Rokid glasses. The current design uses circular numbered targets rather than visible bounding boxes, with the target number corresponding to the detected-object list. Multiple detections can be displayed simultaneously and are refreshed as the wearer's view changes.
