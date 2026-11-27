"""从示例原件生成向日葵所需的全部资源。

原件放在 `tools/sunflower_src/`，逐字节不改：

| 文件 | 用途 |
|---|---|
| `example.png` | 昼侧图集，花正面为黄色 |
| `example2.png` | 夜侧图集，与昼侧版式完全一致，色相偏青 |
| `example.geo.json` | Bedrock 几何，只用来核对尺寸常量，不进产物 |

产物：

| 文件 | 说明 |
|---|---|
| `textures/block/sunflower.png`、`sunflower_night.png` | `example.png`、`example2.png` 逐字节复制 |
| `textures/item/sunflower.png`、`moonflower.png` | 花正面 11×11 原像素居中放进 16×16，不缩放 |
| `textures/entity/ghoflower_projectile.png`、`..._night.png` | 从两张图集切出的射弹贴图 |
| `models/block/` 下六个模型 | 无 element；两张贴图写进 `textures` 才会被 stitch 进方块图集；`particle` 按昼夜侧写 |
| `models/item/sunflower.json`、`moonflower.json` | `item/generated` 指物品图 |
| `blockstates/` 下六个文件 | 六个状态全部映射到同一个空模型 |
| `sunflower/leaves.json` | 十二组叶片模板，运行时按坐标抽一组再微扰 |

鬼花物品的图标与模型在 resources 下单独维护，不在本脚本的产物内。

尺寸常量必须与 Java 侧一致，改这里要同时改
`com.dawnduskterminal.world.SunflowerPlant` 与 `com.dawnduskterminal.client.SunflowerRenderer`。

用法：`python tools/build_sunflower_assets.py`
"""

import json
import os
import shutil

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "tools", "sunflower_src")
ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets", "dawnduskterminal")
BLOCK_TEXTURES = os.path.join(ASSETS, "textures", "block")
ITEM_TEXTURES = os.path.join(ASSETS, "textures", "item")
ENTITY_TEXTURES = os.path.join(ASSETS, "textures", "entity")

SOURCE_DAY = os.path.join(SRC, "example.png")
SOURCE_NIGHT = os.path.join(SRC, "example2.png")
SOURCE_GEO = os.path.join(SRC, "example.geo.json")

# 原件图集的版式（32×32，逐像素不动，只作为取料来源）
SOURCE_SIZE = 32
SOURCE_REGIONS = {
    "face": (0, 0, 11, 11),
    "back": (0, 11, 11, 11),
    "stem": (11, 0, 2, 16),
    "stem_upper": (13, 7, 2, 8),
    "leaf": (13, 0, 5, 7),
}

# 产物图集的版式：放大到 64×64，各区域之间留 4 像素间隔，供方块图集做 mipmap 时不互相渗色。
# Java 侧（SunflowerRenderer）的区域常量必须与这张表一致
TEXTURE_SIZE = 64
REGIONS = {
    "face": (0, 0, 11, 11),
    "back": (15, 0, 11, 11),
    "stem": (0, 15, 2, 16),
    "stem_upper": (6, 15, 2, 8),
    "leaf": (12, 15, 5, 7),
}

# 每个区域的不透明像素数，两张图集实测相同
EXPECTED_OPAQUE = {"face": 73, "back": 69, "stem": 32, "stem_upper": 16, "leaf": 24}

# 示例几何里的立方体尺寸，用来盯住「原件没换、常量没漂」：
# 柱身四片（两片两格、两片一格）、花盘一片、叶片五片
EXPECTED_CUBE_SIZES = [(2, 16, 0), (2, 16, 0), (2, 8, 0), (2, 8, 0), (11, 1, 11)] + [(5, 0, 7)] * 5

ITEM_SIZE = 16

