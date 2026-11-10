"""按 `fairy_dianthus.png` 的连接纹理生成仙灵石竹的竖柱模型与 blockstate。

贴图左上角是 3 列 × 2 行的连接变体，每列都是一整条侧面（8 像素宽）：

| 位置 | 像素范围 | 含义 |
|---|---|---|
| 第一行第一个 | x 0~8，y 0~16 | 这个面的左右没有分支 |
| 第一行第二个 | x 8~16，y 0~16 | 这个面的右边有分支 |
| 第一行第三个 | x 16~24，y 0~12 | 这个面的左边是主柱（十二格高，供转向件用） |
| 第二行第一个 | x 0~8，y 16~32 | 这个面的左边有分支 |
| 第二行第二个 | x 8~16，y 16~32 | 这个面的两边有分支 |
| 第二行第三个 | x 16~24，y 16~28 | 这个面的右边是主柱 |

「左右」按站在方块外面看这个面算：北面的左边是东、南面的左边是西，依此类推。
每个面取哪一条，取决于该面左右两侧有没有分支连接；转向件则看主柱在它哪一侧。

模型贴的是色相带图 `fairy_dianthus_hues.png`（由 `tools/generate_hue_sheets.py` 生成，纵向 25 条），
`particle` 仍指原图 `fairy_dianthus.png`，破坏粒子才不会被压成带图里的一条。
面上不再写 `tintindex`：色相偏移烘在带图里，顶点色是乘数，这里一格都不用。

用法：`python tools/generate_fairy_dianthus_models.py`
"""

import itertools
import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODEL_DIR = os.path.join(
    ROOT, "src", "main", "resources", "assets", "dawnduskterminal", "models", "block", "fairy_dianthus"
)
BLOCKSTATE = os.path.join(
    ROOT, "src", "main", "resources", "assets", "dawnduskterminal", "blockstates", "fairy_dianthus.json"
)
TEXTURE = "dawnduskterminal:block/fairy_dianthus_hues"
PARTICLE = "dawnduskterminal:block/fairy_dianthus"
CONNECTOR = "dawnduskterminal:block/fairy_dianthus_connector"

# 连接纹理的六条：名字 -> (x1, y1, x2, y2)，像素坐标
TILES = {
    "none": (0, 0, 8, 16),
    "branch_right": (8, 0, 16, 16),
    "main_left": (16, 0, 24, 12),
    "branch_left": (0, 16, 8, 32),
    "branch_both": (8, 16, 16, 32),
    "main_right": (16, 16, 24, 28),
}

# 站在外面看这个面时，它的左边 / 右边是哪个水平方向
SIDES = {
    "north": ("east", "west"),
    "east": ("south", "north"),
    "south": ("west", "east"),
    "west": ("north", "south"),
}

HORIZONTAL = ["north", "east", "south", "west"]
FACES = ["north", "east", "south", "west", "up", "down"]

# 竖柱的上端：整张图里唯一一块 8x8 的方形截面
CROSS_SECTION = (24, 0, 32, 8)

# 转向件的底面：示例里就是「无分支」那条的上半截，是刻意的底面视图，不是截面。
# 它「朝世界固定方向」的正解是整份模型跟着连接做 y 旋转，不是换贴图
UNDERSIDE = (0, 0, 8, 8)

# 竖直镜像（y -> 16 - y）下面怎么走：四个侧面留在原方向，上下互换
Y_MIRROR_FACE = {
    "north": "north",
    "east": "east",
    "south": "south",
    "west": "west",
    "up": "down",
    "down": "up",
}

# 拐角只写一份「主柱在南」的模型，其余三个方向靠 blockstate 的 y 旋转转过去：
# 这样截面图案与侧面贴图一起跟着连接方向走，不会像四份变体那样把截面钉死在世界北方。
# 角度与连接柱那份示例同一套（南 0 / 西 90 / 北 180 / 东 270）
CORNER_ROTATION = {"south": 0, "west": 90, "north": 180, "east": 270}

