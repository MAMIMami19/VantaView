# Makes VantaView's own browse-tile pictures from Tabler Icons (MIT) and flag-icons (MIT).
import io, math, random, re, sys, os
import cairosvg
from PIL import Image, ImageDraw, ImageFilter, ImageChops

SP = sys.argv[1]; OUT = sys.argv[2]
ICONS = f"{SP}/tabler/icons/outline"; FLAGS = f"{SP}/flags/flags/4x3"
W, H = 640, 360

def hexc(h): h = h.lstrip('#'); return tuple(int(h[i:i+2], 16) for i in (0, 2, 4))

def glow(img, cx, cy, r, colour, strength):
    layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=colour + (strength,))
    layer = layer.filter(ImageFilter.GaussianBlur(r * 0.55))
    img.alpha_composite(layer)

def icon(name, size, colour=(255, 255, 255), stroke=1.6):
    svg = open(f"{ICONS}/{name}.svg").read()
    svg = svg.replace('stroke="currentColor"', 'stroke="#%02x%02x%02x"' % colour)
    svg = re.sub(r'stroke-width="[0-9.]+"', f'stroke-width="{stroke}"', svg)
    png = cairosvg.svg2png(bytestring=svg.encode(), output_width=size, output_height=size)
    return Image.open(io.BytesIO(png)).convert("RGBA")

def left_fade(img, start=0.28, end=0.62):
    fade = Image.new("L", (W, 1))
    for x in range(W):
        t = (x / W - start) / (end - start)
        fade.putpixel((x, 0), int(255 * min(1, max(0, t))))
    fade = fade.resize((W, H))
    base = Image.new("RGBA", (W, H), (10, 10, 14, 255))
    return Image.composite(img, base, fade)

def tile(key, colours, icon_name=None, flag=None, seed=0):
    rnd = random.Random(hash(key) & 0xffff)
    img = Image.new("RGBA", (W, H), (10, 10, 14, 255))
    a, b = hexc(colours[0]), hexc(colours[1])
    if flag:
        png = cairosvg.svg2png(url=f"{FLAGS}/{flag}.svg", output_width=440, output_height=330)
        f = Image.open(io.BytesIO(png)).convert("RGBA")
        # Cloth: gentle waves of light and shade.
        shade = Image.new("L", f.size)
        for x in range(f.size[0]):
            v = 150 + 70 * math.sin(x / 38.0 + 0.6) + 25 * math.sin(x / 13.0)
            for y in range(0, f.size[1], 1):
                shade.putpixel((x, y), max(0, min(255, int(v + 10 * math.sin(y / 30.0)))))
        f = ImageChops.multiply(f, Image.merge("RGBA", (shade, shade, shade, Image.new("L", f.size, 255))))
        # Wave the cloth slightly.
        waved = Image.new("RGBA", f.size, (0, 0, 0, 0))
        for x in range(f.size[0]):
            dy = int(8 * math.sin(x / 45.0))
            col = f.crop((x, 0, x + 1, f.size[1]))
            waved.paste(col, (x, dy))
        img.alpha_composite(waved, (W - 440 + 10, 15))
        img = img.filter(ImageFilter.GaussianBlur(0.6))
    else:
        glow(img, int(W * 0.74), int(H * 0.30), 150, a, 210)
        glow(img, int(W * 0.92), int(H * 0.86), 140, b, 200)
        glow(img, int(W * 0.58), int(H * 0.75), 90, b, 90)
        for _ in range(26):
            x, y = rnd.randint(int(W * 0.45), W), rnd.randint(0, H)
            r = rnd.choice([1, 1, 1.5, 2])
            ImageDraw.Draw(img).ellipse([x - r, y - r, x + r, y + r], fill=(255, 255, 255, rnd.randint(60, 170)))
        if icon_name:
            ic = icon(icon_name, 220)
            halo = icon(icon_name, 220, a, 3.2).filter(ImageFilter.GaussianBlur(9))
            ic = ic.rotate(rnd.uniform(-8, 8), resample=Image.BICUBIC)
            halo = halo.rotate(0)
            x, y = int(W * 0.62), int(H * 0.5) - 110
            img.alpha_composite(halo, (x, y)); img.alpha_composite(halo, (x, y))
            img.alpha_composite(ic, (x, y))
    img = left_fade(img)
    img.convert("RGB").save(f"{OUT}/browse_{key}.webp", "WEBP", quality=78, method=6)

