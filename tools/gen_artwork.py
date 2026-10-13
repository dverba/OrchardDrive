"""Generates the demo library's artwork PNGs (fake provider) from the Orchard Drive fallback motifs.

Needs rsvg-convert (brew install librsvg).
"""
import os, subprocess, sys, tempfile

SIZE = 512
OUT = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/res/drawable-nodpi"
NIGHT, DUSK, ASPHALT, SUNSET = "#0A0B0F", "#161B2E", "#2D303C", "#FF5A45"

TREE = """<symbol id="tr" viewBox="0 0 100 126">
  <rect x="45.5" y="78" width="9" height="48" fill="#08090C"/>
  <g fill="#0E1712"><circle cx="50" cy="32" r="30"/><circle cx="22" cy="58" r="22"/><circle cx="78" cy="58" r="22"/><circle cx="50" cy="64" r="24"/></g>
  <g stroke="#08090C" stroke-width="6" stroke-linecap="round" fill="none"><path d="M50 100 L35 85"/><path d="M50 100 L65 85"/><path d="M50 104 V80"/></g>
  <g fill="#2F4A3A"><ellipse cx="52" cy="24" rx="7" ry="4" transform="rotate(-62 52 24)"/><ellipse cx="18" cy="50" rx="7" ry="4" transform="rotate(24 18 50)"/><ellipse cx="80" cy="50" rx="7" ry="4" transform="rotate(-24 80 50)"/></g>
  <g fill="#FF5A45"><circle cx="58" cy="40" r="9.5"/><circle cx="30" cy="62" r="9.5"/><circle cx="68" cy="66" r="9.5"/></g>
</symbol>"""


def album(sky, sun_y, sun_r):
    """Sun setting over the road."""
    return f"""<rect width="100" height="100" fill="{sky}"/><circle cx="50" cy="{sun_y}" r="{sun_r}" fill="{SUNSET}"/>
<rect y="62" width="100" height="38" fill="{NIGHT}"/><polygon points="49,62 51,62 78,100 22,100" fill="{ASPHALT}"/>
<g fill="{SUNSET}"><rect x="49.5" y="68" width="1" height="3"/><rect x="49" y="76" width="2" height="6"/><rect x="48.4" y="88" width="3.2" height="12"/></g>"""


def playlist(bg, ink):
    """List lines and a note."""
    return f"""<rect width="100" height="100" fill="{bg}"/><g fill="{ink}"><rect x="20" y="30" width="44" height="7" rx="3.5"/>
<rect x="20" y="46" width="44" height="7" rx="3.5"/><rect x="20" y="62" width="28" height="7" rx="3.5"/>
<path d="M70 30 h10 v8 h-5 v24 a7 7 0 1 1 -5 -6.7 z"/></g>"""


def artist(sky, tree_x):
    """An orange tree against the sun."""
    return f"""<rect width="100" height="100" fill="{sky}"/><circle cx="50" cy="66" r="30" fill="{SUNSET}"/>
<use href="#tr" x="{tree_x}" y="20" width="48" height="60"/><rect y="78" width="100" height="22" fill="{NIGHT}"/>"""


# Variant 1 of each is the design's motif; 2 and 3 shift it so the demo library isn't identical tiles.
ART = {
    "art_album_1": album(DUSK, 62, 26),
    "art_album_2": album(DUSK, 70, 34),
    "art_album_3": album(ASPHALT, 56, 18),
    "art_artist_1": artist(DUSK, 26),
    "art_artist_2": artist(ASPHALT, 14),
    "art_artist_3": artist(NIGHT, 38),
    "art_playlist_1": playlist(SUNSET, NIGHT),
    "art_playlist_2": playlist(DUSK, SUNSET),
    "art_playlist_3": playlist(ASPHALT, "#F4F1EC"),
}

os.makedirs(OUT, exist_ok=True)
with tempfile.TemporaryDirectory() as tmp:
    for name, body in ART.items():
        svg = os.path.join(tmp, name + ".svg")
        with open(svg, "w") as f:
            f.write(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><defs>{TREE}</defs>{body}</svg>')
        subprocess.run(["rsvg-convert", "-w", str(SIZE), "-h", str(SIZE), svg, "-o", os.path.join(OUT, name + ".png")], check=True)
        print("wrote", name)
