#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
开发用脚本：从车模 glb 中抽取车身 base-color UV 图集，按"青色钣金区"生成
示例车衣贴图（程序化图案，占位级），输出到 app/src/main/assets/skins/。

用法：
    python3 tools/make_skin.py

依赖：Pillow（pip install pillow）。不参与 APK 打包。
"""

import json
import os
import random
import struct

from PIL import Image, ImageChops, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GLB = os.path.join(ROOT, "tools/models/cartoon_sports_car.glb")
OUT_DIR = os.path.join(ROOT, "app/src/main/assets/skins")
OUT_SIZE = 2048
ATLAS_IMAGE_INDEX = 0  # CARRERA_4096 外观 base color 图集

# 车身青色钣金区阈值（图集主色约 RGB(88,122,108)）
G_R_MIN = 6
G_B_MIN = 2
LUMA_REF = 110.0  # 车身基准亮度，用于保留原 AO/明暗


def extract_images(glb_path):
    data = open(glb_path, "rb").read()
    off = 12
    json_chunk = None
    bin_chunk = None
    while off < len(data):
        clen, ctype = struct.unpack("<II", data[off:off + 8])
        chunk = data[off + 8:off + 8 + clen]
        if ctype == 0x4E4F534A:
            json_chunk = chunk
        elif ctype == 0x004E4942:
            bin_chunk = chunk
        off += 8 + clen
    gltf = json.loads(json_chunk.decode("utf-8"))
    images = []
    for img in gltf["images"]:
        bv = gltf["bufferViews"][img["bufferView"]]
        start = bv.get("byteOffset", 0)
        length = bv["byteLength"]
        images.append(bin_chunk[start:start + length])
    return images


def build_body_mask(base):
    """青色钣金区软遮罩：G 明显高于 R/B 的像素。"""
    r, g, b = base.split()
    gr = ImageChops.subtract(g, r).point(lambda v: 255 if v > G_R_MIN else 0)
    gb = ImageChops.subtract(g, b).point(lambda v: 255 if v > G_B_MIN else 0)
    mask = ImageChops.multiply(gr, gb).filter(ImageFilter.GaussianBlur(3))
    return mask


def shading_layer(base):
    """用原图亮度做灰度层，合成时保留原 AO/描边。"""
    luma = base.convert("L")
    return luma.point(lambda v: min(255, int(v * 255.0 / LUMA_REF)))


def draw_sakura(size):
    """樱粉渐变 + 花瓣 + 洋红斜切，少女向风格。"""
    rnd = random.Random(2026)
    w, h = size

    # 斜向渐变底：左上樱粉 -> 右下珍珠白
    grad = Image.new("RGB", size)
    px = grad.load()
    for y in range(0, h, 4):
        for x in range(0, w, 4):
            t = (x + y) / (w + h)
            r = int(255 - 10 * t)
            g = int(205 + 45 * t)
            b = int(220 + 30 * t)
            c = (r, g, b)
            for yy in range(y, min(y + 4, h)):
                for xx in range(x, min(x + 4, w)):
                    px[xx, yy] = c

    overlay = Image.new("RGBA", size, (0, 0, 0, 0))
    d = ImageDraw.Draw(overlay)

    # 洋红大面积斜切色块
    d.polygon([(0, int(h * 0.55)), (w, int(h * 0.15)),
               (w, int(h * 0.30)), (0, int(h * 0.70))],
              fill=(236, 64, 122, 150))
    d.polygon([(0, int(h * 0.80)), (w, int(h * 0.45)),
               (w, int(h * 0.52)), (0, int(h * 0.87))],
              fill=(255, 138, 190, 120))

    # 随机花瓣（旋转的椭圆）
    for _ in range(60):
        rad = rnd.randint(30, 90)
        cx = rnd.randint(rad, w - rad)
        cy = rnd.randint(rad, h - rad)
        petal = Image.new("RGBA", (rad * 2, rad * 2), (0, 0, 0, 0))
        pd = ImageDraw.Draw(petal)
        pd.ellipse([rad * 0.5, rad * 0.2, rad * 1.5, rad * 1.8],
                   fill=(255, 255, 255, rnd.randint(70, 150)))
        petal = petal.rotate(rnd.randint(0, 360), resample=Image.BICUBIC)
        overlay.alpha_composite(petal, (cx - rad, cy - rad))

    # 星点
    for _ in range(120):
        x = rnd.randint(0, w)
        y = rnd.randint(0, h)
        s = rnd.randint(2, 6)
        d.ellipse([x, y, x + s, y + s], fill=(255, 255, 255, rnd.randint(120, 230)))

    out = grad.convert("RGBA")
    out.alpha_composite(overlay)
    return out.convert("RGB")


def draw_cyber(size):
    """暗底 + 霓虹青/品红斜条 + 电路网格，赛博风格。"""
    rnd = random.Random(8866)
    w, h = size
    base = Image.new("RGB", size, (10, 10, 26))

    overlay = Image.new("RGBA", size, (0, 0, 0, 0))
    d = ImageDraw.Draw(overlay)

    # 霓虹斜条
    stripe_colors = [(0, 229, 255, 190), (255, 0, 200, 170),
                     (124, 77, 255, 150), (0, 255, 170, 140)]
    for i in range(14):
        c = stripe_colors[i % len(stripe_colors)]
        x0 = int(-w * 0.3 + i * w / 9.0)
        bw = rnd.randint(20, 70)
        d.polygon([(x0, 0), (x0 + bw, 0),
                   (x0 + bw - int(h * 0.5), h), (x0 - int(h * 0.5), h)],
                  fill=c)

    # 电路网格
    for y in range(0, h, 96):
        d.line([(0, y), (w, y)], fill=(40, 120, 160, 70), width=2)
    for x in range(0, w, 96):
        d.line([(x, 0), (x, h)], fill=(40, 120, 160, 70), width=2)

    # 发光点
    for _ in range(90):
        x = rnd.randint(0, w)
        y = rnd.randint(0, h)
        r = rnd.randint(3, 9)
        col = stripe_colors[rnd.randint(0, len(stripe_colors) - 1)]
        d.ellipse([x - r, y - r, x + r, y + r], fill=col)

    out = base.convert("RGBA")
    out.alpha_composite(overlay)
    return out.convert("RGB")


def compose(base, pattern, mask, out_path, shading=1.0, out_size=None):
    """把 pattern 按 mask 合成到 base 的车身区；shading 控制保留原 AO/明暗的强度(0~1)。"""
    scale = Image.merge("RGB", (shading_layer(base),) * 3)
    modulated = ImageChops.multiply(pattern, scale)
    if shading < 1.0:
        modulated = Image.blend(pattern, modulated, shading)
    result = Image.composite(modulated, base, mask)
    result = result.resize((out_size or OUT_SIZE, out_size or OUT_SIZE), Image.LANCZOS)
    result.save(out_path, "JPEG", quality=90)
    print("written", out_path, result.size)


def main():
    images = extract_images(GLB)
    atlas = Image.open(__import__("io").BytesIO(images[ATLAS_IMAGE_INDEX])).convert("RGB")
    print("atlas", atlas.size)
    mask = build_body_mask(atlas)
    os.makedirs(OUT_DIR, exist_ok=True)
    compose(atlas, draw_sakura(atlas.size), mask,
            os.path.join(OUT_DIR, "skin_sakura.jpg"))
    compose(atlas, draw_cyber(atlas.size), mask,
            os.path.join(OUT_DIR, "skin_cyber.jpg"))


if __name__ == "__main__":
    main()
