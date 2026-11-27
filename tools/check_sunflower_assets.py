"""反查向日葵的生成物：贴图没被改、物品图取对了、模型与模板指得到、渲染器的区域常量与图集一致。

与 `build_sunflower_assets.py` 成对使用：生成脚本管「怎么产出」，这个脚本管「产出对不对」，
两者都跑通才算资源可用。改贴图或改区域表之后先跑生成脚本，再跑这个。

渲染器里的区域常量是按像素写死在 Java 里的，与图集版式**必须一一对应**。
两个方向最容易搞错也最难在游戏里一眼看出：柱身的上下、叶片的根与尖。
所以这里把 `SunflowerRenderer` 的常量与 `REGIONS` 表逐条比对，对不上直接报错。

用法：`python tools/check_sunflower_assets.py`
"""

import json
import os
import re
import sys

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "tools", "sunflower_src")
ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets", "dawnduskterminal")
DATA = os.path.join(ROOT, "src", "main", "resources", "data")

SHIPS = {
    "day": os.path.join(ASSETS, "textures", "block", "sunflower.png"),
    "night": os.path.join(ASSETS, "textures", "block", "sunflower_night.png"),
}
SOURCES = {
    "day": os.path.join(SRC, "example.png"),
    "night": os.path.join(SRC, "example2.png"),
}
ICON = os.path.join(ASSETS, "textures", "item", "sunflower.png")
MODEL = os.path.join(ASSETS, "models", "block", "sunflower.json")
ITEM_MODEL = os.path.join(ASSETS, "models", "item", "sunflower.json")
STATES = os.path.join(ASSETS, "blockstates", "sunflower.json")
LEAVES = os.path.join(ASSETS, "sunflower", "leaves.json")

# 原件版式（32×32）
SOURCE_SIZE = 32
SOURCE_REGIONS = {
    "face": (0, 0, 11, 11),
    "back": (0, 11, 11, 11),
    "stem": (11, 0, 2, 16),
    "stem_upper": (13, 7, 2, 8),
    "leaf": (13, 0, 5, 7),
}

# 产物版式：64×64，区域之间留 4 像素间隔，必须与 build_sunflower_assets.py 和 SunflowerRenderer 一致
TEXTURE_SIZE = 64
REGIONS = {
    "face": (0, 0, 11, 11),
    "back": (15, 0, 11, 11),
    "stem": (0, 15, 2, 16),
    "stem_upper": (6, 15, 2, 8),
    "leaf": (12, 15, 5, 7),
}
EXPECTED_OPAQUE = {"face": 73, "back": 69, "stem": 32, "stem_upper": 16, "leaf": 24}

ITEM_SIZE = 16

PROBLEMS = []


def problem(message):
    PROBLEMS.append(message)


def load_json(path):
    if not os.path.isfile(path):
        problem("missing %s" % os.path.relpath(path, ROOT))
        return None
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


def check_atlases():
    for key, shipped in SHIPS.items():
        if not os.path.isfile(shipped):
            problem("missing %s" % os.path.relpath(shipped, ROOT))
            continue
        image = Image.open(shipped).convert("RGBA")
        if image.size != (TEXTURE_SIZE, TEXTURE_SIZE):
            problem("%s must be %dx%d, got %s" % (shipped, TEXTURE_SIZE, TEXTURE_SIZE, image.size))
            continue
        source = Image.open(SOURCES[key]).convert("RGBA")
        if source.size != (SOURCE_SIZE, SOURCE_SIZE):
            problem("%s must be %dx%d, got %s" % (SOURCES[key], SOURCE_SIZE, SOURCE_SIZE, source.size))
            continue
        # 每个区域必须与原件里对应区域逐像素相同，只是搬了位置
        for name, (left, top, width, height) in REGIONS.items():
            src_left, src_top, src_width, src_height = SOURCE_REGIONS[name]
            if (width, height) != (src_width, src_height):
                problem("region %s changed size in the atlas table" % name)
                continue
            shipped_pixels = list(image.crop((left, top, left + width, top + height)).getdata())
            source_pixels = list(source.crop((src_left, src_top, src_left + src_width, src_top + src_height)).getdata())
            if shipped_pixels != source_pixels:
                problem("region %s in the %s atlas must keep the author's pixels unchanged" % (name, key))
            opaque = sum(1 for pixel in shipped_pixels if pixel[3] == 255)
            if opaque != EXPECTED_OPAQUE[name]:
                problem("region %s in the %s atlas is %d opaque, expected %d"
                        % (name, key, opaque, EXPECTED_OPAQUE[name]))
        # 区域之间必须有间隔，否则 mipmap 会把相邻区域混进去
        boxes = [(name,) + box for name, box in REGIONS.items()]
        for index, (name, left, top, width, height) in enumerate(boxes):
            for other_name, other_left, other_top, other_width, other_height in boxes[index + 1:]:
                if (left < other_left + other_width + 4 and other_left < left + width + 4
                        and top < other_top + other_height + 4 and other_top < top + height + 4):
                    problem("regions %s and %s are closer than 4 pixels" % (name, other_name))


