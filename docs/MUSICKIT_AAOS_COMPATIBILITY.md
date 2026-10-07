# MusicKit on AAOS — compatibility log

Not started. Record each experiment (date, image, result, logs) under the tests below.

## Known risks going in (from plan review, 2026-10-07)

1. **Auth depends on the Apple Music Android app.** MusicKit for Android's auth library hands
   off to the Apple Music app and, if it is missing, sends the user to install it. Apple Music is
   not known to be available on AAOS / the Polestar 4 Play Store. Expect Test 2 to fail as
   designed; candidate fallbacks:
   - Sign in with MusicKit JS on a phone/laptop web page, then hand the Music User Token to the
     car (QR code / short code + small backend). Most likely to work on a real car.
   - MusicKit JS inside an on-device WebView/Custom Tab (needs a capable WebView on the car).
2. **DRM is not Widevine.** The MusicKit Android playback library uses Apple's own DRM in native
   code; Widevine level is only relevant if playback goes through MusicKit JS/EME in a WebView.
   Test 5 should check the MusicKit native player first.
3. **The Android SDK is old and lightly maintained** (developer-forum reports of breakage after
   Apple Music app updates). Check native libs for arm64 support and 16 KB page alignment
   (matters for Android 15+ images, not for API 34).
4. **Developer token** requires a paid Apple Developer Program membership and a MusicKit key (.p8).
   Keep the key out of the repo (`*.p8` and `apple-music.properties` are git-ignored).
5. **Distribution/ToS:** personal use is one thing; publishing an Apple Music client on Play
   needs a check of Apple's MusicKit terms.

## Prior art (2026-10-07)

