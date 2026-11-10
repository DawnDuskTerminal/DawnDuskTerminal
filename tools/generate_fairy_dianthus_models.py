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
TEXTURE = "dawnduskterminal:block/fairy_dianthus"
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


def uv(rect, height_px):
    """把像素矩形换成 0~16 的 uv，并按面高裁到前 height_px 行"""
    x1, y1, x2, y2 = rect
    y2 = min(y2, y1 + height_px)
    return [x1 / 2.0, y1 / 2.0, x2 / 2.0, y2 / 2.0]


def tile_for(face, stubs, part):
    """这个面用哪一条连接纹理"""
    left, right = SIDES[face]
    if part == "turn":
        # 转向件只有一个朝向：主柱在那一侧。
        # 十二格高的那两条贴图是十六格那两条的镜像，用在这里左右要反过来
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


def build_model(part, stubs):
    """一份竖柱模型：几何固定，六个面按连接挑贴图"""
    if part == "turn":
        element_from = [4, 4, 4]
        height_px = 12
    else:
        element_from = [4, 0, 4]
        height_px = 16

    faces = {}
    for face in ["north", "east", "south", "west"]:
        faces[face] = {"uv": uv(TILES[tile_for(face, stubs, part)], height_px), "texture": "#0"}
    if part == "turn":
        # 示例里转向件的上下面与竖柱不同：up 取 24,0~32,8，down 取 0,0~8,8
        faces["up"] = {"uv": uv((24, 0, 32, 8), 8), "texture": "#0"}
        faces["down"] = {"uv": uv((0, 0, 8, 8), 8), "texture": "#0"}
    else:
        faces["up"] = {"uv": uv((24, 0, 32, 8), 8), "texture": "#0"}
        faces["down"] = {"uv": uv((24, 0, 32, 8), 8), "texture": "#0"}

    return {
        "parent": "minecraft:block/block",
        "render_type": "minecraft:cutout",
        "textures": {"0": TEXTURE, "particle": TEXTURE},
        "elements": [
            {
                "from": element_from,
                "to": [12, 16, 12],
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

    def emit(part, stubs):
        nonlocal written
        name = "fairy_dianthus_" + part + "_" + "".join("1" if stubs[d] else "0" for d in HORIZONTAL)
        model_path = os.path.join(MODEL_DIR, name + ".json")
        with open(model_path, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(dump_model(build_model(part, stubs)))
        written += 1
        entries.append(
            {
                "when": {key: value for key, value in [("part", part)]
                         + [(d, "true" if stubs[d] else "false") for d in HORIZONTAL]},
                "apply": {"model": f"dawnduskterminal:block/fairy_dianthus/{name}"},
            }
        )

    emit("stem", {d: False for d in HORIZONTAL})
    for mask in itertools.product([False, True], repeat=4):
        stubs = dict(zip(HORIZONTAL, mask))
        if any(mask):
            emit("branch", stubs)
    for direction in HORIZONTAL:
        stubs = {d: d == direction for d in HORIZONTAL}
        emit("turn", stubs)

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