LEAVES = {
    "templates": [
        {"count": 1, "leaves": [{"yaw": 20, "pitch": 26, "scale": 1.0, "y": 7}]},
        {"count": 1, "leaves": [{"yaw": 200, "pitch": 32, "scale": 0.85, "y": 10}]},
        {
            "count": 2,
            "leaves": [
                {"yaw": 10, "pitch": 22, "scale": 1.1, "y": 5},
                {"yaw": 190, "pitch": 30, "scale": 0.9, "y": 11},
            ],
        },
        {
            "count": 2,
            "leaves": [
                {"yaw": 95, "pitch": 28, "scale": 1.05, "y": 8},
                {"yaw": 275, "pitch": 20, "scale": 1.0, "y": 4},
            ],
        },
        {
            "count": 2,
            "leaves": [
                {"yaw": 300, "pitch": 34, "scale": 0.8, "y": 12},
                {"yaw": 120, "pitch": 24, "scale": 1.2, "y": 6},
            ],
        },
        {
            "count": 3,
            "leaves": [
                {"yaw": 0, "pitch": 20, "scale": 1.0, "y": 4},
                {"yaw": 120, "pitch": 30, "scale": 0.9, "y": 9},
                {"yaw": 240, "pitch": 26, "scale": 1.1, "y": 13},
            ],
        },
        {
            "count": 3,
            "leaves": [
                {"yaw": 45, "pitch": 36, "scale": 0.8, "y": 6},
                {"yaw": 165, "pitch": 22, "scale": 1.15, "y": 10},
                {"yaw": 285, "pitch": 28, "scale": 1.0, "y": 3},
            ],
        },
        {
            "count": 3,
            "leaves": [
                {"yaw": 30, "pitch": 24, "scale": 1.2, "y": 11},
                {"yaw": 150, "pitch": 32, "scale": 0.85, "y": 5},
                {"yaw": 270, "pitch": 20, "scale": 1.0, "y": 8},
            ],
        },
        {
            "count": 4,
            "leaves": [
                {"yaw": 15, "pitch": 22, "scale": 1.0, "y": 3},
                {"yaw": 105, "pitch": 30, "scale": 0.9, "y": 7},
                {"yaw": 195, "pitch": 26, "scale": 1.1, "y": 11},
                {"yaw": 285, "pitch": 34, "scale": 0.85, "y": 14},
            ],
        },
        {
            "count": 4,
            "leaves": [
                {"yaw": 60, "pitch": 28, "scale": 0.95, "y": 5},
                {"yaw": 150, "pitch": 20, "scale": 1.15, "y": 9},
                {"yaw": 240, "pitch": 36, "scale": 0.8, "y": 12},
                {"yaw": 330, "pitch": 24, "scale": 1.05, "y": 2},
            ],
        },
        {
            "count": 2,
            "leaves": [
                {"yaw": 80, "pitch": 18, "scale": 1.25, "y": 14},
                {"yaw": 250, "pitch": 38, "scale": 0.75, "y": 7},
            ],
        },
        {
            "count": 3,
            "leaves": [
                {"yaw": 210, "pitch": 26, "scale": 1.0, "y": 6},
                {"yaw": 330, "pitch": 32, "scale": 0.9, "y": 10},
                {"yaw": 75, "pitch": 22, "scale": 1.05, "y": 13},
            ],
        },
    ]
}


def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print("wrote %s" % os.path.relpath(path, ROOT))


def check_sources():
    """核对两张原件的版式与示例几何的尺寸"""
    for path in (SOURCE_DAY, SOURCE_NIGHT):
        image = Image.open(path).convert("RGBA")
        if image.size != (SOURCE_SIZE, SOURCE_SIZE):
            raise SystemExit("%s must be %dx%d, got %s" % (path, SOURCE_SIZE, SOURCE_SIZE, image.size))
    day = Image.open(SOURCE_DAY).convert("RGBA")
    night = Image.open(SOURCE_NIGHT).convert("RGBA")
    for name, (left, top, width, height) in SOURCE_REGIONS.items():
        for label, image in (("day", day), ("night", night)):
            crop = image.crop((left, top, left + width, top + height))
            opaque = sum(1 for pixel in crop.getdata() if pixel[3] == 255)
            if opaque != EXPECTED_OPAQUE[name]:
                raise SystemExit(
                    "region %s in the %s sheet is %d/%d opaque, expected %d"
                    % (name, label, opaque, width * height, EXPECTED_OPAQUE[name])
                )
    with open(SOURCE_GEO, "r", encoding="utf-8") as handle:
        geo = json.load(handle)
    cubes = []
    for geometry in geo.get("minecraft:geometry", []):
        for bone in geometry.get("bones", []):
            for cube in bone.get("cubes", []):
                cubes.append(tuple(cube["size"]))
    if sorted(cubes) != sorted(EXPECTED_CUBE_SIZES):
        raise SystemExit("example.geo.json cube sizes changed: %s, expected %s"
                         % (sorted(cubes), sorted(EXPECTED_CUBE_SIZES)))


def repack(source):
    """把原件按 SOURCE_REGIONS 取料、按 REGIONS 摆进留了间隔的 64×64 图集，像素本身不变"""
    sheet = Image.new("RGBA", (TEXTURE_SIZE, TEXTURE_SIZE), (0, 0, 0, 0))
    for name, (left, top, width, height) in SOURCE_REGIONS.items():
        piece = source.crop((left, top, left + width, top + height))
        target_left, target_top, target_width, target_height = REGIONS[name]
        if (target_width, target_height) != (width, height):
            raise SystemExit("region %s changed size: %s vs %s" % (name, (width, height), (target_width, target_height)))
        sheet.paste(piece, (target_left, target_top))
    return sheet


