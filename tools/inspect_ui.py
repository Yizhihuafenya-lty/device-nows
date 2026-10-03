#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""解析 uiautomator dump 出来的 XML，打印界面上的文本节点与可点击控件。

用法：
    adb shell uiautomator dump /sdcard/ui.xml
    adb pull /sdcard/ui.xml ui_dump.xml
    python tools/inspect_ui.py ui_dump.xml [--tap-target 目标文字]

--tap-target 会打印该节点中心点的屏幕坐标，方便直接用 adb shell input tap 点击。
输出中的中文按 UTF-8 写出，避免 Windows 控制台编码干扰。
"""

import sys
import xml.etree.ElementTree as ET


def parse_bounds(raw):
    """把 "[x1,y1][x2,y2]" 解析成 (x1, y1, x2, y2)。"""
    if not raw:
        return None
    try:
        parts = raw.replace("][", ",").strip("[]").split(",")
        x1, y1, x2, y2 = (int(p) for p in parts)
        return x1, y1, x2, y2
    except (ValueError, TypeError):
        return None


def center(bounds):
    x1, y1, x2, y2 = bounds
    return (x1 + x2) // 2, (y1 + y2) // 2


def walk(node, depth, out):
    text = node.get("text", "")
    desc = node.get("content-desc", "")
    cls = node.get("class", "").rsplit(".", 1)[-1]
    bounds = parse_bounds(node.get("bounds"))
    clickable = node.get("clickable") == "true"

    if text or desc or clickable:
        label = text or ("[desc] " + desc)
        extra = []
        if clickable:
            extra.append("可点击")
        if desc and text:
            extra.append("desc=" + desc)
        if node.get("checked") == "true":
            extra.append("已选中")
        suffix = ("  <" + ", ".join(extra) + ">") if extra else ""
        out.append("%s%s  %s%s  bounds=%s" % ("  " * depth, cls, label, suffix, bounds))

    for child in node:
        walk(child, depth + 1, out)


def flatten(node, acc):
    acc.append(node)
    for child in node:
        flatten(child, acc)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1

    path = sys.argv[1]
    target = None
    if "--tap-target" in sys.argv:
        target = sys.argv[sys.argv.index("--tap-target") + 1]

    tree = ET.parse(path)
    root = tree.getroot()

    out = []
    walk(root, 0, out)
    print("\n".join(out))

    nodes = []
    flatten(root, nodes)

    if target:
        print("\n--- 查找可点击目标: %s ---" % target)
        for node in nodes:
            if (node.get("text") == target or node.get("content-desc") == target):
                bounds = parse_bounds(node.get("bounds"))
                if bounds:
                    cx, cy = center(bounds)
                    print("命中: text=%r desc=%r bounds=%s" % (
                        node.get("text"), node.get("content-desc"), bounds))
                    print("adb shell input tap %d %d" % (cx, cy))
                    return 0
        print("未找到该目标")
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
