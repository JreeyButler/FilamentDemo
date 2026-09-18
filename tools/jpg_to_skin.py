#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把一张普通 JPG/PNG 图片自动转换成车模可用的车衣（skin）资源。

原理：车模外观车漆的 base color 是一张 UV 图集（车身钣金区为青色）。
本脚本把输入图片按指定方式缩放到图集尺寸，只覆盖"青色车身区"并保留原
AO/描边（可调），输出到 app/src/main/assets/skins/，App 启动后会自动发现。

用法示例：
    # 默认 cover：等比铺满并居中裁剪
    python3 tools/jpg_to_skin.py my_art.jpg

    # 完整放入（四周用底色填充）
    python3 tools/jpg_to_skin.py logo.png --mode contain --base-color "#101018"

    # 拉伸铺满 / 平铺
    python3 tools/jpg_to_skin.py pattern.jpg --mode stretch
    python3 tools/jpg_to_skin.py tile.jpg --mode tile --tile-size 1024

    # 指定输出名与明暗保留强度
    python3 tools/jpg_to_skin.py art.jpg -o my_skin.jpg --shading 0.7

依赖：Pillow。不参与 APK 打包。
"""

import argparse
import os
import sys

from PIL import Image

# 允许直接以脚本方式运行（把 tools/ 加入模块搜索路径）
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from make_skin import (  # noqa: E402
    ATLAS_IMAGE_INDEX,
    GLB,
    OUT_DIR,
    build_body_mask,
    compose,
    extract_images,
)

MODES = ("cover", "contain", "stretch", "tile")


def parse_color(text):
    text = text.lstrip("#")
    if len(text) == 6:
        return tuple(int(text[i:i + 2], 16) for i in (0, 2, 4))
    raise argparse.ArgumentTypeError("颜色格式应为 #RRGGBB")


def fit_pattern(img, size, mode, base_color, tile_size):
    """把输入图片适配到 size×size 的图集空间。"""
    if mode == "stretch":
        return img.resize((size, size), Image.LANCZOS)

    if mode == "cover":
        ratio = max(size / img.width, size / img.height)
        nw = max(1, int(img.width * ratio + 0.5))
        nh = max(1, int(img.height * ratio + 0.5))
        resized = img.resize((nw, nh), Image.LANCZOS)
        left = (nw - size) // 2
        top = (nh - size) // 2
        return resized.crop((left, top, left + size, top + size))

    if mode == "contain":
        canvas = Image.new("RGB", (size, size), base_color)
        ratio = min(size / img.width, size / img.height)
        nw = max(1, int(img.width * ratio + 0.5))
        nh = max(1, int(img.height * ratio + 0.5))
        resized = img.resize((nw, nh), Image.LANCZOS)
        canvas.paste(resized, ((size - nw) // 2, (size - nh) // 2))
        return canvas

    if mode == "tile":
        tile = img if tile_size <= 0 else img.resize(
            (tile_size, tile_size), Image.LANCZOS)
        canvas = Image.new("RGB", (size, size), base_color)
        for y in range(0, size, tile.height):
            for x in range(0, size, tile.width):
                canvas.paste(tile, (x, y))
        return canvas

    raise ValueError("unknown mode: " + mode)


def default_output(name):
    stem = os.path.splitext(os.path.basename(name))[0]
    return os.path.join(OUT_DIR, stem + ".jpg")


def main():
    parser = argparse.ArgumentParser(description="JPG -> 车衣 skin 资源")
    parser.add_argument("input", help="输入图片（jpg/png/...）")
    parser.add_argument("-o", "--out", default=None,
                        help="输出路径（默认 app/src/main/assets/skins/<名字>.jpg）")
    parser.add_argument("--mode", choices=MODES, default="cover",
                        help="图片适配方式：cover(默认)/contain/stretch/tile")
    parser.add_argument("--base-color", type=parse_color, default=(255, 255, 255),
                        help="contain/tile 模式的填充底色，如 #101018")
    parser.add_argument("--tile-size", type=int, default=0,
                        help="tile 模式单块边长（像素，0=原始尺寸）")
    parser.add_argument("--shading", type=float, default=0.85,
                        help="保留原车 AO/明暗的强度 0~1（默认 0.85，0=纯平贴）")
    parser.add_argument("--out-size", type=int, default=2048,
                        help="输出贴图边长（默认 2048）")
    parser.add_argument("--glb", default=GLB, help="车模 glb 路径（用于取 UV 图集）")
    args = parser.parse_args()

    if not os.path.isfile(args.input):
        parser.error("输入文件不存在: " + args.input)
    if not (0.0 <= args.shading <= 1.0):
        parser.error("--shading 需在 0~1")

    images = extract_images(args.glb)
    atlas = Image.open(__import__("io").BytesIO(images[ATLAS_IMAGE_INDEX])).convert("RGB")
    print("atlas", atlas.size)

    art = Image.open(args.input).convert("RGB")
    print("input", args.input, art.size)

    pattern = fit_pattern(art, atlas.width, args.mode, args.base_color, args.tile_size)
    mask = build_body_mask(atlas)

    out = args.out or default_output(args.input)
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    compose(atlas, pattern, mask, out, shading=args.shading, out_size=args.out_size)
    print("done ->", out)


if __name__ == "__main__":
    main()
