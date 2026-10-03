#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 jsDelivr 拉取 Google 官方 Material Design 图标（Apache-2.0），
转换成 Android vector drawable，输出到 app/src/main/res/drawable/。

用法：python tools/fetch_material_icons.py
"""

import os
import re
import sys
import urllib.request

VERSION = "0.14.13"
BASE = "https://cdn.jsdelivr.net/npm/@material-design-icons/svg@%s/filled/%s.svg"

# 本地资源名 -> Material 图标名
ICONS = {
    "ic_nav_all": "list",
    "ic_nav_device": "smartphone",
    "ic_nav_system": "android",
    "ic_nav_cpu": "memory",
    "ic_nav_memory": "storage",
    "ic_nav_display": "aspect_ratio",
    "ic_nav_battery": "battery_full",
    "ic_nav_network": "wifi",
    "ic_nav_sensor": "sensors",
    "ic_nav_camera": "photo_camera",
    "ic_nav_app": "apps",
    # 新增分组图标
    "ic_nav_features": "grid_view",
    "ic_nav_media": "perm_media",
    "ic_nav_motion": "touch_app",
    # 控件图标
    "ic_check": "check_circle",
    "ic_cross": "cancel",
    "ic_chevron_right": "chevron_right",
    "ic_settings": "settings",
    "ic_refresh": "refresh",
    "ic_copy": "content_copy",
    "ic_info": "info",
}

VECTOR_TEMPLATE = """<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
%s</vector>
"""

PATH_TEMPLATE = """    <path
        android:fillColor="#FF000000"
        android:pathData="%s" />
"""


def fetch(name):
    url = BASE % (VERSION, name)
    with urllib.request.urlopen(url, timeout=30) as resp:
        return resp.read().decode("utf-8")


def extract_paths(svg):
    """提取所有 <path> 的 d 属性，跳过 fill="none" 的占位路径。"""
    result = []
    for tag in re.findall(r"<path\b[^>]*/?>", svg):
        d = re.search(r'\sd="([^"]+)"', tag)
        if not d:
            continue
        fill = re.search(r'\sfill="([^"]+)"', tag)
        if fill and fill.group(1) == "none":
            continue
        result.append(d.group(1).strip())
    return result


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out_dir = os.path.join(root, "app", "src", "main", "res", "drawable")
    os.makedirs(out_dir, exist_ok=True)

    failed = []
    for local, material in ICONS.items():
        try:
            svg = fetch(material)
        except Exception as exc:  # noqa: BLE001
            failed.append((local, material, str(exc)))
            print("FAIL  %-16s <- %-18s %s" % (local, material, exc))
            continue

        paths = extract_paths(svg)
        if not paths:
            failed.append((local, material, "未提取到 path"))
            print("FAIL  %-16s <- %-18s 未提取到 path" % (local, material))
            continue

        body = "".join(PATH_TEMPLATE % d for d in paths)
        content = VECTOR_TEMPLATE % body
        with open(os.path.join(out_dir, local + ".xml"), "w", encoding="utf-8") as fh:
            fh.write(content)
        print("OK    %-16s <- %-18s %d 条路径, %d 字节" % (local, material, len(paths), len(content)))

    if failed:
        print("\n以下图标未能获取，需要手工处理：")
        for local, material, why in failed:
            print("  %s (%s): %s" % (local, material, why))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
