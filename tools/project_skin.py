#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把一张 JPG/PNG 通过 3D 投影烘焙成车模车衣（skin）资源。

与 jpg_to_skin.py 的"直接贴图集"不同，本脚本读取车模 glb 的顶点/UV，
把图片按指定投影方向（侧/前/顶/柱面）投影到车身表面，再烘焙回
模型原有的 UV 图集。因此车身各面板上的图案是**连贯**的，而不是碎片拼贴。

推荐用途：侧视图痛车/logo/渐变涂装。

用法示例：
    # 侧视投影（默认），等比铺满
    python3 tools/project_skin.py my_art.jpg

    # 只把图放到车身侧面中段（缩放+平移控制位置）
    python3 tools/project_skin.py anime.png --project side --zoom 0.5 --center 0.5 0.45

    # 前脸 / 顶视 / 柱面环绕
    python3 tools/project_skin.py art.jpg --project front
    python3 tools/project_skin.py art.jpg --project top
    python3 tools/project_skin.py art.jpg --project cylindrical

    # 周围区域填底色/保留原车漆明暗
    python3 tools/project_skin.py art.jpg --base-color "#101018" --shading 0.8

依赖：Pillow、numpy（pip install pillow numpy）。不参与 APK 打包。
"""

import argparse
import io
import json
import math
import os
import struct
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_GLB = os.path.join(ROOT, "tools/models/cartoon_sports_car.glb")
OUT_DIR = os.path.join(ROOT, "app/src/main/assets/skins")
ATLAS_IMAGE_INDEX = 0
PAINT_KEYWORD = "CARRERA_4096"
EXCLUDE_KEYWORDS = ("MATTE", "GLASS", "MIRROR", "HEADLIGHT", "LAMP")
LUMA_REF = 110.0

_COMPONENT = {5120: ("b", 1), 5121: ("B", 1), 5122: ("h", 2),
              5123: ("H", 2), 5125: ("I", 4), 5126: ("f", 4)}
_TYPE_N = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}


# ── glb 解析 ────────────────────────────────────────────────────────────────

def load_glb(path):
    data = open(path, "rb").read()
    off = 12
    gltf = bin_chunk = None
    while off < len(data):
        clen, ctype = struct.unpack("<II", data[off:off + 8])
        chunk = data[off + 8:off + 8 + clen]
        if ctype == 0x4E4F534A:
            gltf = json.loads(chunk.decode("utf-8"))
        elif ctype == 0x004E4942:
            bin_chunk = chunk
        off += 8 + clen
    return gltf, bin_chunk


def read_accessor(gltf, bin_chunk, index):
    acc = gltf["accessors"][index]
    n = _TYPE_N[acc["type"]]
    fmt, size = _COMPONENT[acc["componentType"]]
    count = acc["count"]
    bv = gltf["bufferViews"][acc["bufferView"]]
    start = bv.get("byteOffset", 0) + acc.get("byteOffset", 0)
    stride = bv.get("byteStride", 0) or (n * size)
    out = np.empty((count, n), dtype=np.float64)
    raw = np.frombuffer(bin_chunk, dtype=np.uint8)
    for i in range(count):
        base = start + i * stride
        out[i] = struct.unpack_from("<" + fmt * n, raw, base)
    if acc.get("normalized"):
        maxv = {"b": 127.0, "B": 255.0, "h": 32767.0, "H": 65535.0}.get(fmt)
        if maxv:
            out = out / maxv
    return out


def node_local_matrix(node):
    if "matrix" in node:
        return np.array(node["matrix"], dtype=np.float64).reshape(4, 4, order="F")
    m = np.eye(4)
    if "scale" in node:
        m = np.diag([*node["scale"], 1.0]) @ m
    if "rotation" in node:
        x, y, z, w = node["rotation"]
        r = np.array([
            [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
            [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
            [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)],
        ])
        rm = np.eye(4)
        rm[:3, :3] = r
        m = rm @ m
    if "translation" in node:
        tm = np.eye(4)
        tm[:3, 3] = node["translation"]
        m = tm @ m
    return m


def world_matrices(gltf):
    nodes = gltf["nodes"]
    world = {}
    visited = set()

    def walk(idx, parent):
        if idx in visited:
            return
        visited.add(idx)
        world[idx] = parent @ node_local_matrix(nodes[idx])
        for c in nodes[idx].get("children", []):
            walk(c, world[idx])

    for root in gltf["scenes"][gltf.get("scene", 0)]["nodes"]:
        walk(root, np.eye(4))
    return world


def material_is_paint(gltf, mat_index):
    if mat_index is None:
        return False
    name = (gltf["materials"][mat_index].get("name") or "").upper()
    return PAINT_KEYWORD in name and not any(k in name for k in EXCLUDE_KEYWORDS)


def gather_body_triangles(gltf, bin_chunk):
    """返回 (N,3,3) 世界坐标顶点、(N,3,2) UV。"""
    world = world_matrices(gltf)
    tris, uvs = [], []
    for node_idx, node in enumerate(gltf["nodes"]):
        if "mesh" not in node:
            continue
        wm = world.get(node_idx, np.eye(4))
        for prim in gltf["meshes"][node["mesh"]]["primitives"]:
            if not material_is_paint(gltf, prim.get("material")):
                continue
            if "indices" not in prim or "TEXCOORD_0" not in prim["attributes"]:
                continue
            pos = read_accessor(gltf, bin_chunk, prim["attributes"]["POSITION"])
            uv = read_accessor(gltf, bin_chunk, prim["attributes"]["TEXCOORD_0"])
            idx = read_accessor(gltf, bin_chunk, prim["indices"]).astype(int).ravel()
            ph = np.hstack([pos, np.ones((len(pos), 1))]) @ wm.T
            p = ph[:, :3]
            for i in range(0, len(idx), 3):
                a, b, c = idx[i], idx[i + 1], idx[i + 2]
                tris.append([p[a], p[b], p[c]])
                uvs.append([uv[a], uv[b], uv[c]])
    if not tris:
        return np.zeros((0, 3, 3)), np.zeros((0, 3, 2))
    return np.array(tris), np.array(uvs)


# ── 投影 ────────────────────────────────────────────────────────────────────

def project_vertices(p, mode, bbox, kwargs):
    """把世界坐标 (N,3) 投影为归一化平面坐标 (N,2)，范围约 [0,1]。"""
    xmin, xmax, ymin, ymax, zmin, zmax = bbox

    def norm(v, lo, hi):
        return (v - lo) / max(1e-6, hi - lo)

    if mode == "side":          # 沿 Z 投影：车长 X → u，车高 Y → v
        u = norm(p[:, 0], xmin, xmax)
        v = 1.0 - norm(p[:, 1], ymin, ymax)
    elif mode == "front":       # 沿 X 投影：车宽 Z → u，车高 Y → v
        u = norm(p[:, 2], zmin, zmax)
        v = 1.0 - norm(p[:, 1], ymin, ymax)
    elif mode == "top":         # 沿 Y 投影：车长 X → u，车宽 Z → v
        u = norm(p[:, 0], xmin, xmax)
        v = norm(p[:, 2], zmin, zmax)
    elif mode == "cylindrical":  # 绕 X 轴环绕：角度 → u，车长 → v
        ang = np.arctan2(p[:, 2], p[:, 1] - (ymin + ymax) * 0.5)
        u = (ang / (2 * math.pi)) + 0.5
        v = norm(p[:, 0], xmin, xmax)
    else:
        raise ValueError(mode)

    cx, cy = kwargs["center"]
    zoom = kwargs["zoom"]
    u = (u - cx) * zoom + 0.5
    v = (v - cy) * zoom + 0.5
    return np.stack([u, v], axis=1)


def make_source_mapper(src, fit, base_color):
    """返回把归一化 (N,2) 映射到源图像素坐标与有效掩码的函数。"""
    h, w = src.shape[:2]
    src_aspect = w / h

    def mapper(pq):
        a = pq[:, 0]
        b = pq[:, 1]
        valid = (a >= 0) & (a <= 1) & (b >= 0) & (b <= 1)
        if fit == "stretch" or abs(src_aspect - 1.0) < 1e-6:
            sx = a * w
            sy = b * h
        elif fit == "contain":
            if src_aspect >= 1:      # 宽图：左右满，上下留边
                draw_h = 1.0 / src_aspect
                top = (1 - draw_h) / 2
                sx = a * w
                sy = (b - top) / draw_h * h
            else:                    # 高图：上下满，左右留边
                draw_w = src_aspect
                left = (1 - draw_w) / 2
                sx = (a - left) / draw_w * w
                sy = b * h
        else:                        # cover：等比铺满并居中裁剪
            if src_aspect >= 1:      # 宽图：按高度铺满，裁剪左右
                sx = (w - h) / 2 + a * h
                sy = b * h
            else:                    # 高图：按宽度铺满，裁剪上下
                sx = a * w
                sy = (h - w) / 2 + b * w
        return sx, sy, valid

    return mapper


def sample_nearest(src, sx, sy):
    h, w = src.shape[:2]
    xi = np.clip(np.round(sx).astype(int), 0, w - 1)
    yi = np.clip(np.round(sy).astype(int), 0, h - 1)
    return src[yi, xi]


# ── 烘焙 ────────────────────────────────────────────────────────────────────

def build_mask(atlas_img, size):
    small = atlas_img.resize((size, size), Image.LANCZOS)
    r, g, b = small.split()
    from PIL import ImageChops
    gr = ImageChops.subtract(g, r).point(lambda v: 255 if v > 6 else 0)
    gb = ImageChops.subtract(g, b).point(lambda v: 255 if v > 2 else 0)
    m = ImageChops.multiply(gr, gb).filter(ImageFilter.GaussianBlur(2))
    return np.asarray(m, dtype=np.float32) / 255.0


def bake(atlas_img, src, tris, uvs, args):
    size = args.out_size
    out = np.asarray(atlas_img.resize((size, size), Image.LANCZOS),
                     dtype=np.float32).copy()
    mask = build_mask(atlas_img, size)[:, :, None]
    base = np.array(args.base_color, dtype=np.float32)

    bbox = (tris[:, :, 0].min(), tris[:, :, 0].max(),
            tris[:, :, 1].min(), tris[:, :, 1].max(),
            tris[:, :, 2].min(), tris[:, :, 2].max())
    kwargs = {"center": args.center, "zoom": args.zoom}
    pq = project_vertices(tris.reshape(-1, 3), args.project, bbox, kwargs)
    pq = pq.reshape(-1, 3, 2)

    mapper = make_source_mapper(src, args.fit, base)

    # 每个顶点的源图像素坐标
    flat = pq.reshape(-1, 2)
    sx_all, sy_all, valid_all = mapper(flat)
    sx_all = sx_all.reshape(-1, 3)
    sy_all = sy_all.reshape(-1, 3)
    valid_all = valid_all.reshape(-1, 3)

    painted = np.zeros((size, size), dtype=bool)
    total = len(tris)
    for t in range(total):
        uv = uvs[t] * size
        u0, v0 = uv[0]
        u1, v1 = uv[1]
        u2, v2 = uv[2]
        minx = max(int(math.floor(min(u0, u1, u2))), 0)
        maxx = min(int(math.ceil(max(u0, u1, u2))), size - 1)
        miny = max(int(math.floor(min(v0, v1, v2))), 0)
        maxy = min(int(math.ceil(max(v0, v1, v2))), size - 1)
        if maxx < minx or maxy < miny:
            continue

        xs = np.arange(minx, maxx + 1)
        ys = np.arange(miny, maxy + 1)
        gx, gy = np.meshgrid(xs + 0.5, ys + 0.5)

        denom = (v1 - v2) * (u0 - u2) + (u2 - u1) * (v0 - v2)
        if abs(denom) < 1e-9:
            continue
        w0 = ((v1 - v2) * (gx - u2) + (u2 - u1) * (gy - v2)) / denom
        w1 = ((v2 - v0) * (gx - u2) + (u0 - u2) * (gy - v2)) / denom
        w2 = 1.0 - w0 - w1
        inside = (w0 >= -1e-4) & (w1 >= -1e-4) & (w2 >= -1e-4)
        if not inside.any():
            continue

        sx = w0 * sx_all[t, 0] + w1 * sx_all[t, 1] + w2 * sx_all[t, 2]
        sy = w0 * sy_all[t, 0] + w1 * sy_all[t, 1] + w2 * sy_all[t, 2]
        ok = inside & (valid_all[t, 0] | valid_all[t, 1] | valid_all[t, 2])
        if not ok.any():
            continue

        color = sample_nearest(src, sx, sy)  # (h, w, 3)
        region = out[miny:maxy + 1, minx:maxx + 1]
        m = mask[miny:maxy + 1, minx:maxx + 1]
        sel = ok & (m[:, :, 0] > 0.05)
        region[sel] = color[sel]
        out[miny:maxy + 1, minx:maxx + 1] = region
        painted[miny:maxy + 1, minx:maxx + 1] |= sel

        if t % 4000 == 0 and t:
            print("  烘焙进度 %d/%d" % (t, total))

    # 未投影到的车身区用底色，其余保留原图
    out = np.where((mask > 0.05) & ~painted[:, :, None], base, out)

    # 保留原车 AO/明暗
    if args.shading > 0:
        luma = np.asarray(atlas_img.resize((size, size), Image.LANCZOS)
                          .convert("L"), dtype=np.float32)
        shade = np.clip(luma / LUMA_REF, 0.3, 1.6)[:, :, None]
        shaded = np.clip(out * shade, 0, 255)
        out = out * (1 - args.shading) + shaded * args.shading

    return Image.fromarray(np.clip(out, 0, 255).astype(np.uint8))


def parse_color(text):
    text = text.lstrip("#")
    if len(text) == 6:
        return tuple(int(text[i:i + 2], 16) for i in (0, 2, 4))
    raise argparse.ArgumentTypeError("颜色格式应为 #RRGGBB")


def main():
    ap = argparse.ArgumentParser(description="JPG -> 车衣（3D 投影烘焙）")
    ap.add_argument("input")
    ap.add_argument("-o", "--out", default=None)
    ap.add_argument("--project", choices=("side", "front", "top", "cylindrical"),
                    default="side")
    ap.add_argument("--fit", choices=("cover", "contain", "stretch"), default="cover")
    ap.add_argument("--center", nargs=2, type=float, metavar=("CX", "CY"),
                    default=(0.5, 0.5), help="投影平面上的贴图中心")
    ap.add_argument("--zoom", type=float, default=1.0, help="贴图缩放（>1 放大）")
    ap.add_argument("--base-color", type=parse_color, default=(255, 255, 255),
                    help="投影之外的车身底色，如 #101018")
    ap.add_argument("--shading", type=float, default=0.8, help="保留原 AO/明暗 0~1")
    ap.add_argument("--out-size", type=int, default=2048)
    ap.add_argument("--glb", default=DEFAULT_GLB)
    args = ap.parse_args()

    if not os.path.isfile(args.input):
        ap.error("输入文件不存在: " + args.input)

    gltf, bin_chunk = load_glb(args.glb)
    images = gltf["images"]
    bv = gltf["bufferViews"][images[ATLAS_IMAGE_INDEX]["bufferView"]]
    start = bv.get("byteOffset", 0)
    atlas = Image.open(io.BytesIO(
        bin_chunk[start:start + bv["byteLength"]])).convert("RGB")
    print("atlas", atlas.size)

    src = np.asarray(Image.open(args.input).convert("RGB"), dtype=np.float32)
    print("input", args.input, src.shape[1], "x", src.shape[0])

    tris, uvs = gather_body_triangles(gltf, bin_chunk)
    print("车身三角面:", len(tris))
    if len(tris) == 0:
        ap.error("未找到车身几何（检查 --glb 与材质名）")

    result = bake(atlas, src, tris, uvs, args)

    out = args.out or os.path.join(
        OUT_DIR, os.path.splitext(os.path.basename(args.input))[0] + ".jpg")
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    result.save(out, "JPEG", quality=90)
    print("done ->", out)


if __name__ == "__main__":
    main()