- **Automus** (`com.lindehammarkonsult.automus`, Play Store, third-party): signs in with a
  **QR code** — the car shows a code, the user completes Apple sign-in on their phone "via Apple's
  official servers", no password typed in the car. Session, queue and resume persist. Library,
  playlists, recommendations, full queue work; iTunes Match / uploaded tracks have limited support.
  This matches fallback (1) above: MusicKit JS authorization on the phone → Music User Token
  relayed to the car. How it plays DRM audio is unknown (MusicKit Android playback lib fed the
  relayed token vs. MusicKit JS/Widevine in a WebView) — the key question to answer next.
  ([Xataka Móvil](https://www.xatakamovil.com/aplicaciones/apple-music-no-tiene-app-oficial-para-android-automotive-esta-alternativa-ha-hecho-que-no-eche-coche))
  - From its [privacy policy](https://automus.se/automus-privacy-policy.html) and [site/FAQ](https://automus.se):
    - Own backend (automus.se) holds a short-lived auth session ("deleted after 5 minutes or upon
      successful authentication"); car polls it, receives the **Music User Token** + storefront id,
      stores them in SharedPreferences. Developer token presumably served/signed by the backend.
    - FAQ: sign-in page "gets stuck after I press Allow" → "try Chrome instead of Safari". The
      Allow step and popup sensitivity match **MusicKit JS `authorize()`** on the phone page.
    - "Some child Apple IDs do not work with this kind of sign-in."
    - **Not compatible with older Intel-based AAOS cars** → ships ARM-only native code. Strong hint
      playback uses **Apple's MusicKit Android playback library** (ARM-only native libs) with the
      relayed token, not a WebView. Also "standard stereo" only — no lossless/Atmos — consistent
      with that library.
    - Uploaded / iTunes Match tracks unsupported (catalog streaming only).
    - Google Assistant voice play supported (needs "on Automus" suffix sometimes).
  - Paid ($5.49), 1K+ installs, 3.7★ (Play listing viewed on emulator 2026-10-07).
- **Official Apple Music for AAOS** launched July 2026, **Volvo-only** (EX60 preinstalled, EX90/ES90
  later). Unclear whether other AAOS cars (e.g. Polestar) will get it.
  ([mobiflip](https://www.mobiflip.de/apple-music-kommt-ins-auto-app-fuer-android-automotive-startet/))

## SDK facts (from the AARs, 2026-10-07)

- `mediaplayback-release-1.1.1.aar` (Feb 2020) and `musickitauth-release-1.1.2.aar` (Feb 2021).
- Native code `libappleMusicSDK.so` + `libc++_shared.so` for **arm64-v8a and armeabi-v7a only**
  (no x86/x86_64) — confirms why Automus excludes Intel AAOS cars. DRM is Apple's own
  ("FootHill" JNI classes), not Widevine.
- The host app must `System.loadLibrary("c++_shared")` and `System.loadLibrary("appleMusicSDK")`
  before `MediaPlayerControllerFactory.createLocalController`, or it throws `UnsatisfiedLinkError`.
- Playback auth is a plain `TokenProvider { getDeveloperToken(); getUserToken() }` — any valid Music
  User Token works, regardless of how it was obtained.
- Integration quirks (handled in the build): local AARs carry no POM, so the auth SDK's AppCompat
  themes need `androidx.appcompat` added by hand; its `SDKUriHandlerActivity` lacks
  `android:exported` (pre-Android 12), so the app manifest declares it; it sets
  `allowBackup=true`, overridden with `tools:replace`.

## Integration findings (2026-10-07, while building AppleMusicPlayer)

- **MusicKit caches the user token at controller creation**, even though `TokenProvider` is an
  interface. A controller created while signed out fails later with `TYPE_PLAYBACK_QUEUE`
  ("FileNotFoundException: /v1/me/storefront"). Don't create it until a token exists, and recreate it
  when the token changes.
- **`createLocalController` opens an AudioTrack** and throws `IllegalArgumentException: Invalid audio
  buffer size` if audioserver is unavailable (seen once during an audioserver restart: `-32`/EPIPE
  everywhere). Creating it in `Service.onCreate` crashed the service, so creation is now lazy and retried.
- **`play()` is ignored while a queue is loading.** Media3 calls `prepare()` then `play()`, so the
  player re-issues `play()` when buffering ends or the current item changes. A `STOPPED → PAUSED`
  transition during loading is not a user pause.
- AAOS calls `prepare()` on an empty player when Media Center connects; that must be a no-op.
- Plays through the SDK are reported to Apple (they appear in `/v1/me/recent/played`).
- The auth AAR ships `drawable-v24/ic_launcher_foreground.xml` (Android robot) that overrode the
  app's icon in Media Center; the app's icon resources are now named `ic_app_icon_*`.
- Library playlist entries come back with `a.`-prefixed ids that can't be fetched individually;
  play requests resolve through the container instead.

## Test 1 — MusicKit initializes under AAOS

✅ **Pass** (2026-10-07, emulator AAOS 14 API 34 Play image, arm64). After loading the native
libraries, `createLocalController` succeeds and returns a `MediaPlayerController`.

## Test 2 — User can authenticate

**Apple's Android auth SDK: ❌ fails as designed** (2026-10-07, same image). It shows its own portrait
"Install Apple Music" screen; the button opens Play, which says *"Your device isn't compatible with
this version"* for Apple Music on AAOS. No token can be obtained this way.

**Alternative: MusicKit JS on another device.** For development, `tools/musickit_token.py` runs
MusicKit JS `authorize()` in the desktop browser and pushes the token to the emulator over adb.
The production equivalent is the QR relay (same approach as Automus). **✅ QR relay verified
2026-10-07** with a real phone: car shows QR → phone page (GitHub Pages) runs MusicKit JS
`authorize()` → token relayed via Supabase → car stores it → library loads. Gotcha: the page first had
`<meta name="referrer" content="no-referrer">`; after **Allow** the user was dropped back on the page with
no token (no `/complete` call). Removing it fixed the hand-back. The page also accepts the token via
`authorizationStatusDidChange` or an already-authorized instance. ✅ **Pass** (2026-10-07):
MusicKit JS v3 `authorize()` in desktop Chrome returned a Music User Token, which works for the API
and for SDK playback. Gotchas: Chrome showed Apple's `idmsa.apple.com` *"400 Request Header Or
Cookie Too Large"* (too many apple.com cookies). An Incognito window fixed it. Allow popups for the
page.
## Test 3 — Music User Token obtained / stored / reused

✅ **Pass** (2026-10-07). Token stored in SharedPreferences. After `am force-stop` and a relaunch,
`GET /v1/me/storefront` → 200 and MusicKit playback still works without signing in again.
## Test 4 — Catalog API search ("Daft Punk")

✅ **Pass** (2026-10-07) with the developer token alone: `GET /v1/catalog/us/search` → 200,
10 songs, 10 albums, 1 artist, 1 playlist. 30-second previews (plain AAC) play in ExoPlayer.
## Test 5 — DRM-protected playback on the AAOS emulator

✅ **Pass, audibly confirmed after a correction** (2026-10-07, AAOS 14 API 34 Play image, arm64
emulator on Apple silicon).

> **Correction:** this was first marked passing on track state alone (AudioTrack `started`, position
> advancing). The user then reported that no Apple audio was audible. Investigation:
> a Visualizer meter on the output mix showed real signal (MusicKit peak −2.5 dBFS, RMS −21 dBFS vs
> −96 dBFS silence), and MusicKit, ExoPlayer and the Radio app all mixed into the same
> `bus0_media_out` primary output. So the emulator's guest→host audio link was broken (probably
> since an audioserver restart at 05:49 that logged `-32` errors in every process). After a cold boot
> (`-no-snapshot-load`), both the ExoPlayer preview and MusicKit playback were **audible**. Sound
> quality on the emulator is poor, but AudioFlinger shows only 2 partial underruns, so the problem is
> the emulator's macOS audio passthrough, not MusicKit. Judge quality on real hardware.
>
> Lesson: "track started" is not "audible". Use the meter (Apple Music Test → *Measure output*;
> `adb shell pm grant com.dverba.aaosmusic android.permission.RECORD_AUDIO` first) plus a human listen.

`CatalogPlaybackQueueItemProvider` with song `697195462` ("One More Time"):
- `STOPPED → PLAYING`, duration **320357 ms** (full track, not the 30 s preview).
- Buffering finished in ~6.5 s. An app `AudioTrack` stayed `state:started` at 44.1 kHz stereo for 70+ s,
  with no item-ended or error callbacks.
- DRM handled by the SDK's native FootHill module (`FootHillConfig::config()` logged). No Widevine involved.
- Apple's player requests its own audio focus (`USAGE_MEDIA`/`CONTENT_TYPE_MUSIC`). The future
  `SimpleBasePlayer` wrapper has to coordinate with that instead of letting Media3 also request focus.
- Harmless startup noise: `SVAudioRendererNative: SVBufferManager::peekNextBufferToBeConsumed() ERROR
  illegal state: 0` repeats until the first buffer is ready.

## Conclusion (2026-10-07)

**Milestone 8 (critical path) is proven on the emulator, including audible output:** Apple Music → MusicKit for Android SDK →
our app → audio on AAOS, with sign-in done on a second device. Architecture:
- **Auth:** QR relay (car ↔ small backend ↔ phone page running MusicKit JS). Apple's Android auth SDK is
  not usable on AAOS.
- **Playback:** MusicKit for Android `MediaPlayerController` wrapped as a Media3 `SimpleBasePlayer`.
- **Catalog/library:** Apple Music REST API with developer token + Music User Token.

Open risks: the SDK is from 2020 and could break server-side at any time; it's ARM-only; the Polestar
may behave differently (verify on car); developer token must move to a backend before any
non-personal build.