def check_icon():
    if not os.path.isfile(ICON):
        problem("missing %s" % os.path.relpath(ICON, ROOT))
        return
    icon = Image.open(ICON).convert("RGBA")
    if icon.size != (ITEM_SIZE, ITEM_SIZE):
        problem("%s must be %dx%d, got %s" % (ICON, ITEM_SIZE, ITEM_SIZE, icon.size))
        return
    left, top, width, height = SOURCE_REGIONS["face"]
    face = Image.open(SOURCES["day"]).convert("RGBA").crop((left, top, left + width, top + height))
    expected = Image.new("RGBA", (ITEM_SIZE, ITEM_SIZE), (0, 0, 0, 0))
    offset = (ITEM_SIZE - width) // 2
    expected.paste(face, (offset, (ITEM_SIZE - height) // 2))
    if list(icon.getdata()) != list(expected.getdata()):
        problem("the item icon must be the 11x11 face pasted centred into 16x16 without scaling")


def check_models():
    model = load_json(MODEL)
    if model is not None:
        textures = model.get("textures", {})
        for key in ("day", "night", "particle"):
            if key not in textures:
                problem("%s must list the %s texture so it gets stitched into the block atlas" % (MODEL, key))
        if model.get("elements"):
            problem("%s must have no elements: the geometry comes from the renderer" % MODEL)

    # 方块模型里的 particle 决定破坏粒子：夜侧的三类方块必须用夜版贴图
    for name in ("sunflower", "sunflower_stem", "ghoflower", "moonflower", "moonflower_stem", "lunarghoflower"):
        path = os.path.join(ASSETS, "models", "block", name + ".json")
        block_model = load_json(path)
        if block_model is None:
            continue
        night = name in ("moonflower", "moonflower_stem", "lunarghoflower")
        wanted = "dawnduskterminal:block/sunflower_night" if night else "dawnduskterminal:block/sunflower"
        particle = block_model.get("textures", {}).get("particle")
        if particle != wanted:
            problem("%s must use %s for break particles, got %s" % (path, wanted, particle))

    item = load_json(ITEM_MODEL)
    if item is not None:
        if item.get("parent") != "minecraft:item/generated":
            problem("%s must parent minecraft:item/generated" % ITEM_MODEL)
        layer = item.get("textures", {}).get("layer0")
        if layer != "dawnduskterminal:item/sunflower":
            problem("%s must point layer0 at dawnduskterminal:item/sunflower, got %s" % (ITEM_MODEL, layer))

    states = load_json(STATES)
    if states is not None:
        variants = states.get("variants", {})
        expected = {
            "segment=%d,head=%s" % (segment, "true" if head else "false")
            for segment in range(3)
            for head in (False, True)
        }
        missing = expected - set(variants)
        if missing:
            problem("%s is missing variants: %s" % (STATES, sorted(missing)))
        for key, variant in variants.items():
            if variant.get("model") != "dawnduskterminal:block/sunflower":
                problem("variant %s must point at dawnduskterminal:block/sunflower" % key)


def check_leaves():
    data = load_json(LEAVES)
    if data is None:
        return
    templates = data.get("templates", [])
    if len(templates) < 8:
        problem("%s must carry at least 8 templates, got %d" % (LEAVES, len(templates)))
    for index, template in enumerate(templates):
        leaves = template.get("leaves", [])
        if template.get("count") != len(leaves):
            problem("template %d: count %s but %d leaves" % (index, template.get("count"), len(leaves)))
        if not 1 <= len(leaves) <= 4:
            problem("template %d must hold 1..4 leaves, got %d" % (index, len(leaves)))
        for leaf in leaves:
            if not 0.0 <= leaf.get("yaw", -1.0) <= 360.0:
                problem("template %d: yaw out of range: %s" % (index, leaf))
            if not 0.0 <= leaf.get("pitch", -1.0) <= 60.0:
                problem("template %d: pitch out of range: %s" % (index, leaf))
            if not 0.5 <= leaf.get("scale", 0.0) <= 1.5:
                problem("template %d: scale out of range: %s" % (index, leaf))
            if not 0.0 <= leaf.get("y", -1.0) <= 16.0:
                problem("template %d: y out of range: %s" % (index, leaf))


def check_renderer_constants():
    """渲染器里按像素写死的区域常量必须与 REGIONS 表一一对应"""
    path = os.path.join(
        ROOT, "src", "main", "java", "com", "dawnduskterminal", "client", "SunflowerRenderer.java")
    if not os.path.isfile(path):
        problem("missing %s" % os.path.relpath(path, ROOT))
        return
    with open(path, "r", encoding="utf-8") as handle:
        text = handle.read()

    def value(name):
        match = re.search(r"\b%s\s*=\s*([0-9]+(?:\.[0-9]+)?)F" % name, text)
        return None if match is None else float(match.group(1))

    face_left, face_top, face_width, face_height = REGIONS["face"]
    back_left, back_top, back_width, back_height = REGIONS["back"]
    stem_left, stem_top, stem_width, stem_height = REGIONS["stem"]
    leaf_left, leaf_top, leaf_width, leaf_height = REGIONS["leaf"]
    expected = {
        "TEXTURE_SIZE": float(TEXTURE_SIZE),
        "FACE_U": float(face_left),
        "FACE_V": float(face_top),
        "HEAD_TEXTURE_SIZE": float(face_width),
        "BACK_U": float(back_left),
        "BACK_V": float(back_top),
        "STEM_U": float(stem_left),
        "STEM_V_BOTTOM": float(stem_top + stem_height),
        "LEAF_U": float(leaf_left),
        # 叶片的根在上沿、尖在下沿；对调过一次，所以这两条单独盯着
        "LEAF_V_ROOT": float(leaf_top),
        "LEAF_V_TIP": float(leaf_top + leaf_height),
    }
    for name, wanted in expected.items():
        actual = value(name)
        if actual is None:
            problem("SunflowerRenderer is missing the constant %s" % name)
        elif actual != wanted:
            problem("SunflowerRenderer.%s is %s but the atlas table says %s" % (name, actual, wanted))


def check_projectile_texture():
    """射弹贴图是花正面切出来的两张：昼版与夜版各一，逐像素与原件一致"""
    for key, name in (("day", "ghoflower_projectile"), ("night", "ghoflower_projectile_night")):
        path = os.path.join(ASSETS, "textures", "entity", name + ".png")
        if not os.path.isfile(path):
            problem("missing %s (run the generator)" % os.path.relpath(path, ROOT))
            continue
        image = Image.open(path).convert("RGBA")
        left, top, width, height = SOURCE_REGIONS["face"]
        if image.size != (width, height):
            problem("%s must be %dx%d, got %s" % (path, width, height, image.size))
            continue
        source = Image.open(SOURCES[key]).convert("RGBA").crop((left, top, left + width, top + height))
        if list(image.getdata()) != list(source.getdata()):
            problem("%s must be the %s face region copied unchanged" % (path, key))


def check_projected_texture_path():
    """实体渲染器的贴图常量必须是 textures/...png，且昼、夜两张都要有"""
    path = os.path.join(
        ROOT, "src", "main", "java", "com", "dawnduskterminal", "client", "GhoflowerProjectileRenderer.java")
    if not os.path.isfile(path):
        problem("missing %s" % os.path.relpath(path, ROOT))
        return
    text = open(path, encoding="utf-8").read()
    if '"textures/entity/" + name + ".png"' not in text:
        problem("the projectile texture must be built as 'textures/entity/<name>.png'")
    for name in ("ghoflower_projectile", "ghoflower_projectile_night"):
        if ('projectileTexture("%s")' % name) not in text:
            problem("the projectile renderer is missing the %s texture constant" % name)


def check_items():
    """向日葵与月葵各有一份物品模型与贴图（鬼花没有物品），缺了掉出来就是紫黑格子"""
    for name in ("sunflower", "moonflower", "ghoflower", "lunarghoflower"):
        model = load_json(os.path.join(ASSETS, "models", "item", name + ".json"))
        if model is None:
            continue
        if model.get("parent") != "minecraft:item/generated":
            problem("models/item/%s.json must parent minecraft:item/generated" % name)
        layer = model.get("textures", {}).get("layer0")
        if layer != "dawnduskterminal:item/" + name:
            problem("models/item/%s.json must point layer0 at dawnduskterminal:item/%s, got %s"
                    % (name, name, layer))
        icon = os.path.join(ASSETS, "textures", "item", name + ".png")
        if not os.path.isfile(icon):
            problem("missing %s" % os.path.relpath(icon, ROOT))


def check_ghoflower():
    """鬼花是独立的方块与掉落表，另有三条伤害类型与三条洞穴音效"""
    model = load_json(os.path.join(ASSETS, "models", "block", "ghoflower.json"))
    if model is not None and model.get("elements"):
        problem("models/block/ghoflower.json must have no elements: the geometry comes from the renderer")
    for block_name in ("sunflower", "moonflower", "ghoflower"):
        states = load_json(os.path.join(ASSETS, "blockstates", block_name + ".json"))
        if states is None:
            continue
        expected_variants = {
            "segment=%d,head=%s" % (segment, "true" if head else "false")
            for segment in range(3)
            for head in (False, True)
        }
        missing_variants = expected_variants - set(states.get("variants", {}))
        if missing_variants:
            problem("blockstates/%s.json is missing variants: %s" % (block_name, sorted(missing_variants)))

    states = load_json(os.path.join(ASSETS, "blockstates", "ghoflower.json"))
    if states is not None:
        expected = {
            "segment=%d,head=%s" % (segment, "true" if head else "false")
            for segment in range(3)
            for head in (False, True)
        }
        missing = expected - set(states.get("variants", {}))
        if missing:
            problem("blockstates/ghoflower.json is missing variants: %s" % sorted(missing))

    # 四份战利品表保留为资源；掉落由方块代码发出，不查这些表，这里只确认文件还在
    for name in ("sunflower", "moonflower", "ghoflower", "lunarghoflower"):
        path = os.path.join(DATA, "dawnduskterminal", "loot_table", "blocks", name + ".json")
        load_json(path)

    for name in ("ghoflower_fertiliser", "ghoflower_beauty", "ghoflower_ghost"):
        path = os.path.join(DATA, "dawnduskterminal", "damage_type", name + ".json")
        data = load_json(path)
        if data is not None and data.get("message_id") != name:
            problem("%s must carry message_id %s, got %s" % (path, name, data.get("message_id")))

    sounds = load_json(os.path.join(ASSETS, "sounds.json"))
    if sounds is not None:
        for index in (1, 2, 10):
            key = "ghoflower_cave_%d" % index
            wanted = "minecraft:ambient/cave/cave%d" % index
            if key not in sounds:
                problem("sounds.json is missing %s" % key)
            elif wanted not in sounds[key].get("sounds", []):
                problem("sounds.json %s must point at %s" % (key, wanted))


def main():
    check_atlases()
    check_icon()
    check_models()
    check_leaves()
    check_renderer_constants()
    check_ghoflower()
    check_items()
    check_projected_texture_path()
    check_projectile_texture()
    if PROBLEMS:
        print("向日葵资源检查未通过：")
        for message in PROBLEMS:
            print("  - %s" % message)
        raise SystemExit(1)
    print("向日葵资源检查通过")


if __name__ == "__main__":
    main()
