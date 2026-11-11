"""生成 banner(320x180) 与 icon(192x192)，纯标准库，无 Pillow 依赖。"""
import struct, zlib, os, math

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res", "drawable")
os.makedirs(OUT, exist_ok=True)

SS = 3  # 超采样倍数


def lerp(a, b, t):
    return a + (b - a) * t


def render(w, h, painter):
    W, H = w * SS, h * SS
    buf = [[(0, 0, 0)] * W for _ in range(H)]
    for y in range(H):
        for x in range(W):
            buf[y][x] = painter(x / SS, y / SS, w, h)
    # 下采样
    rows = []
    for y in range(h):
        row = bytearray()
        for x in range(w):
            r = g = b = 0
            for dy in range(SS):
                for dx in range(SS):
                    p = buf[y * SS + dy][x * SS + dx]
                    r += p[0]
                    g += p[1]
                    b += p[2]
            n = SS * SS
            row += bytes((r // n, g // n, b // n))
        rows.append(bytes(row))
    return rows


def write_png(path, w, h, rows):
    def chunk(typ, data):
        c = struct.pack(">I", len(data)) + typ + data
        return c + struct.pack(">I", zlib.crc32(typ + data) & 0xFFFFFFFF)

    raw = b"".join(b"\x00" + r for r in rows)
    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)


def in_triangle(px, py, a, b, c):
    def sign(p1, p2, p3):
        return (p1[0] - p3[0]) * (p2[1] - p3[1]) - (p2[0] - p3[0]) * (p1[1] - p3[1])

    d1 = sign((px, py), a, b)
    d2 = sign((px, py), b, c)
    d3 = sign((px, py), c, a)
    neg = d1 < 0 or d2 < 0 or d3 < 0
    pos = d1 > 0 or d2 > 0 or d3 > 0
    return not (neg and pos)


def make_banner(w, h):
    cx, cy = w * 0.5, h * 0.5
    scale = h / 180.0
    tri = ((cx - 17 * scale, cy - 24 * scale),
           (cx - 17 * scale, cy + 24 * scale),
           (cx + 26 * scale, cy))

    def paint(x, y, W, H):
        t = (x / W * 0.6 + y / H * 0.9)
        r = int(lerp(14, 12, t))
        g = int(lerp(38, 22, t))
        b = int(lerp(64, 46, t))
        # 中心柔光
        d = math.hypot(x - cx, y - cy) / (W * 0.72)
        glow = max(0.0, 1.0 - d) ** 2
        r = min(255, int(r + 26 * glow))
        g = min(255, int(g + 74 * glow))
        b = min(255, int(b + 120 * glow))
        # 圆环底座
        ring = math.hypot(x - cx, y - cy)
        if 46 * scale - 1.4 <= ring <= 46 * scale + 1.4:
            r, g, b = (150, 205, 255)
        if in_triangle(x, y, *tri):
            r, g, b = (245, 250, 255)
        return (r, g, b)

    return render(w, h, paint)


def make_icon(w, h):
    cx, cy = w * 0.5, h * 0.5
    rad = w * 0.22
    tri = ((cx - 17 * w / 192, cy - 21 * w / 192),
           (cx - 17 * w / 192, cy + 21 * w / 192),
           (cx + 24 * w / 192, cy))

    def paint(x, y, W, H):
        dx, dy = x - cx, y - cy
        corner = w * 0.22
        ax, ay = abs(dx), abs(dy)
        inside = (ax <= cx - corner * 0 and ay <= cy)  # 近似方形占位
        if ax > cx or ay > cy:
            return (0, 0, 0)
        t = (x / W + y / H) * 0.5
        r = int(lerp(16, 10, t))
        g = int(lerp(44, 24, t))
        b = int(lerp(74, 50, t))
        d = math.hypot(dx, dy) / (w * 0.7)
        glow = max(0.0, 1.0 - d) ** 2
        r = min(255, int(r + 22 * glow))
        g = min(255, int(g + 70 * glow))
        b = min(255, int(b + 116 * glow))
        if in_triangle(x, y, *tri):
            r, g, b = (245, 250, 255)
        return (r, g, b)

    return render(w, h, paint)


write_png(os.path.join(OUT, "banner.png"), 320, 180, make_banner(320, 180))
write_png(os.path.join(OUT, "icon.png"), 192, 192, make_icon(192, 192))
print("banner.png + icon.png ->", os.path.abspath(OUT))
