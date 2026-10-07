# AAOSMusic — Apple Music client for Android Automotive OS

Native AAOS media app (Media3 `MediaLibraryService`) intended to become an Apple Music client.
Target vehicle: Polestar 4 (Google Automotive Services). Development happens on the AAOS emulator.

**Status:** Milestones 1–9 work on the emulator. The fake provider covers the AAOS layer; the Apple
Music provider browses your real library, recommendations and catalog search and plays full tracks
through MusicKit for Android. Sign-in currently uses a desktop dev tool; the in-car QR flow is next.
See [Status](#status) and [docs/APPLE_MUSIC_SETUP.md](docs/APPLE_MUSIC_SETUP.md).

## Quick start

Requirements: Android Studio (2025.1+), Android SDK with platform 36, an AAOS emulator image.
Full emulator setup: [docs/AAOS_SETUP.md](docs/AAOS_SETUP.md).

```bash
# Gradle needs JDK 17+. The system Java on this machine is 8, so use Studio's bundled JBR:
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

./gradlew assembleDebug testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Open the app in AAOS Media Center
adb shell am start -a android.car.intent.action.MEDIA_TEMPLATE \
  --es android.car.intent.extra.MEDIA_COMPONENT com.aaosmusic/.automotive.AutomotiveMusicService

# Debug/settings screen (also reachable from the gear icon in Media Center)
adb shell am start -n com.aaosmusic/.ui.MainActivity

# Logs
adb logcat -s AAOSMusic/PLAYBACK AAOSMusic/AAOS_LIBRARY AAOSMusic/MEDIA_SESSION AAOSMusic/APP
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
  AAOSMusicApp.kt                 provider wiring (only place that knows which provider is active)
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
  automotive/ArtworkProvider.kt   content:// proxy for remote artwork (Media Center needs it)
  repository/MusicRepository.kt   provider facade; future cache layer
  ui/MainActivity.kt              parked-only settings/debug screen
  util/AppLog.kt                  structured logging + token redaction
tools/gen_artwork.py              regenerates placeholder artwork
```

Design notes: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

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
- Category folders (Recently Played, Playlists, …) have no icons.
- Test audio streams from soundhelix.com, so the emulator needs network.

### Apple Music provider (verified on emulator, 2026-10-07)

- Media Center: Home → Recently Played (your history), Recommended (Apple's personal
  recommendations); Library → Playlists (174), Artists (catalog artwork), Albums, Songs.
- Search from Media Center hits the Apple catalog. Keystrokes are debounced, so typing a word costs one request.
- Tapping a song plays it and queues the rest of its album/playlist/search results through
  `AppleMusicPlayer` (Media3 `SimpleBasePlayer` over MusicKit). Next/previous/seek/pause/media keys
  all work; plays show up in the account's Apple Music history.
- Signed out: native AAOS "Sign in" prompt at the root; it refreshes automatically after sign-in.

Outstanding: QR sign-in relay; per-list caps (500 albums/artists, 1000 songs) instead of paging;
duplicate concurrent fetches; queue view and shuffle order vs MusicKit's own shuffle; playback
resumption for Apple (still returns Recently Played); developer token must move to a backend.

### Next milestone

QR sign-in relay (backend + phone page), replacing `tools/musickit_token.py`.
