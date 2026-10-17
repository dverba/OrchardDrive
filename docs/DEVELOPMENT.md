# Developing Orchard Drive

How to build, run and test the app. For what the app does, see the [README](../README.md).

## Quick start

Requirements: Android Studio (2025.1+), Android SDK with platform 36, an AAOS emulator image.
Full emulator setup: [docs/AAOS_SETUP.md](AAOS_SETUP.md).

```bash
# Gradle needs JDK 17+. The system Java on this machine is 8, so use Studio's bundled JBR:
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

./gradlew assembleDebug testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Open the app in AAOS Media Center
adb shell am start -a android.car.intent.action.MEDIA_TEMPLATE \
  --es android.car.intent.extra.MEDIA_COMPONENT com.dverba.aaosmusic/com.aaosmusic.automotive.AutomotiveMusicService

# Debug/settings screen (also reachable from the gear icon in Media Center)
adb shell am start -n com.dverba.aaosmusic/com.aaosmusic.ui.MainActivity

# Logs
adb logcat -s OrchardDrive/PLAYBACK OrchardDrive/AAOS_LIBRARY OrchardDrive/MEDIA_SESSION OrchardDrive/APP
```

Or open the folder in Android Studio and run the `app` configuration against the AAOS AVD.

## Feature flags

Set as Gradle properties (`-PuseFakeProvider=false`, or in `~/.gradle/gradle.properties`); exposed as `BuildConfig` fields.

| Property | Default | Effect |
|---|---|---|
| `useFakeProvider` | `true` | Fake catalog. `false` selects Apple Music (needs the next flag, credentials and the SDK AARs, else falls back to fake). |
| `enableAppleAuth` | `false` | Apple sign-in features. |
| `enableApplePlayback` | `false` | Play through MusicKit for Android (`AppleMusicPlayer`). |
| `enableDebugMenu` | debug `true`, release `false` | Playback buttons on the settings screen. |

## Layout

```
app/src/main/java/com/aaosmusic/
  OrchardDriveApp.kt                 provider wiring (only place that knows which provider is active)
  automotive/
    AutomotiveMusicService.kt     MediaLibraryService: owns player + session
    AutomotiveLibraryCallback.kt  browse / search / play-request / custom-action handling
    LibraryTree.kt                browse hierarchy + queue resolution (pure Kotlin, unit tested)
    MediaIds.kt                   media id scheme
    MediaItemMapper.kt            internal models → Media3 MediaItem
  media/                          MusicProvider, MusicPlaybackProvider, models
  providers/fake/                 FakeMusicProvider, FakePlaybackProvider (ExoPlayer)
  providers/apple/                AppleMusicProvider, API client, JSON mapping, token store
app/src/applesdk/                 MusicKit-dependent code (AppleMusicPlayer); compiled only with the AARs
app/src/noapplesdk/               stub used when the AARs are absent
buildSrc/MusicKitSongIdFix.kt     build-time patch so MusicKit plays catalog ids above Int.MAX_VALUE (2026+ releases)
  automotive/ArtworkProvider.kt   content:// proxy for remote artwork (Media Center needs it)
  repository/MusicRepository.kt   provider facade; future cache layer
  ui/MainActivity.kt              parked-only settings screen (account, display, about)
  ui/DiagnosticsActivity.kt       status, debug report, test tools (Settings → tap Version 7 times)
  util/AppLog.kt                  structured logging + token redaction
tools/gen_artwork.py              regenerates the demo library's artwork (needs rsvg-convert)
tools/gen_icon_vector.py          regenerates the adaptive launcher icon from the store icon
```

Design notes: [docs/ARCHITECTURE.md](ARCHITECTURE.md).

## Status

### Verified on emulator (AAOS 14 / API 34, Google Play image, arm64)

- Installs and runs; appears as a media source in Media Center with app icon, search and settings gear.
- Browse: Home tab (Recently Played, Recommended) and Library tab (Playlists, Artists, Albums, Songs);
  album/artist/playlist drill-down with artwork.
- Library → Albums → Test Album → Test Song plays; Now Playing shows title, artist, artwork,
  progress/duration, prev / play-pause / next.
