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

## Test 1 — MusicKit initializes under AAOS
## Test 2 — User can authenticate
## Test 3 — Music User Token obtained / stored / reused
## Test 4 — Catalog API search ("Daft Punk")
## Test 5 — DRM-protected playback on the AAOS emulator
