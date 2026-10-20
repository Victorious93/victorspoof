"""Derive docs/logo.png and launcher icons from the fleet logo (no new art is generated)."""
import os, sys
from PIL import Image, ImageDraw
src = sys.argv[1] if len(sys.argv) > 1 else '/workspace/fleet-logos/victorspoof-logo.jpg'
im = Image.open(src).convert('RGB')
w, h = im.size
s = min(w, h)
im = im.crop(((w - s) // 2, (h - s) // 2, (w - s) // 2 + s, (h - s) // 2 + s))
im.save('docs/logo.png', optimize=True)
bg = im.getpixel((s // 2, int(s * 0.2)))
print('charcoal sample', bg)
mask = Image.new('L', (s, s), 0)
ImageDraw.Draw(mask).ellipse((int(s*0.01),)*2 + (int(s*0.99),)*2, fill=255)
disc = im.convert('RGBA'); disc.putalpha(mask)
os.makedirs('app/src/main/res/values', exist_ok=True)
open('app/src/main/res/values/ic_launcher_background.xml', 'w').write(
    '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_launcher_background">#%02X%02X%02X</color>\n</resources>\n' % bg[:3])
for d, f in {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}.items():
    p = f'app/src/main/res/mipmap-{d}'; os.makedirs(p, exist_ok=True)
    fs = int(108 * f); inner = int(fs * 72 / 108)
    fg = Image.new('RGBA', (fs, fs), (0, 0, 0, 0))
    fg.alpha_composite(disc.resize((inner, inner), Image.LANCZOS), ((fs - inner) // 2,) * 2)
    fg.save(f'{p}/ic_launcher_foreground.png')
os.makedirs('app/src/main/res/drawable-nodpi', exist_ok=True)
disc.resize((256, 256), Image.LANCZOS).save('app/src/main/res/drawable-nodpi/logo.png')
