# Polestar 4 in-car testing

The car has no adb, so the build is self-contained and logs leave the car through
**Send debug report** (uploaded to the relay; read with `select * from debug_reports order by created_at desc`).

## Build the car APK

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew carApk          # → dist/AAOSMusic-car-0.1.0.apk
```

The `car` build type is release-built and signed with `secrets/car-release.jks` (git-ignored; **back it
up**, since updates must be signed with the same key). It has Apple Music on, the debug screen on, and no Apple
credential inside: the developer token comes from the relay, so the relay's Apple secrets must be set
(see `relay/README.md`).

## Install

> **Finding (2026-10-07):** on Google's AAOS build, sideloading through a file manager dead-ends. The system
> installer asks you to allow the file manager as an unknown source, but its **Settings** button opens a
> stub ("Manage Unknown App Sources is not supported on this device") and returns to the previous
> screen. Reproduced on the emulator with `adb shell am start -n com.dverba.aaosmusic/com.aaosmusic.debug.InstallProbeActivity`
> (debug builds). The first in-car attempt with AnExplorer ended the same way. Unless the Polestar's own
> Settings has an "Install unknown apps" toggle for the file manager, installing needs adb or Google Play
> (internal testing).

1. Copy `dist/AAOSMusic-car-*.apk` to a USB stick (exFAT/FAT32).
2. In the car (parked): open the file browser → USB → tap the APK.
3. If asked, allow the file browser to install unknown apps, then **Install**.
4. Updating: install a newer APK the same way. Settings and sign-in survive updates; an uninstall clears them.

## Test sequence

Stop at the first failure, send a debug report, and note the report ID.

| # | Check | Pass looks like |
|---|---|---|
| 1 | APK installs | No "App not installed" / "not compatible" error |
| 2 | Media source appears | "Apple Music Test" (red note icon) in the car's media source list |
| 3 | Signed-out prompt | Opening it shows "Sign in to see your music" + **Sign in** |
| 4 | QR sign-in | **Sign in** → QR + code; phone scan → Allow → car shows "Apple Music connected" within ~5 s |
| 5 | Browsing | Home → Recently Played / Recommended; Library → Playlists / Artists / Albums / Songs (A–Z folders) |
| 6 | Playback | Tap a song → it plays **audibly** through the car speakers, with artwork and progress |
| 7 | Controls | Steering-wheel next/previous/play-pause, screen seek, shuffle, repeat |
| 8 | Audio focus | Navigation prompt ducks or pauses music; phone call pauses it; music resumes after |
| 9 | Background | Switch to the map or another app: music keeps playing |
| 10 | Car restart | Turn the car off and on: last song shows paused; Play resumes from the same spot, with no new sign-in |
| 11 | Voice | "Hey Google, play Daft Punk on Apple Music Test" |
| 12 | Search | Search icon → type a query → results → play |

After the run (or at any failure): car settings for the app (gear icon in Media) → tap **Version** 7 times
(once; it stays unlocked) → **Diagnostics** → **Send debug report** → note the ID shown.

## What to record

- Car software version and Android version (Settings → About / System), whether developer options exist
- The first debug report's `Displays` line (real resolution/density, used to tune the Polestar AVD)
- Each step: pass/fail, and any on-screen error text
- Sound quality on the car speakers (the emulator's audio is not representative)
- Debug report IDs

## Known risks specific to the car

- **Native library page size:** MusicKit's `libappleMusicSDK.so` is a 2020 build aligned for 4 KB pages. The
  debug report logs the device page size; on a 16 KB-page system the library may fail to load (Test 1
  would fail with an `UnsatisfiedLinkError` in the report).
- **OEM media-source allowlists:** some cars only list approved media apps. Step 2 checks this.
- **Unknown-sources install:** if the file browser refuses APKs, the fallback is a Play Console
  internal-testing track (needs a Google Play developer account).
- **Library size:** ~29% of this account's songs (iCloud uploads, delisted purchases) can't play through
  MusicKit for Android and are hidden.