TILES = [
    # Watch now
    ("trending", ("#3fc5f5", "#a24bf0"), "trending-up"), ("latest", ("#3fc5f5", "#a24bf0"), "clock-play"),
    ("toprated", ("#3fc5f5", "#a24bf0"), "star"),
    # Genres
    ("action", ("#ff6a2b", "#b3121d"), "flame"), ("adventure", ("#5fbf6a", "#1d5e3a"), "compass"),
    ("animation", ("#ffb84d", "#ff4fa3"), "balloon"), ("anime", ("#c86bff", "#ff4f7b"), "sparkles"),
    ("comedy", ("#ffd84a", "#ff8a2b"), "mood-happy"), ("crime", ("#4a7dff", "#d21f3c"), "fingerprint"),
    ("documentary", ("#e0b56a", "#7a5a2e"), "camera"), ("drama", ("#8a94b8", "#3b4466"), "masks-theater"),
    ("family", ("#ffcf5a", "#ff6f61"), "home-heart"), ("fantasy", ("#7fd3ff", "#6b4dff"), "wand"),
    ("history", ("#d9a55a", "#6e4a24"), "building-castle"), ("horror", ("#7a1020", "#2a3a2a"), "ghost"),
    ("music", ("#ff4fd8", "#4f7bff"), "music"), ("mystery", ("#b58a4a", "#3a2a5a"), "spy"),
    ("romance", ("#ff6f91", "#c2185b"), "heart"), ("scifi", ("#45e0ff", "#2747c7"), "rocket"),
    ("thriller", ("#9ad0ff", "#20304a"), "eye"), ("war", ("#9a8a5a", "#4a4030"), "helicopter"),
    ("western", ("#ffae5a", "#8a4a1e"), "horse"), ("kids", ("#5ad1ff", "#ffd84a"), "balloon"),
    ("reality", ("#ff9a4a", "#ff4f7b"), "device-tv"), ("news", ("#5a9aff", "#c7d4ff"), "news"),
    ("talk", ("#ff7a5a", "#7a4dff"), "microphone"),
    # Decades
    ("y1960", ("#ffb36b", "#c2185b"), "vinyl"), ("y1970", ("#ffae5a", "#8a4a1e"), "device-tv-old"),
    ("y1980", ("#ff4fd8", "#45e0ff"), "device-gamepad"), ("y1990", ("#7aff8a", "#2747c7"), "device-floppy"),
    ("y2000", ("#c7d4ff", "#4f7bff"), "disc"), ("y2010", ("#45e0ff", "#6b4dff"), "device-tv"),
    ("y2020", ("#a24bf0", "#ff4f7b"), "sparkles"),
    # Themes
    ("aliens", ("#7aff8a", "#2747c7"), "ufo"), ("disaster", ("#ff8a2b", "#4a4a5a"), "tornado"),
    ("heists", ("#ffd84a", "#3a3a3a"), "moneybag"), ("holidays", ("#ff4f4f", "#2e8b57"), "christmas-tree"),
    ("space", ("#6b4dff", "#45e0ff"), "planet"), ("superheroes", ("#ff3b3b", "#2747c7"), "mask"),
    ("zombies", ("#6a8a3a", "#5a1020"), "skull"), ("sport", ("#5fbf6a", "#ffd84a"), "ball-football"),
    ("monsters", ("#8a3aff", "#2a1a3a"), "spider"), ("timetravel", ("#45e0ff", "#ffae5a"), "hourglass"),
    ("roadtrip", ("#ffae5a", "#4f7bff"), "car"), ("survival", ("#8ab0c8", "#2a4a3a"), "mountain"),
]
FLAG_TILES = ["ar", "br", "cn", "fr", "de", "in", "ie", "it", "jp", "kr", "mx", "ng", "es", "se", "tr", "gb", "us", "dk", "no", "pl", "th", "ru", "au", "ca"]

os.makedirs(OUT, exist_ok=True)
for key, cols, ic in TILES: tile(key, cols, ic)
for c in FLAG_TILES: tile("flag_" + c, ("#ffffff", "#ffffff"), flag=c)
print("done", len(TILES) + len(FLAG_TILES))