# 拐角模型的「标准朝向」：连接朝南
CANONICAL_STUBS = {"north": False, "east": False, "south": True, "west": False}


def uv(rect, height_px):
    """把像素矩形换成 0~16 的 uv，并按面高裁到前 height_px 行"""
    x1, y1, x2, y2 = rect
    y2 = min(y2, y1 + height_px)
    return [x1 / 2.0, y1 / 2.0, x2 / 2.0, y2 / 2.0]


def tile_for(face, stubs, part):
    """这个面用哪一条连接纹理"""
    left, right = SIDES[face]
    if part in ("turn", "drop"):
        # 拐角只有一个朝向：主柱在那一侧。向下拐角与转向件在这一点上完全一样，
        # 差的只是柱子占格子的上半截还是下半截（几何在 build_model 里换）
        stub_dirs = [direction for direction in HORIZONTAL if stubs[direction]]
        if len(stub_dirs) == 1:
            main = stub_dirs[0]
            if right == main:
                return "main_left"
            if left == main:
                return "main_right"
        return "none"
    has_left = stubs[left]
    has_right = stubs[right]
    if has_left and has_right:
        return "branch_both"
    if has_right:
        return "branch_right"
    if has_left:
        return "branch_left"
    return "none"


def corner_faces(stubs):
    """转向件的六个面（标准朝向：连接朝南）

    侧面按连接挑 12 像素高的那条；上端是那块 8x8 截面，下端是示例里的底面视图。
    """
    faces = {}
    for face_name in HORIZONTAL:
        faces[face_name] = {"uv": uv(TILES[tile_for(face_name, stubs, "turn")], 12), "texture": "#0"}
    faces["up"] = {"uv": uv(CROSS_SECTION, 8), "texture": "#0"}
    faces["down"] = {"uv": uv(UNDERSIDE, 8), "texture": "#0"}
    return faces


def mirror_faces(faces):
    """把一份拐角的面整份做竖直镜像

    y -> 16 - y 之下：四个侧面留在原方向、uv 上下对调；上下面**互换**再各自对调。
    只对调不管互换的话，上端会拿到本该在底下的那块贴图。
    """
    mirrored = {}
    for face, data in faces.items():
        u1, v1, u2, v2 = data["uv"]
        mirrored[Y_MIRROR_FACE[face]] = {"uv": [u1, v2, u2, v1], "texture": data["texture"]}
    return mirrored


def build_model(part, stubs):
    """一份柱子模型：几何固定，六个面按角色定

    贴的是色相带图，`particle` 单指原图；面上不带 `tintindex`，色相不经过顶点色。

    - `stem` / `branch` / `hang`：整根柱子，上下两端都用截面，侧面按连接挑
    - `turn`：转向件，柱子抬 4 像素、十二格高，上端截面、下端底面视图
    - `drop`：转向件的竖直镜像——几何换到格底（0~12），六个面按 {@link #mirror_faces} 推导
    """
    if part == "turn":
        element_from = [4, 4, 4]
        element_to = [12, 16, 12]
    elif part == "drop":
        element_from = [4, 0, 4]
        element_to = [12, 12, 12]
    else:
        element_from = [4, 0, 4]
        element_to = [12, 16, 12]

    if part == "drop":
        faces = mirror_faces(corner_faces(stubs))
    elif part == "turn":
        faces = corner_faces(stubs)
    else:
        faces = {}
        for face_name in HORIZONTAL:
            faces[face_name] = {"uv": uv(TILES[tile_for(face_name, stubs, part)], 16), "texture": "#0"}
        faces["up"] = {"uv": uv(CROSS_SECTION, 8), "texture": "#0"}
        faces["down"] = {"uv": uv(CROSS_SECTION, 8), "texture": "#0"}

    return {
        "parent": "minecraft:block/block",
        "render_type": "minecraft:cutout",
        "textures": {"0": TEXTURE, "particle": PARTICLE},
        "elements": [
            {
                "from": element_from,
                "to": element_to,
                "faces": {face: faces[face] for face in FACES},
            }
        ],
    }


