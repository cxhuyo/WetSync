"""生成模块图标：微信绿 + 小布蓝，中间双向箭头表示词库互传。"""
from PIL import Image, ImageDraw, ImageFilter
import math, os

S = 1024          # 基础画布
SS = 4            # 超采样倍数
W = S * SS

def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))

def rounded_bg(w, h, radius, top, bot):
    img = Image.new('RGB', (w, h), bot)
    d = ImageDraw.Draw(img)
    for y in range(h):
        t = y / max(1, h - 1)
        d.line([(0, y), (w, y)], fill=lerp(top, bot, t))
    mask = Image.new('L', (w, h), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, w - 1, h - 1], radius=radius, fill=255)
    out = Image.new('RGB', (w, h), (255, 255, 255))
    out.paste(img, (0, 0), mask)
    return out, mask

def arrow(d, cx, cy, length, thickness, color, direction=1):
    """画一个右向箭头（direction=-1 则左向），带圆角矩形箭杆"""
    h = thickness
    L = length
    shaft = h * 1.55
    head = h * 2.1
    pts = []
    x0, x1 = cx - L // 2, cx + L // 2
    if direction > 0:
        pts = [
            (x0, cy - shaft // 2), (x1 - head, cy - shaft // 2),
            (x1 - head, cy - h), (x1, cy),
            (x1 - head, cy + h), (x1 - head, cy + shaft // 2),
            (x0, cy + shaft // 2),
        ]
    else:
        pts = [
            (x1, cy - shaft // 2), (x0 + head, cy - shaft // 2),
            (x0 + head, cy - h), (x0, cy),
            (x0 + head, cy + h), (x0 + head, cy + shaft // 2),
            (x1, cy + shaft // 2),
        ]
    d.polygon(pts, fill=color)

def make(size, out_path, bg_transparent=False):
    w = h = size * SS
    r = int(w * 0.235)

    WX_GREEN_TOP = (40, 214, 120)
    WX_GREEN_BOT = (18, 168, 92)
    XB_BLUE = (76, 141, 255)
    XB_BLUE_D = (38, 96, 214)

    # 圆角底：上绿下蓝的斜向渐变
    base = Image.new('RGB', (w, h), (255, 255, 255))
    px = base.load()
    for y in range(h):
        for x in range(0, w, 4):
            t = (x / w * 0.35 + y / h * 0.65)
            if t < 0.5:
                c = lerp(WX_GREEN_TOP, WX_GREEN_BOT, t / 0.5)
            else:
                c = lerp(WX_GREEN_BOT, XB_BLUE_D, (t - 0.5) / 0.5 * 0.85)
            for dx in range(4):
                if x + dx < w:
                    px[x + dx, y] = c

    mask = Image.new('L', (w, h), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, w - 1, h - 1], radius=r, fill=255)
    img = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    img.paste(base, (0, 0), mask)

    # 一层柔和高光（左上）
    gloss = Image.new('L', (w, h), 0)
    ImageDraw.Draw(gloss).ellipse([-w * 0.35, -h * 0.55, w * 0.85, h * 0.42], fill=52)
    gloss = gloss.filter(ImageFilter.GaussianBlur(w * 0.06))
    from PIL import ImageChops
    gloss = ImageChops.multiply(gloss, mask)      # 只在圆角内部生效
    white = Image.new('RGBA', (w, h), (255, 255, 255, 255))
    white.putalpha(gloss)
    img = Image.alpha_composite(img, white)

    d = ImageDraw.Draw(img)

    cx, cy = w // 2, h // 2

    # 先画上下两个双向箭头（在方块背后向两侧伸出），表示词库互传
    alen = int(w * 0.72)          # 必须明显长于方块（方块宽 w*0.30），否则被完全盖住
    thick = int(w * 0.042)
    off = int(w * 0.175)
    arrow(d, cx, cy - off, alen, thick, (255, 255, 255, 245), 1)
    arrow(d, cx, cy + off, alen, thick, (255, 255, 255, 220), -1)

    # 中间白色圆角方块 —— 象征「词库」
    box = int(w * 0.30)
    bx0, by0 = cx - box // 2, cy - box // 2
    br = int(box * 0.26)
    # 投影
    sh = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(sh).rounded_rectangle(
        [bx0, by0 + int(box * 0.05), bx0 + box, by0 + box + int(box * 0.05)],
        radius=br, fill=(0, 0, 0, 90))
    sh = sh.filter(ImageFilter.GaussianBlur(w * 0.018))
    img = Image.alpha_composite(img, sh)
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([bx0, by0, bx0 + box, by0 + box], radius=br, fill=(255, 255, 255, 245))

    # 方块里画三条「词条」横线（象征词库内容）
    line_w = box * 0.44
    gap = box * 0.155
    ly = cy - gap
    for i, frac in enumerate((1.0, 0.78, 0.9)):
        lw = int(line_w * frac if i != 1 else line_w * 0.78)
        y = ly + i * gap
        d.rounded_rectangle(
            [cx - lw // 2, y - box * 0.026, cx + lw // 2, y + box * 0.026],
            radius=int(box * 0.026),
            fill=(150, 165, 190) if i else (110, 130, 160))

    # 缩放到目标尺寸
    final = img.resize((size, size), Image.LANCZOS)
    final.save(out_path, 'PNG')
    print('wrote', out_path, final.size)

if __name__ == '__main__':
    out = 'icon_src'
    os.makedirs(out, exist_ok=True)
    make(1024, os.path.join(out, 'ic_launcher_1024.png'))
    make(192, os.path.join(out, 'ic_192.png'))
