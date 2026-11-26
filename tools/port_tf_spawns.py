#!/usr/bin/env python3
"""把暮色森林的自然生成写进晨昏线的群系副本。

规则
----
1. 原版生物只留蝙蝠：所有 `minecraft:*` 的刷怪条目按类别删除，`minecraft:bat` 保留。
   这一条对全部群系生效，没有例外。
2. 按 `PAIRS` 的对应关系，把暮色森林群系的 `spawners` 并入对应的模组群系。
   映射是一对一：一个暮色森林群系只喂一个模组群系。
   已经存在的同类别同类型条目按暮色森林的顺序重排，不重复。
3. 沼泽与冰川两族在项目里只有中心群系，因此同时并入各自的中心与外围：
   沼泽并入 `swamp`（外围）与 `fire_swamp`（中心）；
   冰川并入 `glacier`（中心）与 `snowy_forest`（外围）。
4. 不在 `PAIRS` 里的群系只做第 1 条，不并入任何暮色森林的表。
   例外是 `SHARED_TABLES` 里的两份共享表：
   通用动物表铺给十片林地、草地与丘陵，
   雪地一族（冰川 + 雪林）铺给最冷的两个雪地群系。
   共享表不是映射，同一个暮色森林群系仍只出现在一条映射里。

对应关系
--------
群系对应关系取自 `docs/biomes.md` 的「装了暮色森林内容的副本」一节：
`dark_forest` 装的是 `dark_forest_center`，`birch_forest` 装的是 `firefly_forest`，
其余按同一张表。`forest` 是手工移植的暮色森林本体。

用法
----
    python3 tools/port_tf_spawns.py            # 写入群系文件
    python3 tools/port_tf_spawns.py --check    # 只报告将会改动哪些群系
"""

from __future__ import annotations

import argparse
import json
import sys
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
BIOME_DIR = REPO_ROOT / "src" / "main" / "resources" / "data" / "dawnduskterminal" / "worldgen" / "biome"

# 暮色森林 jar：先找运行目录里用户安装的那一份，再退回 Gradle 缓存里的编译依赖
TF_JAR_CANDIDATES = [
    REPO_ROOT / "run" / "client" / "mods" / "twilightforest-1.21.1-4.8.3345-universal.jar",
]

TF_GRADLE_GLOB = "caches/modules-2/files-2.1/curse.maven/the-twilight-forest-227639/*/*/*.jar"

# 每个模组群系要并入的暮色森林群系；一个暮色森林群系只出现在这里一次
PAIRS = {
    "forest": ["forest"],
    "birch_forest": ["firefly_forest"],
    "flower_forest": ["dense_forest"],
    "jungle": ["enchanted_forest"],
    "windswept_hills": ["highlands"],
    "plains": ["clearing"],
    "savanna": ["oak_savannah"],
    "dark_forest": ["dark_forest_center"],
    # 只有中心群系的两族：中心与外围一并并入
    "swamp": ["swamp", "fire_swamp"],
    "snowy_plains": ["glacier", "snowy_forest"],
}

# 原版条目里唯一保留的一项
KEPT_VANILLA = {"minecraft:bat"}

# 共享表：不是群系映射，而是同一份刷怪表铺到同类的几个群系上。
# 映射是一对一，所以这里不写成 PAIRS 的条目；这两份表各自有自己的适用对象。
SHARED_TABLES = {
    # 通用动物表：暮色森林的森林、平原、丘陵那十多个群系共用的那一份
    "generic": {
        "sources": ["forest"],
        "biomes": [
            "taiga",
            "old_growth_pine_taiga",
            "old_growth_spruce_taiga",
            "meadow",
            "sunflower_plains",
            "savanna_plateau",
            "windswept_savanna",
            "windswept_forest",
            "sparse_jungle",
            "bamboo_jungle",
        ],
    },
    # 雪地一族：冰川与雪林两份表合起来，只给最冷的两个雪地群系
    "snow": {
        "sources": ["glacier", "snowy_forest"],
        "biomes": ["frozen_peaks", "jagged_peaks"],
    },
}


def shared_sources(biome: str) -> list[str]:
    """这个群系要用到的共享表的来源群系。"""
    return [
        source
        for table in SHARED_TABLES.values()
        if biome in table["biomes"]
        for source in table["sources"]
    ]


def find_tf_jar() -> Path:
    for path in TF_JAR_CANDIDATES:
        if path.exists():
            return path
    for path in sorted((Path.home() / ".gradle").glob(TF_GRADLE_GLOB)):
        return path
    sys.exit(
        "error: 找不到暮色森林 jar。\n"
        "       请把 twilightforest-1.21.1-4.8.3345-universal.jar 放进 run/client/mods/，"
        "或先执行一次构建让 Gradle 缓存就位。"
    )