def dump_model(model):
    """把模型写成 vanilla 那种可读的紧凑 JSON"""
    def uv_text(values):
        return "[" + ", ".join(f"{value:g}" for value in values) + "]"

    element = model["elements"][0]
    lines = [
        "{",
        '  "parent": "minecraft:block/block",',
        '  "render_type": "minecraft:cutout",',
        '  "textures": {',
        f'    "0": "{model["textures"]["0"]}",',
        f'    "particle": "{model["textures"]["particle"]}"',
        "  },",
        '  "elements": [',
        "    {",
        f'      "from": {element["from"]},',
        f'      "to": {element["to"]},',
        '      "faces": {',
    ]
    faces = []
    for face, data in element["faces"].items():
        faces.append(f'        "{face}": {{"uv": {uv_text(data["uv"])}, "texture": "#0"}}')
    lines.append(",\n".join(faces))
    lines += ["      }", "    }", "  ]", "}", ""]
    return "\n".join(lines)


def main():
    os.makedirs(MODEL_DIR, exist_ok=True)
    for stale in os.listdir(MODEL_DIR):
        if stale.endswith(".json"):
            os.remove(os.path.join(MODEL_DIR, stale))

    entries = []
    written = 0

    def write(part, stubs, name):
        nonlocal written
        model_path = os.path.join(MODEL_DIR, name + ".json")
        with open(model_path, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(dump_model(build_model(part, stubs)))
        written += 1
        return f"dawnduskterminal:block/fairy_dianthus/{name}"

    def emit(part, stubs):
        name = "fairy_dianthus_" + part + "_" + "".join("1" if stubs[d] else "0" for d in HORIZONTAL)
        model = write(part, stubs, name)
        entries.append(
            {
                "when": {key: value for key, value in [("part", part)]
                         + [(d, "true" if stubs[d] else "false") for d in HORIZONTAL]},
                "apply": {"model": model},
            }
        )

    def emit_corner(part):
        """拐角：只写一份「连接朝南」的模型，四个方向靠 blockstate 的 y 旋转"""
        name = "fairy_dianthus_" + part
        model = write(part, CANONICAL_STUBS, name)
        for direction in HORIZONTAL:
            entries.append(
                {
                    "when": {"part": part, **{d: "true" if d == direction else "false" for d in HORIZONTAL}},
                    "apply": {"model": model, "y": CORNER_ROTATION[direction]},
                }
            )

    emit("stem", {d: False for d in HORIZONTAL})
    for mask in itertools.product([False, True], repeat=4):
        stubs = dict(zip(HORIZONTAL, mask))
        if any(mask):
            emit("branch", stubs)
    emit_corner("turn")
    # 向下拐角是转向件的竖直镜像：几何换到格底，六个面按镜像规则推导
    emit_corner("drop")
    emit("hang", {d: False for d in HORIZONTAL})

    # 连接柱：示例里是绕 X 轴转 90° 的盒子，模型按旋转前写，方向交给 blockstate 的 x / y
    rotations = {
        "north": {"x": 90, "y": 180},
        "east": {"x": 90, "y": 270},
        "south": {"x": 90},
        "west": {"x": 90, "y": 90},
    }
    for direction, rotation in rotations.items():
        apply = {"model": CONNECTOR}
        apply.update(rotation)
        entries.append({"when": {direction: "true"}, "apply": apply})

    with open(BLOCKSTATE, "w", encoding="utf-8", newline="\n") as handle:
        json.dump({"multipart": entries}, handle, ensure_ascii=False, indent=2)
        handle.write("\n")

    print(f"models: {written}")
    print(f"multipart entries: {len(entries)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())


