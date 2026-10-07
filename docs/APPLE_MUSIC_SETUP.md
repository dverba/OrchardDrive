# Apple Music setup (local development)

Nothing here is committed: `apple-music.properties`, `secrets/`, `*.p8` and `app/libs/apple/` are git-ignored.

## 1. Apple Developer portal

1. **Identifiers → + → Media IDs**: identifier `media.com.aaosmusic`, enable **MusicKit**.
2. **Keys → +**: enable **Media Services (MusicKit, ShazamKit, Apple Music Feed)**, configure it with
   the Media ID, register, **download the `.p8`** (one-time download), note the **Key ID**.
3. **Membership details**: note the **Team ID**.
4. Download **MusicKit for Android** (developer.apple.com/musickit) and copy
   `mediaplayback-release-*.aar` and `musickitauth-release-*.aar` into `app/libs/apple/`.

## 2. Local files

```
secrets/AuthKey_<KEYID>.p8
apple-music.properties
```
```properties
APPLE_TEAM_ID=XXXXXXXXXX
APPLE_KEY_ID=XXXXXXXXXX
APPLE_PRIVATE_KEY_PATH=secrets/AuthKey_XXXXXXXXXX.p8
```

Debug builds sign a 30-day developer token at build time and embed it (`BuildConfig.APPLE_DEVELOPER_TOKEN`).
**Debug only**: anything in an APK can be extracted. Release builds get an empty token and must
fetch one from a backend.

## 3. Build the Apple variant

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew installDebug -PuseFakeProvider=false -PenableApplePlayback=true -PenableAppleAuth=true
```

For Android Studio, put the same flags in `~/.gradle/gradle.properties`:
```properties
useFakeProvider=false
enableApplePlayback=true
enableAppleAuth=true
```

Without the AARs the `src/noapplesdk` stub is compiled and the app falls back to the fake provider.

## 4. Sign in

Open the app's settings (gear in Media Center, or the **Sign in** button AAOS shows when signed out).
It shows a QR code. Scan it with your phone, sign in to Apple Music, and tap **Allow**. The car connects
within a couple of seconds. See `relay/README.md` for how the relay works.

Fallback for development without a phone, the desktop helper:

```bash
python3 tools/musickit_token.py   # open http://localhost:8765 in Chrome (Incognito if Apple
                                  # shows "400 Request Header Or Cookie Too Large")
```

It runs MusicKit JS `authorize()` and pushes the Music User Token to the connected emulator/device over adb.
The token is stored in app SharedPreferences and survives restarts. When signed out, Media Center
shows "Sign in to see your music" with a **Sign in** button, and it refreshes automatically once a
token arrives.
