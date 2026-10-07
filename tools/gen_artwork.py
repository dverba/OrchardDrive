"""Generates placeholder artwork PNGs for the fake provider. No dependencies beyond stdlib."""
import os, struct, sys, zlib

SIZE = 512
OUT = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/res/drawable-nodpi"

# name -> (top-left rgb, bottom-right rgb, shape)
ART = {
    "art_album_1": ((250, 45, 72), (90, 20, 120), "circle"),
    "art_album_2": ((20, 140, 220), (10, 40, 90), "circle"),
    "art_album_3": ((250, 170, 30), (180, 60, 20), "circle"),
    "art_artist_1": ((120, 60, 200), (30, 20, 60), "head"),
    "art_artist_2": ((30, 180, 140), (10, 60, 50), "head"),
    "art_artist_3": ((230, 90, 160), (80, 30, 60), "head"),
    "art_playlist_1": ((60, 60, 70), (250, 45, 72), "bars"),
    "art_playlist_2": ((40, 90, 60), (200, 230, 80), "bars"),
    "art_playlist_3": ((20, 30, 80), (90, 200, 250), "bars"),
}

def pixel(x, y, a, b, shape):
    t = (x + y) / (2 * (SIZE - 1))
    r, g, bl = (int(a[i] + (b[i] - a[i]) * t) for i in range(3))
    cx = cy = SIZE / 2
    on = False
    if shape == "circle":
        d = ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5
        on = 150 < d < 170 or d < 30
    elif shape == "head":
        on = ((x - cx) ** 2 + (y - 200) ** 2) < 80 ** 2 or (
            y > 300 and ((x - cx) ** 2 / 150 ** 2 + (y - 420) ** 2 / 130 ** 2) < 1)
    elif shape == "bars":
        on = any(abs(y - row) < 14 and 110 < x < 402 - k * 60 for k, row in enumerate((170, 240, 310, 380)))
    if on:
        r, g, bl = (min(255, c + 110) for c in (r, g, bl))
    return bytes((r, g, bl))

def png(path, a, b, shape):
    raw = b"".join(b"\x00" + b"".join(pixel(x, y, a, b, shape) for x in range(SIZE)) for y in range(SIZE))
    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n")
        f.write(chunk(b"IHDR", struct.pack(">IIBBBBB", SIZE, SIZE, 8, 2, 0, 0, 0)))
        f.write(chunk(b"IDAT", zlib.compress(raw, 9)))
        f.write(chunk(b"IEND", b""))

os.makedirs(OUT, exist_ok=True)
for name, (a, b, shape) in ART.items():
    png(os.path.join(OUT, name + ".png"), a, b, shape)
    print("wrote", name)
