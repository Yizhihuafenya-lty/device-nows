#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 Android 启动图标（纯标准库实现，不依赖 Pillow）。

用法：python tools/gen_icons.py
输出：app/src/main/res/mipmap-*/ic_launcher.png 以及 artwork/ic_launcher_512.png
"""

import os
import struct
import zlib

SS = 4  # 超采样倍数，用于抗锯齿

# 每个密度目录对应的图标边长（px）
DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

TOP_RGB = (66, 133, 244)
BOTTOM_RGB = (21, 101, 192)
WHITE = (255, 255, 255)


def rounded_rect_hit(dx, dy, hw, hh, r):
    """判断点 (dx, dy)（相对矩形中心）是否落在圆角矩形内。"""
    qx = abs(dx) - (hw - r)
    qy = abs(dy) - (hh - r)
    if qx < 0.0:
        qx = 0.0
    if qy < 0.0:
        qy = 0.0
    return (qx * qx + qy * qy) ** 0.5 - r <= 0.0


def rasterize(size):
    """以 SS 倍分辨率光栅化，返回 (N, bg, ring, screen) 三张覆盖位图。"""
    n = size * SS
    bg = bytearray(n * n)
    ring = bytearray(n * n)
    screen = bytearray(n * n)

    half = n / 2.0
    corner = 0.22 * n

    phone_hw = 0.150 * n
    phone_hh = 0.285 * n
    phone_r = 0.052 * n
    frame = 0.030 * n
    inner_r = max(phone_r - frame, 0.0)

    for y in range(n):
        dy = y + 0.5 - half
        row = y * n
        for x in range(n):
            dx = x + 0.5 - half
            i = row + x
            if not rounded_rect_hit(dx, dy, half, half, corner):
                continue
            bg[i] = 1
            if rounded_rect_hit(dx, dy, phone_hw, phone_hh, phone_r):
                if rounded_rect_hit(dx, dy, phone_hw - frame, phone_hh - frame, inner_r):
                    screen[i] = 1
                else:
                    ring[i] = 1
    return n, bg, ring, screen


def downsample(size, n, bg, ring, screen):
    """把 SS 倍覆盖位图盒式降采样为最终 RGBA 像素。"""
    out = bytearray(size * size * 4)
    inv = 1.0 / (SS * SS)

    for oy in range(size):
        for ox in range(size):
            cr = cg = cb = 0.0
            covered = 0
            for sy in range(SS):
                y = oy * SS + sy
                t = y / (n - 1.0)
                br = TOP_RGB[0] + (BOTTOM_RGB[0] - TOP_RGB[0]) * t
                bgc = TOP_RGB[1] + (BOTTOM_RGB[1] - TOP_RGB[1]) * t
                bb = TOP_RGB[2] + (BOTTOM_RGB[2] - TOP_RGB[2]) * t
                base = y * n
                for sx in range(SS):
                    i = base + ox * SS + sx
                    if not bg[i]:
                        continue
                    covered += 1
                    if ring[i]:
                        cr += WHITE[0]
                        cg += WHITE[1]
                        cb += WHITE[2]
                    elif screen[i]:
                        # 屏幕区域：底色叠一层 20% 白色
                        cr += br * 0.8 + 255 * 0.2
                        cg += bgc * 0.8 + 255 * 0.2
                        cb += bb * 0.8 + 255 * 0.2
                    else:
                        cr += br
                        cg += bgc
                        cb += bb

            o = (oy * size + ox) * 4
            if covered:
                out[o] = int(cr / covered + 0.5)
                out[o + 1] = int(cg / covered + 0.5)
                out[o + 2] = int(cb / covered + 0.5)
                out[o + 3] = int(covered * 255 * inv + 0.5)
    return bytes(out)


def write_png(path, width, height, rgba):
    """写出 8 位 RGBA PNG。"""
    raw = bytearray()
    stride = width * 4
    for y in range(height):
        raw.append(0)  # filter type 0 (None)
        raw += rgba[y * stride:(y + 1) * stride]

    def chunk(tag, data):
        return (
            struct.pack(">I", len(data))
            + tag
            + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        )

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")

    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as fh:
        fh.write(png)


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    res = os.path.join(root, "app", "src", "main", "res")

    for density, size in DENSITIES.items():
        n, bg, ring, screen = rasterize(size)
        rgba = downsample(size, n, bg, ring, screen)
        out = os.path.join(res, "mipmap-" + density, "ic_launcher.png")
        write_png(out, size, size, rgba)
        print("生成 %-58s %dx%d" % (os.path.relpath(out, root), size, size))

    # 额外输出一张 512 图，方便上架或做宣传图
    size = 512
    n, bg, ring, screen = rasterize(size)
    rgba = downsample(size, n, bg, ring, screen)
    out = os.path.join(root, "artwork", "ic_launcher_512.png")
    write_png(out, size, size, rgba)
    print("生成 %-58s %dx%d" % (os.path.relpath(out, root), size, size))


if __name__ == "__main__":
    main()