def build_assets():
    """两张原件重排成留间隔的图集，物品图取花正面居中放进 16×16"""
    os.makedirs(BLOCK_TEXTURES, exist_ok=True)
    day = repack(Image.open(SOURCE_DAY).convert("RGBA"))
    day_path = os.path.join(BLOCK_TEXTURES, "sunflower.png")
    day.save(day_path)
    print("wrote %s" % os.path.relpath(day_path, ROOT))
    night = repack(Image.open(SOURCE_NIGHT).convert("RGBA"))
    night_path = os.path.join(BLOCK_TEXTURES, "sunflower_night.png")
    night.save(night_path)
    print("wrote %s" % os.path.relpath(night_path, ROOT))

    left, top, width, height = SOURCE_REGIONS["face"]
    face = Image.open(SOURCE_DAY).convert("RGBA").crop((left, top, left + width, top + height))
    icon = Image.new("RGBA", (ITEM_SIZE, ITEM_SIZE), (0, 0, 0, 0))
    offset = (ITEM_SIZE - width) // 2
    icon.paste(face, (offset, (ITEM_SIZE - height) // 2))
    os.makedirs(ITEM_TEXTURES, exist_ok=True)
    icon_path = os.path.join(ITEM_TEXTURES, "sunflower.png")
    icon.save(icon_path)
    print("wrote %s" % os.path.relpath(icon_path, ROOT))
    # 月葵用夜版图集里的花正面
    left, top, width, height = SOURCE_REGIONS["face"]
    moon_face = Image.open(SOURCE_NIGHT).convert("RGBA").crop((left, top, left + width, top + height))
    moon_icon = Image.new("RGBA", (ITEM_SIZE, ITEM_SIZE), (0, 0, 0, 0))
    moon_icon.paste(moon_face, ((ITEM_SIZE - width) // 2, (ITEM_SIZE - height) // 2))
    moon_icon_path = os.path.join(ITEM_TEXTURES, "moonflower.png")
    moon_icon.save(moon_icon_path)
    print("wrote %s" % os.path.relpath(moon_icon_path, ROOT))

    # 射弹的贴图：花正面从图集里单独切出来一份。
    # 实体渲染走 RenderType#entityCutoutNoCull，它要的是 TextureManager 里加载过的独立贴图；
    # textures/block/ 下的文件只被 stitch 进方块图集，取不到，会变成黑加品红
    # 昼、夜各一张，按实体类型固定，飞过晨昏线不变色
    os.makedirs(ENTITY_TEXTURES, exist_ok=True)
    for source, name in ((SOURCE_DAY, "ghoflower_projectile"), (SOURCE_NIGHT, "ghoflower_projectile_night")):
        left, top, width, height = SOURCE_REGIONS["face"]
        piece = Image.open(source).convert("RGBA").crop((left, top, left + width, top + height))
        target = os.path.join(ENTITY_TEXTURES, name + ".png")
        piece.save(target)
        print("wrote %s" % os.path.relpath(target, ROOT))


def uv(name):
    """区域换成图集里的四个角，单位是像素"""
    left, top, width, height = REGIONS[name]
    return [left, top, left + width, top + height]


def build_models():
    # 四类花盘与两类茎各有一份空模型，贴图指向同一套图集；
    # particle 按昼夜侧取各自的图集，破坏粒子才不会串到另一侧
    for name in ("sunflower", "moonflower", "ghoflower", "lunarghoflower", "sunflower_stem", "moonflower_stem"):
        night = name in ("moonflower", "moonflower_stem", "lunarghoflower")
        particle = "dawnduskterminal:block/sunflower_night" if night else "dawnduskterminal:block/sunflower"
        write_json(
            os.path.join(ASSETS, "models", "block", name + ".json"),
            {
                "parent": "minecraft:block/block",
                "render_type": "minecraft:cutout",
                "textures": {
                    "particle": particle,
                    "day": "dawnduskterminal:block/sunflower",
                    "night": "dawnduskterminal:block/sunflower_night",
                },
                "elements": [],
            },
        )
    # 物品模型只有向日葵与月葵两份；鬼花物品的模型在 resources 下单独维护
    for name in ("sunflower", "moonflower"):
        write_json(
            os.path.join(ASSETS, "models", "item", name + ".json"),
            {
                "parent": "minecraft:item/generated",
                "textures": {"layer0": "dawnduskterminal:item/" + name},
            },
        )


def build_blockstates():
    for name in ("sunflower", "moonflower", "ghoflower", "lunarghoflower", "sunflower_stem", "moonflower_stem"):
        variants = {}
        for segment in range(3):
            for head in (False, True):
                variants["segment=%d,head=%s" % (segment, "true" if head else "false")] = {
                    "model": "dawnduskterminal:block/" + name
                }
        write_json(
            os.path.join(ASSETS, "blockstates", name + ".json"),
            {"variants": variants},
        )


def build_leaves():
    write_json(os.path.join(ASSETS, "sunflower", "leaves.json"), LEAVES)


def main():
    check_sources()
    build_assets()
    build_models()
    build_blockstates()
    build_leaves()
    print("sunflower assets are up to date")


if __name__ == "__main__":
    main()
