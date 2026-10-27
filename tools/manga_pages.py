"""Generates manga-like test pages for OcrEvalTest: vertical dialogue in speech bubbles, narration
boxes, furigana, sound effects, screentone and rough line art.

Each page comes clean, as a low-resolution scan and as a phone photo of a print, with <name>.txt
holding the text that should be read (one block per line; furigana and sound effects are left out,
as noise the recogniser should not need to read) and <name>.cols the same per column.

    python3 tools/manga_pages.py OUT_DIR FONT_DIR

Needs Pillow, Noto Sans/Serif CJK installed, and these OFL fonts from github.com/google/fonts in
FONT_DIR: ZenMaruGothic-Bold, KleeOne-SemiBold, MochiyPopOne-Regular, ZenAntique-Regular,
HachiMaruPop-Regular, YuseiMagic-Regular and DelaGothicOne-Regular (.ttf).
"""
import math, os, random, sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

OUT = sys.argv[1]
FONTS = sys.argv[2]
NOTO_SANS = ('/usr/share/fonts/noto-cjk/NotoSansCJK-Medium.ttc', 0)
NOTO_SERIF = ('/usr/share/fonts/noto-cjk/NotoSerifCJK-Regular.ttc', 0)
DIALOGUE_FONTS = [NOTO_SANS, (f'{FONTS}/ZenMaruGothic-Bold.ttf', 0), (f'{FONTS}/KleeOne-SemiBold.ttf', 0),
                  (f'{FONTS}/MochiyPopOne-Regular.ttf', 0), (f'{FONTS}/ZenAntique-Regular.ttf', 0),
                  (f'{FONTS}/HachiMaruPop-Regular.ttf', 0), (f'{FONTS}/YuseiMagic-Regular.ttf', 0)]
SFX_FONT = (f'{FONTS}/DelaGothicOne-Regular.ttf', 0)

# Each bubble is a list of columns, right to left.
DIALOGUE = [
    ['おい、待てよ！'], ['どこへ行くつもりだ？'], ['もう遅いよ', '電車は行っちゃった'],
    ['今日は本当に', 'ありがとう', 'ございました'], ['え……', 'うそでしょ？'], ['なんで', 'お前がここに', 'いるんだ！？'],
    ['先生が呼んでたよ'], ['ちょっと待って！', '財布を忘れた'], ['まさか', 'あいつが犯人だったとは'],
    ['大丈夫？', 'ケガはない？'], ['この街の', '平和は俺が守る'], ['ラーメン食べに', '行こうぜ'],
    ['約束したじゃない'], ['静かにしろ', '誰か来る'], ['ふざけるな！'], ['明日の試験', '自信ある？'],
    ['全然ダメだった……'], ['俺は', '絶対に諦めない'], ['見て！', '雪が降ってきた'], ['いただきます！'],
    ['そんなこと', '言われても', '困るよ'], ['魔法なんて', '存在しないって', '思ってた'], ['早く逃げろ！'],
    ['お腹すいたなぁ'], ['あの日のことは', '忘れられない'], ['ねえ、', '聞いてる？'], ['よし、', '作戦開始だ'],
    ['また明日ね'], ['ここは危険だ', '下がっていろ'], ['ずっと', '好きでした'], ['冗談だろ？'],
    ['宿題やった？'], ['ちょっとだけ', '休憩しよう'], ['ついに', '見つけたぞ'], ['行ってきます！'],
]
NARRATION = [['その頃、', '東京では――'], ['三年後'], ['翌朝'], ['彼女はまだ', '何も知らなかった'],
             ['夏休み最後の日']]
SFX = ['ドン', 'ゴゴゴ', 'バッ', 'ザワ', 'ドキドキ', 'シーン', 'ガシャン']
# (kanji in a bubble, its reading) for furigana.
FURIGANA = {'財布': 'さいふ', '犯人': 'はんにん', '試験': 'しけん', '魔法': 'まほう', '危険': 'きけん',
            '作戦': 'さくせん', '存在': 'そんざい', '宿題': 'しゅくだい', '休憩': 'きゅうけい'}

ROTATE = set('ー〜～…‥―－（）「」『』【】()-')
PUNCT = set('、。')
SMALL = set('ぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮ')

_font_cache = {}


def font(spec, size):
    key = (spec, size)
    if key not in _font_cache:
        _font_cache[key] = ImageFont.truetype(spec[0], size, index=spec[1])
    return _font_cache[key]


