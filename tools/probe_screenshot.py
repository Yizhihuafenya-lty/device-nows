#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""对 adb screencap 截图做像素级检查，用于在没有图像预览能力时核对配色与布局。

用法：
    python tools/probe_screenshot.py 截图.png
    python tools/probe_screenshot.py 截图.png --at 10,600 600,520   # 采样指定坐标
"""

import collections
import struct
import sys
import zlib

# 设计稿配色，用于比对
PALETTE = {
    "page_background": (0xF1, 0xF3, 0xF6),
    "card_background": (0xFF, 0xFF, 0xFF),
    "brand_primary": (0x15, 0x65, 0xC0),
    "text_primary": (0x1B, 0x1C, 0x1E),
    "text_secondary": (0x6B, 0x72, 0x80),
}


def _paeth(a, b, c):
    p = a + b - c
    pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
    if pa <= pb and pa <= pc:
        return a
    return b if pb <= pc else c


def read_png(path):
    """解码 8 位 RGB/RGBA PNG，返回 (宽, 高, 每行 bytes)。"""
    data = open(path, "rb").read()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("不是 PNG 文件")

    pos, idat = 8, b""
    width = height = depth = ctype = None
    while pos < len(data):
        length = struct.unpack(">I", data[pos:pos + 4])[0]
        tag = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + length]
        if tag == b"IHDR":
            width, height, depth, ctype = struct.unpack(">IIBB", chunk[:10])
        elif tag == b"IDAT":
            idat += chunk
        elif tag == b"IEND":
            break
        pos += 12 + length

    if depth != 8 or ctype not in (2, 6):
        raise ValueError("仅支持 8 位 RGB/RGBA，实际 depth=%s ctype=%s" % (depth, ctype))

    channels = 3 if ctype == 2 else 4
    stride = width * channels
    raw = zlib.decompress(idat)

    rows = []
    prev = bytearray(stride)
    i = 0
    for _ in range(height):
        ftype = raw[i]
        i += 1
        line = bytearray(raw[i:i + stride])
        i += stride
        if ftype == 1:
            for x in range(channels, stride):
                line[x] = (line[x] + line[x - channels]) & 255
        elif ftype == 2:
            for x in range(stride):
                line[x] = (line[x] + prev[x]) & 255
        elif ftype == 3:
            for x in range(stride):
                a = line[x - channels] if x >= channels else 0
                line[x] = (line[x] + ((a + prev[x]) >> 1)) & 255
        elif ftype == 4:
            for x in range(stride):
                a = line[x - channels] if x >= channels else 0
                b = prev[x]
                c = prev[x - channels] if x >= channels else 0
                line[x] = (line[x] + _paeth(a, b, c)) & 255
        rows.append(bytes(line))
        prev = line

    return width, height, channels, rows


def pixel(rows, channels, x, y):
    off = x * channels
    return rows[y][off], rows[y][off + 1], rows[y][off + 2]


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    path = sys.argv[1]
    width, height, channels, rows = read_png(path)
    print("图像: %s  %dx%d  通道=%d" % (path, width, height, channels))

    if "--at" in sys.argv:
        coords = sys.argv[sys.argv.index("--at") + 1]
        print("\n--- 定点采样 ---")
        for pair in coords.split():
            x, y = (int(v) for v in pair.split(","))
            r, g, b = pixel(rows, channels, x, y)
            print("  (%4d,%4d)  #%02X%02X%02X" % (x, y, r, g, b))

    # 全图主色统计（每 4 像素抽样，降低耗时）
    counter = collections.Counter()
    for y in range(0, height, 4):
        for x in range(0, width, 4):
            counter[pixel(rows, channels, x, y)] += 1

    total = sum(counter.values())
    print("\n--- 出现最多的 12 种颜色 ---")
    for (r, g, b), count in counter.most_common(12):
        print("  #%02X%02X%02X  %6.2f%%" % (r, g, b, 100.0 * count / total))

    print("\n--- 设计稿配色命中情况 ---")
    for name, (pr, pg, pb) in PALETTE.items():
        hit = 0
        for (r, g, b), count in counter.items():
            if abs(r - pr) <= 6 and abs(g - pg) <= 6 and abs(b - pb) <= 6:
                hit += count
        print("  %-16s #%02X%02X%02X  %6.2f%%" % (name, pr, pg, pb, 100.0 * hit / total))
    return 0


if __name__ == "__main__":
    sys.exit(main())
