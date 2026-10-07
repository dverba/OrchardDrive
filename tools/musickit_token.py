#!/usr/bin/env python3
"""
Dev-only stand-in for the QR sign-in relay: get a Music User Token with MusicKit JS in your
desktop browser and push it to the connected emulator/device over adb.

    python3 tools/musickit_token.py        # then open http://localhost:8765 in Chrome

Reads apple-music.properties (git-ignored), signs a developer token with the local .p8 key via
openssl, and never prints either token.
"""
import base64, http.server, json, os, subprocess, sys, time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PORT = 8765
ACTIVITY = "com.dverba.aaosmusic/com.aaosmusic.ui.AppleMusicTestActivity"
EXTRA = "com.aaosmusic.extra.USER_TOKEN"
ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")


def props():
    out = {}
    with open(os.path.join(ROOT, "apple-music.properties")) as f:
        for line in f:
            if "=" in line and not line.lstrip().startswith("#"):
                k, v = line.split("=", 1)
                out[k.strip()] = v.strip()
    return out


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def der_to_raw(sig: bytes) -> bytes:
    """ECDSA DER (SEQUENCE{INTEGER r, INTEGER s}) -> 64-byte r||s as JWS ES256 requires."""
    assert sig[0] == 0x30
    i = 2 if sig[1] < 0x80 else 2 + (sig[1] & 0x7F)
    parts = []
    for _ in range(2):
        assert sig[i] == 0x02
        n = sig[i + 1]
        parts.append(sig[i + 2:i + 2 + n].lstrip(b"\x00").rjust(32, b"\x00"))
        i += 2 + n
    return parts[0] + parts[1]


def developer_token(hours=12) -> str:
    p = props()
    key_path = os.path.join(ROOT, p["APPLE_PRIVATE_KEY_PATH"])
    now = int(time.time())
    header = b64url(json.dumps({"alg": "ES256", "kid": p["APPLE_KEY_ID"]}).encode())
    payload = b64url(json.dumps({"iss": p["APPLE_TEAM_ID"], "iat": now, "exp": now + hours * 3600}).encode())
    signing_input = f"{header}.{payload}".encode()
    der = subprocess.run(["openssl", "dgst", "-sha256", "-sign", key_path], input=signing_input,
                         capture_output=True, check=True).stdout
    return f"{header}.{payload}.{b64url(der_to_raw(der))}"


PAGE = """<!doctype html><html><head><meta charset="utf-8"><title>MusicKit token</title>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>body{font:16px system-ui;max-width:560px;margin:40px auto;padding:0 16px}
button{font-size:18px;padding:10px 18px}#s{margin-top:16px;white-space:pre-wrap}</style></head>
<body><h2>Apple Music &rarr; AAOS emulator</h2>
<p>Sign in with the Apple ID that has an Apple Music subscription. The token is sent straight to
the emulator over adb and is not shown here.</p>
<button id="b" disabled>Loading MusicKit…</button><div id="s"></div>
<script>
const s = t => document.getElementById('s').textContent = t;
document.addEventListener('musickitloaded', async () => {
  try {
    await MusicKit.configure({developerToken: '__DEV_TOKEN__', app: {name: 'AAOSMusic', build: '0.1.0'}});
  } catch (e) { s('MusicKit configure failed: ' + e); return; }
  const b = document.getElementById('b');
  b.disabled = false; b.textContent = 'Sign in to Apple Music';
  b.onclick = async () => {
    try {
      const token = await MusicKit.getInstance().authorize();
      s('Got user token, sending to emulator…');
      const r = await fetch('/token', {method: 'POST', body: token});
      s(await r.text());
    } catch (e) { s('Sign-in failed: ' + e); }
  };
});
</script>
<script src="https://js-cdn.music.apple.com/musickit/v3/musickit.js" data-web-components async></script>
</body></html>"""


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        body = PAGE.replace("__DEV_TOKEN__", developer_token()).encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        token = self.rfile.read(int(self.headers["Content-Length"])).decode().strip()
        if self.path != "/token" or not token or "'" in token:
            self.send_response(400); self.end_headers(); return
        # --activity-clear-task: start fresh so the token lands in onCreate even if other screens
        # (e.g. Apple's auth SDK or Play) are stacked on top of the test screen.
        r = subprocess.run([ADB, "shell", f"am start --activity-clear-task -n {ACTIVITY} --es {EXTRA} '{token}'"],
                           capture_output=True, text=True)
        ok = r.returncode == 0 and "Error" not in r.stdout + r.stderr
        msg = (f"Sent to emulator ({len(token)}-char token). Check the Apple Music Test screen."
               if ok else "adb failed: " + (r.stderr or r.stdout)[:300])
        print(msg)
        self.send_response(200 if ok else 500)
        self.send_header("Content-Type", "text/plain")
        self.end_headers()
        self.wfile.write(msg.encode())

    def log_message(self, fmt, *args):  # keep tokens out of the console
        print(f"{self.command} {self.path.split('?')[0]}")


if __name__ == "__main__":
    developer_token()  # fail fast if the key/props are wrong
    print(f"Open http://localhost:{PORT} in Chrome")
    http.server.ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
