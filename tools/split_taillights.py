#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
模型侧改造脚本（不依赖 Blender）：把尾灯/转向灯从原网格里拆成独立材质，便于运行时独立控制。

后灯组布局（用户确认）：
    |转向灯|倒车灯|-----尾灯(中央红条)-----|倒车灯|转向灯|
  - 中央红色尾灯：车身网格 Object_5 中 x≈-1.81、y≈0.52~0.66、z∈[-0.51,0.51]、贴图为红色的三角面；
  - 琥珀色转向灯：Object_47（CARRERA_4096_lamps）中
      后转向灯 = x<-1.0 且 |z|>0.75（外侧）；
      前转向灯 = x>1.0（车头琥珀色灯带，整条）；
  - 倒车灯（内侧）：保持 CARRERA_4096_lamps，本脚本不动。

不改动源模型，输出新模型：
    app/src/main/assets/models/cartoon_sports_car_taillights.glb

依赖：Pillow（用于按贴图颜色识别红色条带）。不参与 APK 打包。
"""

import copy
import io
import json
import os
import struct
import sys

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "tools/models/cartoon_sports_car.glb")
DST = os.path.join(ROOT, "app/src/main/assets/models/cartoon_sports_car_taillights.glb")

BODY_MATERIAL = "CARRERA_4096"
LAMPS_MATERIAL = "CARRERA_4096_lamps"
TAIL_MATERIAL = "CARRERA_4096_TAILLIGHTS"
TURN_MATERIAL = "CARRERA_4096_TURNSIGNALS"

# 中央尾灯条带筛选（世界坐标 + 贴图红色度）
TAIL_MAX_X = -1.2
TAIL_MAX_ABS_Z = 0.56
TAIL_MIN_Y = 0.45
TAIL_MAX_Y = 0.75
TAIL_MIN_REDNESS = 20.0
TAIL_EMISSIVE_RGB = [1.0, 0.05, 0.03]

# 转向灯筛选（世界坐标）
TURN_MAX_X = -1.0       # 后转向灯：x<-1.0 且 |z|>0.75（外侧琥珀段）
TURN_MIN_ABS_Z = 0.75
TURN_FRONT_MIN_X = 1.0  # 前转向灯：x>1.0 的琥珀色灯带（整条）
TURN_EMISSIVE_RGB = [1.0, 0.45, 0.0]

_COMPONENT = {5120: "b", 5121: "B", 5122: "h", 5123: "H", 5125: "I", 5126: "f"}
_TYPE_N = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}


# ── glb 读写 ────────────────────────────────────────────────────────────────

def read_glb(path):
    data = open(path, "rb").read()
    magic, version, length = struct.unpack_from("<III", data, 0)
    assert magic == 0x46546C67, "not a glb"
    off = 12
    gltf = bin_chunk = None
    while off < length:
        clen, ctype = struct.unpack_from("<II", data, off)
        chunk = data[off + 8:off + 8 + clen]
        if ctype == 0x4E4F534A:
            gltf = json.loads(chunk.decode("utf-8"))
        elif ctype == 0x004E4942:
            bin_chunk = bytearray(chunk)
        off += 8 + clen
    return gltf, bin_chunk


def write_glb(path, gltf, bin_chunk):
    json_bytes = json.dumps(gltf, separators=(",", ":")).encode("utf-8")
    json_bytes += b" " * ((4 - len(json_bytes) % 4) % 4)
    bin_out = bytes(bin_chunk)
    bin_out += b"\x00" * ((4 - len(bin_out) % 4) % 4)
    total = 12 + 8 + len(json_bytes) + (8 + len(bin_out) if bin_out else 0)
    with open(path, "wb") as f:
        f.write(struct.pack("<III", 0x46546C67, 2, total))
        f.write(struct.pack("<II", len(json_bytes), 0x4E4F534A))
        f.write(json_bytes)
        if bin_out:
            f.write(struct.pack("<II", len(bin_out), 0x004E4942))
            f.write(bin_out)


# ── 数学（行主序 4x4）───────────────────────────────────────────────────────

def mat_identity():
    return [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]


def mat_mul(a, b):
    c = [0.0] * 16
    for r in range(4):
        for col in range(4):
            c[r * 4 + col] = sum(a[r * 4 + k] * b[k * 4 + col] for k in range(4))
    return c


def local_matrix(node):
    if "matrix" in node:
        m = node["matrix"]  # column-major
        return [m[0], m[4], m[8], m[12],
                m[1], m[5], m[9], m[13],
                m[2], m[6], m[10], m[14],
                m[3], m[7], m[11], m[15]]
    trs = mat_identity()
    if "translation" in node:
        trs[3], trs[7], trs[11] = node["translation"]
    rot = mat_identity()
    if "rotation" in node:
        x, y, z, w = node["rotation"]
        rot = [
            1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w), 0,
            2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w), 0,
            2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y), 0,
            0, 0, 0, 1,
        ]
    scale = mat_identity()
    if "scale" in node:
        scale[0], scale[5], scale[10] = node["scale"]
    return mat_mul(mat_mul(trs, rot), scale)


def world_matrices(gltf):
    world = {}
    nodes = gltf["nodes"]

    def walk(idx, parent):
        if idx in world:
            return
        world[idx] = mat_mul(parent, local_matrix(nodes[idx]))
        for c in nodes[idx].get("children", []):
            walk(c, world[idx])

    for root in gltf["scenes"][gltf.get("scene", 0)]["nodes"]:
        walk(root, mat_identity())
    return world


def transform_point(m, p):
    x, y, z = p
    return (m[0] * x + m[1] * y + m[2] * z + m[3],
            m[4] * x + m[5] * y + m[6] * z + m[7],
            m[8] * x + m[9] * y + m[10] * z + m[11])


# ── accessor / 贴图 ─────────────────────────────────────────────────────────

def read_accessor(gltf, bin_chunk, index):
    acc = gltf["accessors"][index]
    n = _TYPE_N[acc["type"]]
    fmt = _COMPONENT[acc["componentType"]]
    size = struct.calcsize(fmt)
    bv = gltf["bufferViews"][acc["bufferView"]]
    start = bv.get("byteOffset", 0) + acc.get("byteOffset", 0)
    stride = bv.get("byteStride", 0) or (n * size)
    out = []
    for i in range(acc["count"]):
        vals = struct.unpack_from("<" + fmt * n, bin_chunk, start + i * stride)
        out.append(vals if n > 1 else vals[0])
    return out


def append_index_accessor(gltf, bin_chunk, indices):
    while len(bin_chunk) % 4 != 0:
        bin_chunk.append(0)
    byte_offset = len(bin_chunk)
    for v in indices:
        bin_chunk += struct.pack("<I", int(v))

    bv_index = len(gltf["bufferViews"])
    gltf["bufferViews"].append({
        "buffer": 0,
        "byteOffset": byte_offset,
        "byteLength": len(indices) * 4,
        "target": 34963,
    })
    acc_index = len(gltf["accessors"])
    gltf["accessors"].append({
        "bufferView": bv_index,
        "componentType": 5125,
        "count": len(indices),
        "type": "SCALAR",
        "min": [int(min(indices))],
        "max": [int(max(indices))],
    })
    return acc_index


def material_index(gltf, name):
    return next(i for i, m in enumerate(gltf["materials"]) if m.get("name") == name)


def add_emissive_material(gltf, base_index, name, rgb):
    mat = copy.deepcopy(gltf["materials"][base_index])
    mat["name"] = name
    mat["emissiveFactor"] = list(rgb)
    mat.setdefault("extensions", {}).setdefault(
        "KHR_materials_emissive_strength", {})["emissiveStrength"] = 0.0
    index = len(gltf["materials"])
    gltf["materials"].append(mat)
    return index


# ── 拆分 ────────────────────────────────────────────────────────────────────

def split_material(gltf, bin_chunk, world, image_cache, src_name, dst_mat_index, classify):
    """
    遍历使用 src_name 材质的 primitive，按 classify(cx,cy,cz,redness) 把三角面拆到 dst_mat_index。
    只追加索引 accessor（不动顶点），原 primitive 保留其余三角面。
    """
    src_mat = material_index(gltf, src_name)
    total = 0
    for ni, node in enumerate(gltf["nodes"]):
        if "mesh" not in node:
            continue
        mesh = gltf["meshes"][node["mesh"]]
        wm = world.get(ni, mat_identity())
        for prim in list(mesh["primitives"]):
            if prim.get("material") != src_mat or "TEXCOORD_0" not in prim["attributes"]:
                continue

            image = image_cache(gltf, bin_chunk, prim["material"])
            pixels = image.load() if image else None
            width, height = image.size if image else (0, 0)

            positions = read_accessor(gltf, bin_chunk, prim["attributes"]["POSITION"])
            uvs = read_accessor(gltf, bin_chunk, prim["attributes"]["TEXCOORD_0"])
            indices = read_accessor(gltf, bin_chunk, prim["indices"])
            world_pos = [transform_point(wm, p) for p in positions]

            keep, split = [], []
            for t in range(0, len(indices), 3):
                tri = indices[t:t + 3]
                cx = sum(world_pos[i][0] for i in tri) / 3.0
                cy = sum(world_pos[i][1] for i in tri) / 3.0
                cz = sum(world_pos[i][2] for i in tri) / 3.0
                redness = None
                if pixels is not None:
                    redness = 0.0
                    for i in tri:
                        u = uvs[i][0] % 1.0
                        v = uvs[i][1] % 1.0
                        r, g, b = pixels[int(u * (width - 1)), int(v * (height - 1))]
                        redness += r - (g + b) / 2.0
                    redness /= 3.0
                (split if classify(cx, cy, cz, redness) else keep).extend(tri)

            if not split or not keep:
                continue

            prim["indices"] = append_index_accessor(gltf, bin_chunk, keep)
            split_prim = copy.deepcopy(prim)
            split_prim["indices"] = append_index_accessor(gltf, bin_chunk, split)
            split_prim["material"] = dst_mat_index
            mesh["primitives"].append(split_prim)
            total += len(split) // 3
            print("  node %d %s / mesh %s：拆出 %d 个三角面 -> %s"
                  % (ni, node.get("name"), mesh.get("name"),
                     len(split) // 3, gltf["materials"][dst_mat_index]["name"]))
    return total


_IMAGE_CACHE = {}


def get_base_color_image(gltf, bin_chunk, material_index_):
    pbr = gltf["materials"][material_index_].get("pbrMetallicRoughness", {})
    if "baseColorTexture" not in pbr:
        return None
    source = gltf["textures"][pbr["baseColorTexture"]["index"]]["source"]
    if source not in _IMAGE_CACHE:
        bv = gltf["bufferViews"][gltf["images"][source]["bufferView"]]
        start = bv.get("byteOffset", 0)
        _IMAGE_CACHE[source] = Image.open(
            io.BytesIO(bytes(bin_chunk[start:start + bv["byteLength"]]))
        ).convert("RGB")
    return _IMAGE_CACHE[source]


def is_tail(cx, cy, cz, redness):
    return (cx < TAIL_MAX_X and abs(cz) < TAIL_MAX_ABS_Z
            and TAIL_MIN_Y < cy < TAIL_MAX_Y
            and redness is not None and redness > TAIL_MIN_REDNESS)


def is_turn(cx, cy, cz, redness):
    rear = cx < TURN_MAX_X and abs(cz) > TURN_MIN_ABS_Z
    front = cx > TURN_FRONT_MIN_X
    return rear or front


def main():
    gltf, bin_chunk = read_glb(SRC)
    world = world_matrices(gltf)

    tail_mat = add_emissive_material(
        gltf, material_index(gltf, BODY_MATERIAL), TAIL_MATERIAL, TAIL_EMISSIVE_RGB)
    turn_mat = add_emissive_material(
        gltf, material_index(gltf, LAMPS_MATERIAL), TURN_MATERIAL, TURN_EMISSIVE_RGB)

    print("拆分中央尾灯：")
    tail_count = split_material(gltf, bin_chunk, world, get_base_color_image,
                                BODY_MATERIAL, tail_mat, is_tail)
    print("拆分外侧转向灯：")
    turn_count = split_material(gltf, bin_chunk, world, get_base_color_image,
                                LAMPS_MATERIAL, turn_mat, is_turn)

    if tail_count == 0 or turn_count == 0:
        sys.exit("拆分结果异常：尾灯 %d / 转向灯 %d" % (tail_count, turn_count))

    while len(bin_chunk) % 4 != 0:
        bin_chunk.append(0)
    gltf["buffers"][0]["byteLength"] = len(bin_chunk)
    write_glb(DST, gltf, bin_chunk)

    print("已写出:", DST, "%.1f MB" % (os.path.getsize(DST) / 1e6))
    print("合计：尾灯 %d 面、转向灯 %d 面" % (tail_count, turn_count))

    # 自检
    check, _ = read_glb(DST)
    names = [m.get("name") for m in check["materials"]]
    print("材质:", names)
    for ni, node in enumerate(check["nodes"]):
        if "mesh" not in node:
            continue
        for prim in check["meshes"][node["mesh"]]["primitives"]:
            n = check["materials"][prim["material"]].get("name")
            if n in (TAIL_MATERIAL, TURN_MATERIAL):
                print("自检: node %d %s %s 索引数=%d"
                      % (ni, node.get("name"), n,
                         check["accessors"][prim["indices"]]["count"]))


if __name__ == "__main__":
    main()
