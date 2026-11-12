#!/usr/bin/env python3
"""生成主世界矿石两块石的方块资源与战利品表

晨昏线把维度里的主世界矿石换成了自己的方块：矿石按宿主石分两档，`canopy_stone_*_ore`
对应上层的苍穹石，`charnockite_*_ore` 对应深层的紫苏岩。
两档的贴图各自一份，模型与方块状态完全相同，只有贴图名不同。

这个脚本只生成那些「一眼就是复制品」的文件：

    assets/dawnduskterminal/blockstates/<id>.json
    assets/dawnduskterminal/models/block/<id>.json
    assets/dawnduskterminal/models/item/<id>.json
    data/dawnduskterminal/loot_table/blocks/<id>.json

贴图与语言文件不在这里：贴图是原画，语言条目要手工读着改。
战利品表逐条对着原版的 `minecraft:blocks/<矿石>` 抄，只把「精准采集掉本体」那一条
换成模组方块，所以挖出来的东西与原版矿石一致（原料、数量区间、时运与爆炸衰减）。

用法：

    python3 tools/generate_overworld_ore_assets.py
"""

import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "src", "main", "resources")
ASSETS = os.path.join(SRC, "assets", "dawnduskterminal")
LOOT_DIR = os.path.join(SRC, "data", "dawnduskterminal", "loot_table", "blocks")

# 两档宿主石。表里的值就是方块名里的前缀，同时决定贴图名（贴图与本方块同名）
TIERS = ["canopy_stone", "charnockite"]

# 六种主世界矿石。drops 是原版矿石的掉落表：
#   item    掉什么
#   count   掉几个（None 表示固定一个，不需要 set_count）
#   fortune apply_bonus 的公式
ORE_TYPES = {
    "copper": {"item": "minecraft:raw_copper", "count": (2, 5), "fortune": "minecraft:ore_drops"},
    "iron": {"item": "minecraft:raw_iron", "count": None, "fortune": "minecraft:ore_drops"},
    "gold": {"item": "minecraft:raw_gold", "count": None, "fortune": "minecraft:ore_drops"},
    "redstone": {
        "item": "minecraft:redstone",
        "count": (4, 5),
        "fortune": "minecraft:uniform_bonus_count",
        "fortune_parameters": {"bonusMultiplier": 1},
    },
    "lapis": {"item": "minecraft:lapis_lazuli", "count": (4, 9), "fortune": "minecraft:ore_drops"},
    "diamond": {"item": "minecraft:diamond", "count": None, "fortune": "minecraft:ore_drops"},
}

SILK_TOUCH = {
    "condition": "minecraft:match_tool",
    "predicate": {
        "predicates": {
            "minecraft:enchantments": [
                {"enchantments": "minecraft:silk_touch", "levels": {"min": 1}}
            ]
        }
    },
}


def block_id(tier, ore):
    return "{}_{}_ore".format(tier, ore)


def write(path, payload):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(json.dumps(payload, indent=2, ensure_ascii=False))
        handle.write("\n")


def blockstate(identifier, lit):
    """红石矿是 RedStoneOreBlock，带 lit 状态；两态共用一个模型，原作也只有一张贴图"""
    if lit:
        variants = {
            "lit=false": {"model": "dawnduskterminal:block/" + identifier},
            "lit=true": {"model": "dawnduskterminal:block/" + identifier},
        }
    else:
        variants = {"": {"model": "dawnduskterminal:block/" + identifier}}
    return {"variants": variants}


def loot_table(identifier, ore):
    spec = ORE_TYPES[ore]
    functions = []
    if spec["count"] is not None:
        low, high = spec["count"]
        functions.append(
            {
                "add": False,
                "count": {"type": "minecraft:uniform", "max": float(high), "min": float(low)},
                "function": "minecraft:set_count",
            }
        )
    bonus = {
        "enchantment": "minecraft:fortune",
        "formula": spec["fortune"],
        "function": "minecraft:apply_bonus",
    }
    if "fortune_parameters" in spec:
        bonus["parameters"] = spec["fortune_parameters"]
    functions.append(bonus)
    functions.append({"function": "minecraft:explosion_decay"})
    return {
        "type": "minecraft:block",
        "pools": [
            {
                "bonus_rolls": 0.0,
                "entries": [
                    {
                        "type": "minecraft:alternatives",
                        "children": [
                            {
                                "type": "minecraft:item",
                                "conditions": [SILK_TOUCH],
                                "name": "dawnduskterminal:" + identifier,
                            },
                            {
                                "type": "minecraft:item",
                                "functions": functions,
                                "name": spec["item"],
                            },
                        ],
                    }
                ],
                "rolls": 1.0,
            }
        ],
        "random_sequence": "dawnduskterminal:blocks/" + identifier,
    }


def main():
    for tier in TIERS:
        for ore in ORE_TYPES:
            identifier = block_id(tier, ore)
            write(
                os.path.join(ASSETS, "blockstates", identifier + ".json"),
                blockstate(identifier, ore == "redstone"),
            )
            write(
                os.path.join(ASSETS, "models", "block", identifier + ".json"),
                {
                    "parent": "minecraft:block/cube_all",
                    "textures": {"all": "dawnduskterminal:block/" + identifier},
                },
            )
            write(
                os.path.join(ASSETS, "models", "item", identifier + ".json"),
                {"parent": "dawnduskterminal:block/" + identifier},
            )
            write(
                os.path.join(LOOT_DIR, identifier + ".json"),
                loot_table(identifier, ore),
            )
    print("wrote {} ore asset sets".format(len(TIERS) * len(ORE_TYPES)))


if __name__ == "__main__":
    main()
