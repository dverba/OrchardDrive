# Sign-in relay

Lets the car sign in to Apple Music without the Apple Music app (unavailable on AAOS): the car shows a QR
code, the user signs in on their phone with MusicKit JS, and the Music User Token is relayed to the car.

| Piece | Lives at | Source |
|---|---|---|
| Session table | Supabase project `AAOSMusic` (`tlrznrkphtyhuzvsjfis`) | `supabase/migrations/` |
| `signin` Edge Function (`verify_jwt` off; secrets in the request are the auth) | `https://tlrznrkphtyhuzvsjfis.supabase.co/functions/v1/signin` | `supabase/functions/signin/index.ts` |
| Phone page | https://dverba.github.io/aaosmusic-signin/ (public repo `dverba/aaosmusic-signin`) | `web/index.html` |

Flow: car `POST /start` → shows QR for `<page>?c=<code>` → phone `GET /session/<code>` (developer token) →
MusicKit JS `authorize()` → phone `POST /complete` → car `POST /poll` gets the token once, and the row is deleted.
Codes are 10 characters (~50 bits) and expire after 5 minutes.

Supabase Edge Functions can't serve HTML (it's rewritten to text/plain), hence GitHub Pages for the page.

Deploy:
- Function: `supabase functions deploy signin --no-verify-jwt --project-ref tlrznrkphtyhuzvsjfis`
- Page: copy `web/index.html` into the `aaosmusic-signin` repo and push.

Other routes: `GET /developer-token` (ES256 developer token signed with the MusicKit key held in
Supabase secrets; apps fetch it instead of shipping a key), `POST /report` (in-car debug reports into
`debug_reports`). `/start` is rate-limited to 6 per minute per client and `/report` to 20 per hour
(salted IP hash).

Apple secrets (needed for `/developer-token` and for `/start` without a client-supplied token):
```bash
supabase secrets set --project-ref tlrznrkphtyhuzvsjfis \
  APPLE_TEAM_ID="$(grep APPLE_TEAM_ID apple-music.properties | cut -d= -f2)" \
  APPLE_KEY_ID="$(grep APPLE_KEY_ID apple-music.properties | cut -d= -f2)" \
  APPLE_PRIVATE_KEY="$(cat "$(grep APPLE_PRIVATE_KEY_PATH apple-music.properties | cut -d= -f2)")"
```
