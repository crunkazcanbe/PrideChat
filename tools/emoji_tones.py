#!/usr/bin/env python3
"""Builds assets/pridechat/textures/emoji_tones.png + emoji_tones.json: the 5 skin tones of every single-codepoint
emoji in PrideChat's sheet that has them (Twemoji 14 PNGs, CC-BY 4.0; tone list from emojibase-data, MIT)."""
import io, json, os, urllib.request
from concurrent.futures import ThreadPoolExecutor
from PIL import Image

A = os.path.expanduser("~/mc-mods/pridechat/src/main/resources/assets/pridechat")
SIZE, COLS = 32, 40
base = json.load(open(os.path.join(A, "emoji.json")))["emoji"]
strip = lambda s: s.replace("️", "")
index = {strip(e["u"]): i for i, e in enumerate(base)}

data = json.load(urllib.request.urlopen("https://cdn.jsdelivr.net/npm/emojibase-data@15/en/compact.json"))
print(len(index), "base emoji")
jobs = []                                   # (base index, tone 1-5, unicode, hexcode)
for e in data:
    if not e.get("skins"): continue
    b = index.get(strip(e["unicode"]))
    if b is None or len(strip(e["unicode"])) > 1: continue          # single emoji only (no ZWJ families)
    for s in e["skins"]:
        hx = s["hexcode"].upper().split("-")
        tones = [h for h in hx if h in ("1F3FB", "1F3FC", "1F3FD", "1F3FE", "1F3FF")]
        if len(tones) != 1: continue
        jobs.append((b, int(tones[0], 16) - 0x1F3FA, s["unicode"], s["hexcode"]))

def fetch(j):
    hexes = [h for h in j[3].lower().split("-") if h != "fe0f"]
    url = "https://cdn.jsdelivr.net/gh/twitter/twemoji@14.0.2/assets/72x72/" + "-".join(hexes) + ".png"
    try:
        return Image.open(io.BytesIO(urllib.request.urlopen(url, timeout=20).read())).convert("RGBA").resize((SIZE, SIZE), Image.LANCZOS)
    except Exception as ex:
        print("skip", url, ex); return None

with ThreadPoolExecutor(16) as ex: imgs = list(ex.map(fetch, jobs))
ok = [(j, im) for j, im in zip(jobs, imgs) if im is not None]
rows = (len(ok) + COLS - 1) // COLS
sheet = Image.new("RGBA", (COLS * SIZE, rows * SIZE), (0, 0, 0, 0))
out = []
for i, (j, im) in enumerate(ok):
    sheet.paste(im, ((i % COLS) * SIZE, (i // COLS) * SIZE))
    out.append({"b": j[0], "t": j[1], "u": j[2]})
sheet.save(os.path.join(A, "textures/emoji_tones.png"), optimize=True)
json.dump({"size": SIZE, "cols": COLS, "rows": rows, "tones": out}, open(os.path.join(A, "emoji_tones.json"), "w"), ensure_ascii=False)
print(len(out), "tone sprites,", rows, "rows")
