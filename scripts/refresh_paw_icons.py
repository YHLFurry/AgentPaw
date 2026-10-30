"""Export the shared hand-drawn vector mark using Python + Pillow (no network)."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
ICONS = ROOT / 'agentpaw-core/src/main/resources/icons'
ANDROID = '{http://schemas.android.com/apk/res/android}'
source = ET.parse(ROOT / 'app/src/main/res/drawable/ic_paw.xml').getroot()
paths = [node.get(ANDROID + 'pathData') for node in source.iter('path')]
foreground = '<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n    <group android:translateY="2">\n'
foreground += '\n'.join(f'        <path android:pathData="{d}" android:fillColor="#00000000" android:strokeColor="#FFFFFFFF" android:strokeWidth="2.8" android:strokeLineCap="round" android:strokeLineJoin="round" />' for d in paths)
foreground += '\n    </group>\n</vector>\n'
(ROOT / 'app/src/main/res/drawable/ic_launcher_foreground.xml').write_text(foreground, encoding='utf-8')


def contours(data):
    tokens = re.findall(r'[MCZ]|-?\d+(?:\.\d+)?', data)
    i, points, current = 0, [], (0., 0.)
    while i < len(tokens):
        command = tokens[i]
        i += 1
        if command == 'M':
            current = tuple(map(float, tokens[i:i+2]))
            i += 2
            points.append(current)
        elif command == 'C':
            a, b, c, d, e, f = map(float, tokens[i:i+6])
            i += 6
            x, y = current
            for step in range(1, 49):
                t = step / 48
                u = 1 - t
                points.append((u**3*x + 3*u*u*t*a + 3*u*t*t*c + t**3*e,
                               u**3*y + 3*u*u*t*b + 3*u*t*t*d + t**3*f))
            current = (e, f)
        elif command == 'Z':
            points.append(points[0])
        else:
            raise ValueError(command)
    return points

def paw(size, color, launcher=False):
    scale = 4
    image = Image.new('RGBA', (size*scale, size*scale))
    draw = ImageDraw.Draw(image)
    viewport = 108 if launcher else 80
    factor = size*scale/viewport
    dx, dy = (0, 2) if launcher else (-14, -11)
    width = max(1, round(2.8*factor))
    radius = width/2
    for path in paths:
        pts = [((x+dx)*factor, (y+dy)*factor) for x, y in contours(path)]
        draw.line(pts, fill=color, width=width, joint='curve')
        for x,y in pts:
            draw.ellipse((x-radius, y-radius, x+radius, y+radius), fill=color)
    return image.resize((size,size), Image.Resampling.LANCZOS)

body = '\n'.join(f'<path d="{p}"/>' for p in paths)
svg = '<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="14 11 80 80" role="img" aria-label="AgentPaw">\n<g fill="none" stroke="COLOR" stroke-width="2.8" stroke-linecap="round" stroke-linejoin="round">\n'+body+'\n</g>\n</svg>\n'
for suffix, color in [('', '#7C5CDB'), ('-mono', 'currentColor')]:
    (ICONS/f'agentpaw-logo{suffix}.svg').write_text(svg.replace('COLOR', color), encoding='utf-8')
for size in [16,32,64,128,512]:
    for suffix,color in [('', '#7C5CDB'),('-mono-black', '#000000'),('-mono-white', '#FFFFFF')]:
        paw(size,color).save(ICONS/f'agentpaw-logo{suffix}-{size}.png')
# Preserve existing outline lettering; replace only the icon group.
wordmark = ICONS/'agentpaw-logo-wordmark.svg'
s = wordmark.read_text(encoding='utf-8')
s = re.sub(r'  <!--.*?-->\s*', '', s, flags=re.S)
s = re.sub(r'  <defs>.*?</defs>\s*', '', s, flags=re.S)
s = re.sub(r'  <g transform=".*?</g>', '  <g transform="translate(-11.6 -7.4) scale(1.4)" fill="none" stroke="#7C5CDB" stroke-width="2.8" stroke-linecap="round" stroke-linejoin="round">\n'+body+'\n  </g>', s, count=1, flags=re.S)
wordmark.write_text(s,encoding='utf-8')
for file in ICONS.glob('agentpaw-logo-wordmark-*.png'):
    with Image.open(file) as old:
        image=old.convert('RGBA')
    factor=image.width/531.17
    x,y,extent=round(8*factor),round(8*factor),round(112*factor)
    image.paste((0,0,0,0),(0,0,round(128*factor),image.height))
    image.alpha_composite(paw(extent, '#FFFFFF' if 'mono-white' in file.name else '#7C5CDB'), (x,y))
    image.save(file)
preview=Image.new('RGBA',(512,512),'#7C5CDB')
preview.alpha_composite(paw(512,'#FFFFFF',launcher=True))
preview.convert('RGB').save(ROOT/'docs/icon-preview.png')
print('Exported launcher preview and matching SVG/PNG logo resources.')
