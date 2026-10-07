# Google Play internal testing (getting the app onto the Polestar)

Sideloading is blocked on Google's AAOS build (see POLESTAR_TESTING.md), so the car installs from Play.
Internal testing: up to 100 testers you invite, available within minutes, **no Android Automotive quality
review** (that only applies to production) ([Distribute to cars](https://developer.android.com/training/cars/distribute)).

## What's ready in this repo

| Item | Location |
|---|---|
| App bundle | `./gradlew carBundle` → `dist/AAOSMusic-car-<version>-<code>.aab` (version code = git commit count, so each build is uploadable) |
| Upload key | `secrets/car-release.jks` + `secrets/car-signing.properties` (git-ignored; **back up**) |
| Package name | `com.dverba.aaosmusic` (permanent) |
| App icon 512×512 | `store/icon-512.png` |
| Feature graphic 1024×500 | `store/feature-graphic-1024x500.png` |
| Car screenshots (1920×1200) | `store/screenshots/*.png` (fake demo library) |
| Privacy policy | https://dverba.github.io/aaosmusic-signin/privacy.html |

Target API 35 meets Play's Automotive minimum (API 35 from 31 Aug 2026; phones need 36). The 64-bit
native libraries are 64 KB-aligned, which satisfies Play's 16 KB page-size rule.

## Steps

### 1. Developer account (one-time)
1. https://play.google.com/console/signup → **Yourself** (personal) → pay the one-time $25.
2. Complete identity verification (can take a day or more). Personal accounts need a 12-tester, 14-day
   *closed* test before going to **production**. That rule doesn't apply to **internal** testing.

### 2. Create the app
**Home → Create app**
- App name: `AAOSMusic for Apple Music`
- Default language: English (US) · App · Free
- Accept the declarations.

### 3. Add the Android Automotive OS form factor
**Test and release → Setup → Advanced settings → Form factors → + Add form factor → Android Automotive OS**

Play then shows a three-item checklist under *Android Automotive OS* (as of Oct 2026):
1. *Upload Android Automotive OS screenshots (optional)*: done in step 4.
2. *Release an Android Automotive OS app bundle or APK to a testing track ›*: done in step 6. Use this
   link, because car releases go to **dedicated Android Automotive OS tracks**, separate from the phone tracks.
3. *Agree to review policy* (locked until a car release exists): accept it then. It's needed for production;
   internal testing isn't quality-reviewed.

**Then switch to a dedicated track (required for an automotive-only app).** By default Play uses the
*phone* tracks for Automotive, and those reject bundles that require `android.hardware.type.automotive`:
Form factors → **Android Automotive OS → Manage** → next to *Releases* choose **"Use a dedicated release
track for Android Automotive OS"** → **Save**
([Play help](https://support.google.com/googleplay/android-developer/answer/13295490?hl=en)).

*Android XR: Active* shows by default. It's irrelevant here, because the manifest requires
`android.hardware.type.automotive`, so Play won't offer the app to other device types.

### 4. Store listing (Grow users → Store presence → Main store listing)
- **Short description** (≤80):
  `Your Apple Music library on Android Automotive. Unofficial, independent player.` (79 chars)
- **Full description:**
  ```
  AAOSMusic is an independent player that brings your Apple Music library to cars running Android
  Automotive OS, using the car's built-in media screen and steering-wheel controls.

  • Your library: playlists, artists, albums and songs, with A–Z browsing for large libraries
  • Recently played and personal recommendations
  • Catalog search
  • Sign in once by scanning a QR code with your phone. No typing in the car.
  • Resumes where you left off

  Requires an active Apple Music subscription. Uploaded or iCloud-only tracks aren't supported.

  AAOSMusic is not affiliated with, endorsed by, or sponsored by Apple Inc. Apple and Apple Music are
  trademarks of Apple Inc.
  ```
- App icon: `store/icon-512.png` · Feature graphic: `store/feature-graphic-1024x500.png`
- **Phone screenshots: required (at least 2)**, even for a car-only app. Use `store/screenshots/` (16:10), or
  `store/screenshots-16x9/` (same images padded to 16:9) if Play rejects the aspect ratio.
- **Android Automotive OS screenshots:** `store/screenshots/01-home.png` … `05-now-playing.png`.

### 5. App content (Policy → App content)
- **Privacy policy:** `https://dverba.github.io/aaosmusic-signin/privacy.html`
- **Ads:** No ads.
- **App access:** "All or some functionality is restricted". Instructions: *Requires an Apple Music
  subscription. Sign in by scanning the QR code shown in the app's settings with a phone.*
- **Content rating:** questionnaire → category *Music/Audio streaming*, no violence etc. Note that streamed
  music may contain explicit lyrics.
- **Target audience:** 18 and over.
- **Data safety:**
  - Data collected: **App info and performance → Crash logs, Diagnostics**: collected, *optional* (only
    when the user taps Send debug report), purpose *App functionality / Analytics*, not shared.
  - Authentication token: stored on device, and passes through the developer's relay only transiently during
    sign-in (deleted within 5 minutes). Declare under *Personal info → Other info* if the form asks
    about transient processing; otherwise not "collected".
  - Encrypted in transit: Yes. Users can request deletion: Yes (by email).
- **News app / COVID / Government / Financial features:** No.

### 6. Internal testing release
From the Form factors checklist link (step 3, item 2), or **Test and release → Testing → Internal testing**
with the **Android Automotive OS** form factor/track selected, not the phone one.
1. **Testers** tab → create an email list containing the **Google account signed in to the car's Play
   Store** → Save → copy the **opt-in link**.
2. **Create new release** → Play App Signing: accept *Use Google-generated key* (our `car-release.jks` becomes
   the upload key) → upload `dist/AAOSMusic-car-*.aab` → release name e.g. `0.2.0 (15)` → notes → **Save →
   Review → Start rollout to Internal testing**.

### 7. Install in the car
1. On a phone or computer, signed in with the **same Google account as the car**, open the opt-in link
   → **Accept** (become a tester).
2. In the car's Play Store, search `AAOSMusic` (tester-only apps can take a few minutes to show up), or use
   the "Download it on Google Play" link from the opt-in page in a desktop browser and choose the car as the
   install target if it's offered.
3. Then follow the test sequence in `POLESTAR_TESTING.md` (from step 2).

### Updates
Commit, then `./gradlew carBundle` (version code rises with the commit count) → Internal testing → Create new
release → upload. The car updates via Play like any other app.

## Gotchas
- **"APKs and bundles must not require following features: android.hardware.type.automotive… only in
  dedicated tracks"**: the release is on the *phone* track. Discard the draft, enable the dedicated
  Automotive track (step 3), then create the release on the **Android Automotive OS** internal-testing
  track. Reuse the uploaded bundle via **Add from library**. Dedicated tracks have their **own tester lists**.
- **"Item not found" in the car's Play Store**, in order of likelihood: (1) opt-in link copied from the
  *phone* track: use the link from the **Automotive** internal track's Testers tab, with the email list ticked
  and saved; (2) invite not yet accepted: open the link in a **browser** on a phone/computer signed in with
  the car's Google account and accept; (3) the car's Play Store uses a different Google account than the
  tester list; (4) release still processing: wait for *Available to internal testers* (a new app's first
  release can take hours). Then search "AAOSMusic" in the car, or restart the Play Store/infotainment.
- **Warnings about deobfuscation files / native debug symbols**: safe to ignore. R8 is off, and Apple's
  MusicKit `.so` files ship without symbols.
- **Upload key:** losing `car-release.jks` means asking Google support for an upload key reset (possible with
  Play App Signing, but slow). Back it up.
- **Version code:** derived from `git rev-list --count HEAD`. Commit before building, and never rewrite pushed
  history, or the code could go backwards.
- **Policy:** "for Apple Music" naming plus the disclaimer is the conventional, safe form. A production
  release would get a stricter review than internal testing.
