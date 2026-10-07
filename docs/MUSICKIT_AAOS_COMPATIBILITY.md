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

## Test 1 — MusicKit initializes under AAOS
## Test 2 — User can authenticate
## Test 3 — Music User Token obtained / stored / reused
## Test 4 — Catalog API search ("Daft Punk")
## Test 5 — DRM-protected playback on the AAOS emulator