def read_tf_tables(jar: Path) -> dict[str, dict[str, list[dict]]]:
    """读暮色森林各个群系的刷怪表。"""
    tables: dict[str, dict[str, list[dict]]] = {}
    with zipfile.ZipFile(jar) as zf:
        for name in zf.namelist():
            prefix = "data/twilightforest/worldgen/biome/"
            if not (name.startswith(prefix) and name.endswith(".json")):
                continue
            biome = name[len(prefix):-len(".json")]
            doc = json.loads(zf.read(name).decode("utf-8"))
            tables[biome] = {cat: list(entries) for cat, entries in doc.get("spawners", {}).items()}
    return tables


def strip_vanilla(spawners: dict[str, list[dict]]) -> dict[str, list[dict]]:
    """删掉除蝙蝠以外的原版条目，空类别保留为空表。"""
    out: dict[str, list[dict]] = {}
    for category, entries in spawners.items():
        kept = [
            entry
            for entry in entries
            if not entry["type"].startswith("minecraft:") or entry["type"] in KEPT_VANILLA
        ]
        out[category] = kept
    return out


def merge_tf(spawners: dict[str, list[dict]], tables: list[dict[str, list[dict]]]) -> dict[str, list[dict]]:
    """并入暮色森林的条目。

    顺序写成「原有的非暮色森林条目 + 暮色森林表按它自己的顺序补齐」，
    这样同一个群系重跑多少次都得到同一份结果，不会因为先删后加把顺序翻来覆去。
    """
    tf_by_category: dict[str, list[dict]] = {}
    for table in tables:
        for category, entries in table.items():
            bucket = tf_by_category.setdefault(category, [])
            present = {entry["type"] for entry in bucket}
            for entry in entries:
                if entry["type"] not in present:
                    bucket.append(dict(entry))
                    present.add(entry["type"])

    categories = list(spawners.keys())
    categories += [category for category in tf_by_category if category not in categories]

    out: dict[str, list[dict]] = {}
    for category in categories:
        tf_entries = tf_by_category.get(category, [])
        tf_types = {entry["type"] for entry in tf_entries}
        kept = [entry for entry in spawners.get(category, []) if entry["type"] not in tf_types]
        out[category] = kept + tf_entries
    return out


def transform(biome: str, doc: dict, tf_tables: dict[str, dict[str, list[dict]]]) -> dict:
    sources = PAIRS.get(biome, []) + shared_sources(biome)
    spawners = doc.get("spawners")
    if spawners is None:
        if not sources:
            return doc
        spawners = {}
    out = dict(doc)
    stripped = strip_vanilla(spawners)
    tables = [tf_tables[name] for name in sources]
    out["spawners"] = merge_tf(stripped, tables)
    return out


def dumps(doc: dict) -> str:
    return json.dumps(doc, indent=2, ensure_ascii=False) + "\n"


def match_brace(text: str, start: int) -> int:
    """text[start] 是 '{'，返回配对 '}' 的下标。"""
    depth = 0
    in_str = False
    escaped = False
    for index in range(start, len(text)):
        char = text[index]
        if in_str:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_str = False
            continue
        if char == '"':
            in_str = True
        elif char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return index
    raise ValueError("unbalanced braces")


def splice_spawners(text: str, spawners: dict) -> str:
    """只替换 `spawners` 这一段文本，其余字节原样保留。

    整份覆盖会把 `2.5E-4` 这类指数写法重排成 `0.00025`，那些与刷怪无关。
    """
    key = '"spawners": {'
    key_at = text.index(key)
    open_brace = text.index("{", key_at)
    close_brace = match_brace(text, open_brace)
    line_start = text.rindex("\n", 0, key_at) + 1
    indent = " " * (len(text[line_start:key_at]) - len(text[line_start:key_at].lstrip()))
    body = json.dumps(spawners, indent=2, ensure_ascii=False)
    shifted = body.replace("\n", "\n" + indent)
    return text[:open_brace] + shifted + text[close_brace + 1:]


def main() -> int:
    parser = argparse.ArgumentParser(description="把暮色森林的自然生成写入晨昏线群系")
    parser.add_argument("--check", action="store_true", help="只报告将要改动的群系，不写文件")
    args = parser.parse_args()

    jar = find_tf_jar()
    print(f"twilight forest: {jar}")
    tf_tables = read_tf_tables(jar)

    for wanted in sorted({name for names in PAIRS.values() for name in names}):
        if wanted not in tf_tables:
            sys.exit(f"error: 暮色森林 jar 里没有群系 {wanted}")

    changed = 0
    for path in sorted(BIOME_DIR.glob("*.json")):
        biome = path.stem
        before = path.read_text(encoding="utf-8")
        doc = json.loads(before)
        after_doc = transform(biome, doc, tf_tables)
        if after_doc.get("spawners") == doc.get("spawners"):
            continue
        after = splice_spawners(before, after_doc["spawners"])
        if json.loads(after) != after_doc:
            sys.exit(f"error: {biome} 的刷怪表拼接结果与预期不一致")
        changed += 1
        if args.check:
            print(f"  would rewrite {biome}")
        else:
            path.write_text(after, encoding="utf-8", newline="\n")
            print(f"  rewrote {biome}")

    verb = "would change" if args.check else "changed"
    print(f"{verb}: {changed} biome files")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
