# GuideLens Android

Native Android version of the **GuideLens** assistive app (visually impaired & elderly users),
mirroring the GuideLens PWA: same screens, same voice-first flow, same distance-tiered alerts.
**Everything works fully offline except reverse-geocoding ("Where am I?") and walking-route
download, which need internet** — and both fail gracefully with a *spoken* message.

## What works offline (on-device)
| Feature | Implementation |
|---|---|
| Obstacle alerts | SSD MobileNetV2 FPNLite via TensorFlow Lite (bundled in the project) |
| Pole / wall / fence zones | Optional DeepLabV3 Cityscapes segmentation (bundled if download succeeds) |
| Distance tiers | <=7 m notice (max 2x), <=5 m "coming close", <=3 m "Stop!" + triple vibration |
| Spoken output | Android TextToSpeech (offline voices) |
| Voice commands | Android SpeechRecognizer with `EXTRA_PREFER_OFFLINE` (see note below) |
| Sign reading | ML Kit Text Recognition **bundled** model — no network |
| Vibration / SOS | Local vibrator + `smsto:` intent with Google-Maps location link |
| Settings, themes, simple mode | 100% local |

## What needs internet
- **Where am I?** — reverse geocoding. If the phone is offline the app **says out loud**:
  *"No internet access. I can't tell you where you are right now."*
- **Navigate somewhere** — walking routes come from OSRM/OpenStreetMap servers.
  Offline, the app says: *"Navigation needs an internet connection to download the
  walking route. Everything else works offline."*
  (True offline routing would require bundling city-sized map data — out of scope here.)

## Voice input note
Offline speech recognition depends on the phone's speech service (e.g. Google app with the
English offline pack downloaded). If offline recognition isn't available, the app says it
didn't catch that — the big buttons always work regardless.

## Build

### Option A — GitHub Actions (zero setup)
Push this repo to GitHub. The workflow in `.github/workflows/build.yml` validates the bundled
detector model and builds a debug APK, uploaded as an installable artifact.

**The detector model is already bundled** at `app/src/main/assets/detect.tflite`, so the build does not download it.

### Option B — Android Studio
1. **Open** this folder in Android Studio (Ladybug or newer).
2. Let Gradle sync. The detector model is already in `app/src/main/assets/`; no model download
   is required for the build. (Object-detector labels are embedded in `Detector.kt`.)
3. **Build > Build Bundle(s)/APK(s) > Build APK(s)**, then install on the phone.
4. Grant **Camera**, **Microphone** and **Location** permissions when asked.

### Option C — command line
```bash
gradle wrapper          # once
./gradlew assembleDebug # app/build/outputs/apk/debug/app-debug.apk
```

## Project layout
```
app/src/main/java/com/guidelens/app/
  MainActivity.kt     screens, wiring, navigation guidance, SOS, settings
  CameraController.kt CameraX preview + detection loop (fully offline)
  Detector.kt         COCO-SSD TFLite wrapper + monocular distance estimate
  Segmenter.kt        optional DeepLab cityscapes zones (poles/walls/fences)
  OverlayView.kt      bounding boxes + danger-zone canvas
  AlertCenter.kt      7m/5m/3m tiered spoken + vibration alerts
  Speaker.kt          TextToSpeech wrapper
  VoiceController.kt  hold-to-talk offline speech recognition
  Geo.kt              geocoding, OSRM routing, polyline decode, "Where am I"
  OcrReader.kt        ML Kit offline OCR
  Prefs.kt            settings storage
```

## Honest limitations (safety-critical — read this)
- **Distance is estimated from a single camera** (monocular). Treat it as approximate
  bands (7 m / 5 m / 3 m), never exact meters.
- The segmentation model is optional; if it is absent or class indices don't match
  your model variant, named-object detection still works. Adjust `OBSTACLE_IDS` in
  `Segmenter.kt` for a different model.
- The segmentation class indices assume Cityscapes trainIds (wall=3, fence=4, pole=5,
  traffic light=6, traffic sign=7).
- The "Prefer step-free routes" setting is stored but not applied (OSRM foot profile has
  no step-free flag).
- **GuideLens is a complement to your cane or guide dog, never a replacement.**
  This is a working prototype, not a certified safety device.
