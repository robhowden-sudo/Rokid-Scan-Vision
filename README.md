# Rokid Scan Vision

**Rokid Scan Vision** is a green monochrome computer-vision HUD project for Rokid glasses. It uses a phone + glasses architecture, local/on-device vision processing, and a lightweight HUD designed to line up detected objects with the wearer's view.

## Current build

Latest working development line: **numbered target markers + alignment calibration**.

The current main branch now contains the latest successful field-tested branch used for the Rokid alignment work.

## What it does

- Rokid phone + glasses modules
- Rokid CXR messaging between phone and glasses
- Fullscreen green-on-black HUD
- Animated scan line and central targeting reticle
- On-device object detection
- Compact normalized detection packets
- Multiple simultaneous targets
- Numbered circular target markers instead of visible detection boxes
- Matching numbered target list on the HUD
- Horizontal and vertical marker offsets for view calibration
- Centre-biased visual layout designed around the usable Rokid display region
- Detection confidence and object classification display
- HUD connection / vision status indicators
- GitHub Actions Android debug builds

## Current HUD behaviour

Detection bounding boxes are still used internally by the vision system to locate objects, but they are **not drawn on the glasses display**.

Each detected object is shown as a circular target marker containing a number such as `01`, `02`, `03`, and so on. The number matches the corresponding entry in the **DETECTED TARGETS** list.

This allows several objects to remain visible at once without covering the wearer's view with large rectangles.

The current calibration applies explicit horizontal and vertical offsets so the target markers align more closely with the real-world view through the Rokid display.

## Architecture

`Rokid camera / vision source → phone/on-device processing → normalized detection data → CXR → glasses HUD`

The project evolved from the earlier In The Sky Rokid work. The main design goal is to keep the glasses renderer lightweight while moving the heavier detection work to the most appropriate available device.

## Modules

- `phone` — connection, camera/vision and detection controller
- `glasses` — lightweight HUD renderer and on-glasses visual tracking layer

## Display design

The HUD deliberately uses a restrained black-and-green presentation suitable for the Rokid display:

- central targeting reticle
- animated scan line
- numbered circular target markers
- target classification list
- bearing / compass instrumentation
- frame and object counters
- connection and vision status

The marker system is designed to preserve visibility of the real scene rather than surrounding every detected object with a large box.

## Detection packet

Detection coordinates are normalized from `0.0` to `1.0`, so the source camera resolution does not have to match the glasses display resolution.

Example:

```json
{
  "type": "scan_state",
  "frameWidth": 1024,
  "frameHeight": 768,
  "detections": [
    {
      "label": "PERSON",
      "confidence": 0.96,
      "left": 0.20,
      "top": 0.18,
      "right": 0.55,
      "bottom": 0.86
    }
  ]
}
```

The HUD converts the detection centre into a calibrated marker position and applies the current display offsets before drawing the numbered target.

## Building

The project can be opened in Android Studio. The repository also includes a GitHub Actions workflow for Android debug APK builds.

The two principal modules are built separately so phone and glasses APKs can be installed on their respective devices.

## Development status

Active experimental build.

The current priority has been **real-world marker alignment**, including correcting vertical position, horizontal position and the effective scanned/displayed field of view. The latest successful build moved the markers slightly right and down after hardware testing.

This is still experimental computer vision software, because apparently wearing a green machine-vision HUD was not futuristic enough without also spending an evening arguing with coordinate transforms.
