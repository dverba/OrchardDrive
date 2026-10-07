# AAOS emulator setup

## SDK components

Install via Android Studio → SDK Manager, or command line:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
SDK=~/Library/Android/sdk
# cmdline-tools (if missing): SDK Manager → SDK Tools → Android SDK Command-line Tools
$SDK/cmdline-tools/latest/bin/sdkmanager \
  "platforms;android-36" \
  "system-images;android-34-ext9;android-automotive-playstore;arm64-v8a"
```

Use `x86_64` instead of `arm64-v8a` on Intel machines.

Available AAOS images (as of 2026-10): API 33, 34-ext9, 35-ext15, each in
`android-automotive` (Google APIs / closer to AOSP) and `android-automotive-playstore` (GAS-like) variants.
The Polestar 4 is GAS-based, so the Play image is the primary target; use the non-Play image to
compare behaviour (plan §10).

## Create the AVD

```bash
$SDK/cmdline-tools/latest/bin/avdmanager create avd -n AAOS_API34_Play \
  -k "system-images;android-34-ext9;android-automotive-playstore;arm64-v8a" \
  -d automotive_1408p_landscape_with_play
$SDK/emulator/emulator -avd AAOS_API34_Play
```

Or Device Manager → Create Virtual Device → Automotive category.

## Useful commands

```bash
# The AAOS emulator exposes two displays; screencap needs an explicit id
adb shell dumpsys SurfaceFlinger --display-id
adb exec-out screencap -p -d <id> > screen.png

# Open our source directly in Media Center
adb shell am start -a android.car.intent.action.MEDIA_TEMPLATE \
  --es android.car.intent.extra.MEDIA_COMPONENT com.aaosmusic/.automotive.AutomotiveMusicService

# Simulate steering-wheel media buttons
adb shell input keyevent KEYCODE_MEDIA_PLAY_PAUSE
adb shell input keyevent KEYCODE_MEDIA_NEXT

# Inspect the session as AAOS sees it
adb shell dumpsys media_session | grep -A20 com.aaosmusic
```

## Emulator image differences

| Behaviour | API 34 Play | API 34 Google APIs |
|---|---|---|
| Media Center discovers source | ✅ | not tested |
| Browse / search / playback | ✅ | not tested |

## Emulator audio troubleshooting

- **No sound at all, but tracks show `state:started`:** the emulator's link to host audio can die
  (seen after an audioserver restart). Cold boot: `adb emu kill`, then start with `-no-snapshot-load`.
- **Check whether there's actually signal:** debug builds include *Measure output* on the Apple Music
  Test screen (Visualizer on the output mix; −96 dBFS = silence). Grant first:
  `adb shell pm grant com.aaosmusic android.permission.RECORD_AUDIO`.
- **Crackly sound on macOS:** emulator passthrough limitation. If
  `adb shell dumpsys media.audio_flinger | grep underrun` shows few underruns, the guest side is fine.