- Tapping a song queues its whole album/playlist/list from that track.
- Next/previous, seek (progress bar), shuffle and repeat (as AAOS custom actions).
- Hardware media keys (`KEYCODE_MEDIA_PLAY/PAUSE/NEXT/PREVIOUS`, the steering-wheel path).
- Search with categorized results (Songs / Albums / Artists / Playlists headers).
- Playback continues after the Activity's task is removed.
- Playback resumption after process restart (resumes Recently Played).
- 17 JVM unit tests (media ids, hierarchy, queue construction, search).

### Not yet verified / outstanding

- Audio focus loss when another media app starts; transient focus (nav prompts) ducking.
- Long pause → service teardown → reconnect behaviour.
- Plain AOSP AAOS image (only the Google Play image tested so far).
- Playback resumption returns a fixed queue; real last-queue/position persistence is TODO.
- Album title is set in metadata but AAOS Now Playing on this image only shows title + artist.
- Test audio streams from soundhelix.com, so the emulator needs network.

### Apple Music provider (verified on emulator, 2026-10-07)

- Media Center: Home → Recently Played (your history), Recommended (Apple's personal
  recommendations); Library → Playlists (174), Artists (catalog artwork), Albums, Songs.
- Search from Media Center hits the Apple catalog. Keystrokes are debounced, so typing a word costs one request.
- Tapping a song plays it and queues the rest of its album/playlist/search results through
  `AppleMusicPlayer` (Media3 `SimpleBasePlayer` over MusicKit). Next/previous/seek/pause/media keys
  all work; plays show up in the account's Apple Music history.
- Signed out: native AAOS "Sign in" prompt at the root; it refreshes automatically after sign-in.

QR sign-in (2026-10-07): settings screen shows a QR + code → phone page at
https://dverba.github.io/aaosmusic-signin/ → MusicKit JS sign-in → token relayed via Supabase
(`relay/`) → car stores it and Media Center refreshes on its own. Verified with a real iPhone/Apple ID.

Also (2026-10-07):
- Full library (tested with 13,883 songs, 2,095 albums, 1,245 artists) is loaded in the background with
  parallel paging; lists over 300 items get A–Z folders. Concurrent identical requests are de-duplicated.
- Songs that can't play through MusicKit for Android (iCloud uploads, delisted purchases; ~29% of the
  test library) are hidden.
- Queues are windowed to 100 songs around the tapped one (MusicKit rejects very long queues).
- Shuffle is done by the app (MusicKit's shuffled order isn't observable), so AAOS's queue view,
  next and previous match what plays. Toggling shuffle mid-song costs a ~3 s rebuffer.
- Last queue and position persist: after a restart AAOS shows the last song, and Play resumes in place.
- In-car diagnostics: in-memory log, crash capture, and **Send debug report** (no adb in the car).
- Developer token comes from the relay (signed server-side); the `car` build carries no Apple credential.
- Artwork is requested at the size shown (400 px tiles, 200 px rows, 600 px Now Playing), prefetched in
  the background after the library loads (~3,100 images, ~140 MB in tile mode, paused while buffering) and
  kept in a 300 MB LRU cache in filesDir. User-uploaded playlist covers (blobstore.apple.com) aren't shown.
- Settings: high-quality (256 kbps) streaming on cellular, on by default; tiles or list for browse screens.

Polestar: see [docs/POLESTAR_TESTING.md](POLESTAR_TESTING.md).

### Next milestone

Milestone 11: install `dist/OrchardDrive-car-*.apk` on the Polestar 4 and run POLESTAR_TESTING.md.

## Releasing

GitHub Actions builds every push (`.github/workflows/build.yml`: demo app + unit tests). To release, bump
`versionName` in `app/build.gradle.kts`, commit, then `git tag v<version> && git push origin v<version>`.
`.github/workflows/release.yml` then builds the Play bundle and the sideload APK (`./gradlew sideloadApk`:
the car build without the debug screen), attaches both to a GitHub Release, and uploads the bundle to
Play internal testing when `PLAY_SERVICE_ACCOUNT_JSON` is set. It needs the secrets listed at the top of
that file; Apple's AARs come from the private `dverba/orchard-drive-sdk` repo.