def draw_char(img, ch, x, y, size, spec, fill=0):
    """Draws ch in the cell whose top-left is (x, y), as it sits in vertical text."""
    f = font(spec, size)
    if ch in ROTATE:
        tile = Image.new('L', (size * 2, size * 2), 0)
        ImageDraw.Draw(tile).text((size, size), ch, font=f, fill=255, anchor='mm')
        tile = tile.rotate(-90)
        img.paste(fill, (x - size // 2, y - size // 2), tile)
        return
    dx = dy = 0
    if ch in PUNCT:
        dx, dy = int(size * 0.6), -int(size * 0.6)
    elif ch in SMALL:
        dx, dy = int(size * 0.1), -int(size * 0.1)
    ImageDraw.Draw(img).text((x + size // 2 + dx, y + size // 2 + dy), ch, font=f, fill=fill, anchor='mm')


def column_size(columns, size):
    pitch = int(size * 1.08)
    gap = int(size * 0.5)
    w = len(columns) * size + (len(columns) - 1) * gap
    h = max(len(c) for c in columns) * pitch
    return w, h, pitch, gap


def draw_columns(img, columns, left, top, size, spec, furigana=None, fill=0):
    w, h, pitch, gap = column_size(columns, size)
    for i, col in enumerate(columns):
        x = left + w - size - i * (size + gap)
        for j, ch in enumerate(col):
            draw_char(img, ch, x, top + j * pitch, size, spec, fill)
        if furigana:
            for word, reading in furigana.items():
                k = col.find(word)
                if k >= 0:
                    rs = max(10, int(size * 0.42))
                    span = len(word) * pitch
                    rp = span / len(reading)
                    for n, ch in enumerate(reading):
                        draw_char(img, ch, x + size + 1, int(top + k * pitch + n * rp + (rp - rs) / 2), rs, spec, fill)


def screentone(img, box, spacing, radius, value):
    d = ImageDraw.Draw(img)
    l, t, r, b = box
    for y in range(t, b, spacing):
        off = (y // spacing) % 2 * spacing // 2
        for x in range(l + off, r, spacing):
            d.ellipse((x - radius, y - radius, x + radius, y + radius), fill=value)


def line_art(img, box, rng):
    """Scribbles that stand in for drawings: curves, figures, speed lines, tone patches."""
    d = ImageDraw.Draw(img)
    l, t, r, b = box
    if rng.random() < 0.6:
        tl = rng.randint(l, (l + r) // 2)
        tt = rng.randint(t, (t + b) // 2)
        screentone(img, (tl, tt, min(r, tl + rng.randint(100, 400)), min(b, tt + rng.randint(80, 300))),
                   rng.choice([6, 8, 10]), rng.choice([1, 2]), rng.choice([0, 90]))
    if rng.random() < 0.35:
        cx, cy = rng.randint(l, r), rng.randint(t, b)
        for _ in range(60):
            a = rng.random() * 2 * math.pi
            r0, r1 = rng.randint(120, 200), rng.randint(400, 700)
            d.line((cx + r0 * math.cos(a), cy + r0 * math.sin(a), cx + r1 * math.cos(a), cy + r1 * math.sin(a)),
                   fill=0, width=rng.choice([1, 2]))
    for _ in range(rng.randint(2, 5)):
        cx, cy = rng.randint(l, r), rng.randint(t, b)
        rx, ry = rng.randint(30, 140), rng.randint(40, 180)
        d.ellipse((cx - rx, cy - ry, cx + rx, cy + ry), outline=0, width=rng.choice([2, 3, 4]))
        if rng.random() < 0.5:
            d.ellipse((cx - rx // 4, cy - ry // 3, cx + rx // 6, cy - ry // 6), fill=0)
    for _ in range(rng.randint(4, 10)):
        pts = [(rng.randint(l, r), rng.randint(t, b))]
        for _ in range(rng.randint(2, 5)):
            px, py = pts[-1]
            pts.append((min(r, max(l, px + rng.randint(-120, 120))), min(b, max(t, py + rng.randint(-120, 120)))))
        d.line(pts, fill=rng.choice([0, 0, 60]), width=rng.choice([2, 3, 5]), joint='curve')


def panels(w, h, rng):
    """Panel boxes in reading order: rows top to bottom, panels right to left."""
    m, g = 40, 18
    rows = rng.choice([2, 3, 3])
    cuts = sorted(rng.sample(range(int(h * 0.25), int(h * 0.75)), rows - 1)) if rows > 1 else []
    ys = [m] + cuts + [h - m]
    out = []
    for i in range(rows):
        t, b = ys[i] + (g // 2 if i else 0), ys[i + 1] - (g // 2 if i < rows - 1 else 0)
        n = rng.choice([1, 2, 2])
        if n == 1:
            out.append((m, t, w - m, b))
        else:
            x = rng.randint(int(w * 0.35), int(w * 0.65))
            out.append((x + g // 2, t, w - m, b))
            out.append((m, t, x - g // 2, b))
    return out


def page(seed):
    rng = random.Random(seed)
    W, H = 1100, 1600
    img = Image.new('L', (W, H), 255)
    d = ImageDraw.Draw(img)
    truth = []
    columns = []
    dialogue = DIALOGUE[:]
    rng.shuffle(dialogue)
    for pi, (l, t, r, b) in enumerate(panels(W, H, rng)):
        # Art stays inside its panel, as it would be drawn.
        layer = img.copy()
        line_art(layer, (l, t, r, b), rng)
        mask = Image.new('L', img.size, 0)
        ImageDraw.Draw(mask).rectangle((l, t, r, b), fill=255)
        img.paste(layer, mask=mask)
        if rng.random() < 0.4:
            sfx = rng.choice(SFX)
            s = rng.randint(70, 120)
            tile = Image.new('L', (s * len(sfx) + 40, s + 40), 0)
            ImageDraw.Draw(tile).text((20, 20), sfx, font=font(SFX_FONT, s), fill=255)
            tile = tile.rotate(rng.randint(-30, 30), expand=True)
            if tile.width < r - l and tile.height < b - t:
                img.paste(0, (rng.randint(l, r - tile.width), rng.randint(t, b - tile.height)), tile)
        occupied = []
        if pi == 0 or rng.random() < 0.2:
            cols = rng.choice(NARRATION)
            size = rng.randint(22, 28)
            cw, ch, *_ = column_size(cols, size)
            box = (r - cw - 30, t + 8, r - 8, t + ch + 30)
            if box[0] > l and box[3] < b:
                d.rectangle(box, fill=255, outline=0, width=3)
                draw_columns(img, cols, box[0] + 11, box[1] + 11, size, NOTO_SERIF)
                occupied.append(box)
                truth.append((box, ''.join(cols)))
                columns.extend(cols)
        for _ in range(rng.choice([1, 2, 2, 3])):
            if not dialogue:
                break
            cols = dialogue.pop()
            spec = rng.choice(DIALOGUE_FONTS)
            size = rng.randint(24, 36)
            cw, ch, *_ = column_size(cols, size)
            ew, eh = int(cw * 1.45 + 40), int(ch * 1.3 + 40)
            if ew > r - l - 20 or eh > b - t - 20:
                dialogue.insert(0, cols)
                continue
            for _ in range(40):
                el = rng.randint(l + 10, r - ew - 10)
                et = rng.randint(t + 10, b - eh - 10)
                box = (el, et, el + ew, et + eh)
                if all(box[2] < o[0] or o[2] < box[0] or box[3] < o[1] or o[3] < box[1] for o in occupied):
                    break
            else:
                dialogue.insert(0, cols)
                continue
            occupied.append(box)
            # Tail, then the bubble over its base.
            tx, ty = (box[0] + box[2]) // 2, box[3]
            tip = (tx + rng.randint(-60, 60), min(b - 5, ty + rng.randint(30, 70)))
            d.polygon([(tx - 18, ty - 20), (tx + 18, ty - 20), tip], fill=255, outline=0)
            d.line([(tx - 18, ty - 10), tip, (tx + 18, ty - 10)], fill=0, width=3)
            d.ellipse(box, fill=255, outline=0, width=3)
            furi = {k: v for k, v in FURIGANA.items() if any(k in c for c in cols)} if rng.random() < 0.7 else None
            draw_columns(img, cols, box[0] + (ew - cw) // 2, box[1] + (eh - ch) // 2, size, spec, furi)
            truth.append((box, ''.join(cols)))
            columns.extend(cols)
        d.rectangle((l, t, r, b), outline=0, width=4)
    return img.convert('RGB'), [text for _, text in truth], columns


def scan(img, rng):
    """A low-resolution scan: smaller, softer, JPEG-compressed."""
    small = img.resize((img.width * 3 // 5, img.height * 3 // 5), Image.BILINEAR)
    return small.filter(ImageFilter.GaussianBlur(0.6))


def photo(img, rng):
    """A phone photo of a printed page: perspective, uneven light, blur and noise."""
    w, h = img.size
    bg = Image.new('RGB', (w + 200, h + 200), (90, 80, 70))
    bg.paste(img, (100, 100))
    j = lambda: rng.randint(-50, 50)
    quad = (100 + j(), 100 + j(), 100 + j(), h + 100 + j(), w + 100 + j(), h + 100 + j(), w + 100 + j(), 100 + j())
    out = bg.transform((w, h), Image.QUAD, quad, Image.BICUBIC).rotate(rng.uniform(-3, 3), Image.BICUBIC,
                                                                     fillcolor=(90, 80, 70))
    light = Image.linear_gradient('L').resize((w, h)).rotate(rng.randint(0, 360))
    shade = Image.merge('RGB', [light.point(lambda v: 150 + v * 105 // 255)] * 3)
    out = Image.composite(out, Image.new('RGB', (w, h), 0), shade.convert('L'))
    out = Image.blend(out, Image.new('RGB', (w, h), (255, 240, 220)), 0.06)
    noise = Image.effect_noise((w, h), 18).convert('RGB')
    out = Image.blend(out, noise, 0.06)
    return out.filter(ImageFilter.GaussianBlur(0.9))


os.makedirs(OUT, exist_ok=True)
for seed in range(6):
    img, truth, columns = page(seed)
    rng = random.Random(100 + seed)
    for variant, fn, quality in [('clean', None, 95), ('scan', scan, 55), ('photo', photo, 75)]:
        out = fn(img, rng) if fn else img
        name = f'manga{seed + 1}_{variant}'
        out.save(f'{OUT}/{name}.jpg', quality=quality)
        with open(f'{OUT}/{name}.txt', 'w') as f:
            f.write('\n'.join(truth) + '\n')
        with open(f'{OUT}/{name}.cols', 'w') as f:
            f.write('\n'.join(columns) + '\n')
