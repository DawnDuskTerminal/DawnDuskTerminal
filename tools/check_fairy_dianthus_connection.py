"""检查仙灵石竹的连接纹理：六条贴图区是否真的存在，每个状态每个面取的是哪一条。

不重新推导规则，只做两件事：
1. 确认六条贴图区在 `fairy_dianthus.png` 里完全不透明（否则那一面会漏空）；
2. 把生成出来的每份模型的每个面换算回贴图区名字，打印成表，供人工核对连接语义。

用法：`python tools/check_fairy_dianthus_uv.py`
"""

import json
import os

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEXTURE = os.path.join(ROOT, "src", "main", "resources", "assets", "dawnduskterminal", "textures", "block", "fairy_dianthus.png")
MODEL_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "dawnduskterminal", "models", "block", "fairy_dianthus")

# 与生成脚本同一张表：名字 -> (x1, y1, x2, y2) 像素
TILES = {
    "none": (0, 0, 8, 16),
    "branch_right": (8, 0, 16, 16),
    "main_left": (16, 0, 24, 12),
    "branch_left": (0, 16, 8, 32),
    "branch_both": (8, 16, 16, 32),
    "main_right": (16, 16, 24, 28),
}

# 柱子两头的 8x8 截面（整张图里唯一一块方形截面）
TILE_EXTRA = {
    "cross_section": (24, 0, 32, 8),
    "down_view": (0, 0, 8, 8),
}


def tile_of(uv):
    """把 0~16 的 uv 反查成贴图区名字（允许被裁短）

    向下拐角整份模型是转向件的竖直镜像，uv 上下对调过，所以先把颠倒的 v 摆正再查；
    查到以后加 `~mirror` 后缀，这样表里一眼能看出哪几份是镜像版。
    """
    u1, v1, u2, v2 = uv
    mirrored = v1 > v2
    if mirrored:
        v1, v2 = v2, v1
    x1, y1, x2, y2 = [value * 2 for value in (u1, v1, u2, v2)]
    suffix = "~mirror" if mirrored else ""
    for name, (tx1, ty1, tx2, ty2) in TILE_EXTRA.items():
        if abs(x1 - tx1) < 1e-6 and abs(x2 - tx2) < 1e-6 and abs(y1 - ty1) < 1e-6 and abs(y2 - ty2) < 1e-6:
            return name + suffix
    for name, (tx1, ty1, tx2, ty2) in TILES.items():
        if abs(x1 - tx1) < 1e-6 and abs(x2 - tx2) < 1e-6 and abs(y1 - ty1) < 1e-6 and y2 <= ty2 + 1e-6:
            return name + suffix
    return f"?{[x1, y1, x2, y2]}"


def main():
    image = Image.open(TEXTURE).convert("RGBA")
    problems = 0

    print("== 贴图区是否整块不透明")
    for name, (x1, y1, x2, y2) in list(TILES.items()) + list(TILE_EXTRA.items()):
        transparent = 0
        for y in range(y1, y2):
            for x in range(x1, x2):
                if image.getpixel((x, y))[3] == 0:
                    transparent += 1
        if transparent:
            problems += 1
        print(f"  {name:14s} {x1},{y1}~{x2},{y2}  透明像素 {transparent}")

    print()
    print("== 每个状态每个面的贴图区")
    for name in sorted(os.listdir(MODEL_DIR)):
        if not name.endswith(".json"):
            continue
        model = json.load(open(os.path.join(MODEL_DIR, name), encoding="utf-8"))
        faces = model["elements"][0]["faces"]
        tiles = {face: tile_of(data["uv"]) for face, data in faces.items()}
        unknown = [face for face, tile in tiles.items() if tile.startswith("?")]
        if unknown:
            problems += 1
        print(f"  {name[:-5]:28s} " + "  ".join(f"{face}:{tiles[face]}" for face in ["north", "east", "south", "west", "up", "down"]))

    print()
    print("problems:", problems)
    return 1 if problems else 0


if __name__ == "__main__":
    raise SystemExit(main())
