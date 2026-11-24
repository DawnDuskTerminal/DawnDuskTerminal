#!/usr/bin/env python3
"""把原版主世界世界生成移植进 DawnDuskTerminal 的「晨昏线」维度。

脚本做什么
----------
1. 从 Gradle 缓存里反编译出的 NeoForge 客户端 jar 中提取
   ``data/minecraft/worldgen/{density_function,noise,noise_settings}``。
2. 把主世界噪声路由（密度函数）以及它们间接引用的所有*可移植*噪声参数复制进
   ``dawnduskterminal`` 命名空间。
3. 写出 ``worldgen/noise_settings/chrono.json``，作为
   ``minecraft:noise_settings/overworld.json`` 的忠实移植，引用改指移植后的 id。
4. 校验移植文档里的每个引用都能解析，并打印一份报告，说明哪些仍是原版以及原因。

为什么换命名空间*就是*种子派生
------------------------------
``Noises.instantiate(registry, randomFactory, key)`` 把每个噪声场建成
``NormalNoise.create(randomFactory.fromHashOf(key.location()), params)``，而

    RandomState.random = settings.getRandomSource().newInstance(worldSeed).forkPositional()

所以一个噪声场是 ``(世界种子, 噪声键 ResourceLocation)`` 的纯函数。把完全相同的
``firstOctave``/``amplitudes`` 重新注册到 ``dawnduskterminal:*`` 下，得到的地形因此带有主世界的
特征，却是*另一个、由种子派生的*实现，而不是同坐标下主世界的一比一克隆。

数据包跨不过去的边界（是记录，不是 bug）
----------------------------------------
* ``old_blended_noise``（基础三维噪声）的种子来自写死的 ``minecraft:terrain`` 位置
  （``RandomState.wrapNew``），所以那一个分量与主世界相同。
* 含水层与矿石的位置随机源写死为 ``minecraft:aquifer`` 与 ``minecraft:ore``。
* 代码注册的单例（``minecraft:zero``、``minecraft:y``、``minecraft:blend_density``、
  ``minecraft:blend_alpha``、``minecraft:blend_offset``、``minecraft:beardifier``、
  ``minecraft:end_islands``）保持原版；它们自身不携带依赖种子的噪声。

用法
----
    python tools/port_overworld_worldgen.py            # 移植并校验
    python tools/port_overworld_worldgen.py --check     # 只校验，不写文件
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import sys
import tempfile
import zipfile
from pathlib import Path

MOD_ID = "dawnduskterminal"
VANILLA_NS = "minecraft"

REPO_ROOT = Path(__file__).resolve().parent.parent
OUT_ROOT = REPO_ROOT / "src" / "main" / "resources" / "data" / MOD_ID / "worldgen"

# --------------------------------------------------------------------------------------
# 移植范围
# --------------------------------------------------------------------------------------

# 主世界噪声路由，取自 `minecraft:noise_settings/overworld.json`
# 一并带上 `shift_x` 与 `shift_z`，让坐标偏移也重新派生
PORT_DENSITY_FUNCTIONS = [
    "shift_x",
    "shift_z",
    "overworld/base_3d_noise",
    "overworld/continents",
    "overworld/erosion",
    "overworld/depth",
    "overworld/factor",
    "overworld/jaggedness",
    "overworld/offset",
    "overworld/ridges",
    "overworld/ridges_folded",
    "overworld/sloped_cheese",
    "overworld/caves/entrances",
    "overworld/caves/noodle",
    "overworld/caves/pillars",
    "overworld/caves/spaghetti_2d",
    "overworld/caves/spaghetti_2d_thickness_modulator",
    "overworld/caves/spaghetti_roughness_function",
]

# 这些键的字符串值*不是*世界生成引用，绝不能改写
#   type        - 注册的 codec id
#   Name        - `result_state` 或 `default_block` 里的方块 id
#   random_name - 随机源名字，不是注册表 id
#   biome_is    - 群系 id
NEVER_REWRITE_KEYS = {"type", "Name", "random_name", "biome_is"}

# 在代码里注册的密度函数（NoiseRouterData.bootstrap），不以数据包文件形式发出
# 它们在任何数据包里都能解析，所以有意保持原版
CODE_REGISTERED_DENSITY_FUNCTIONS = {
    "zero",
    "y",
    "blend_density",
    "blend_alpha",
    "blend_offset",
    "beardifier",
    "end_islands",
}

# --------------------------------------------------------------------------------------
# 晨昏线方块调色板
# --------------------------------------------------------------------------------------
# 晨昏线维度生成自己的方块，而不是主世界管线写死的那批
# 世界生成数据能掌控的部分，全部在这里换掉：
#
#   * `default_block`   - 主体地形，也是地表规则的默认产物
#   * `default_fluid`   - 含水层与海洋
#   * 地表规则能产出的每一个 `result_state`
#
# 替换施加在设置文档里所有的 `"Name": ...` 上，三类都覆盖到了
# 只通过*群系特征*出现的方块（花岗岩 闪长岩 安山岩 凝灰岩矿
# 簇、岩浆湖与岩浆泉、雪堆、冰斑、圆盘状沉积）在这里够不到，因为
# 群系特征表是全局的，那些必须连维度自己的群系一起换掉
PALETTE = {
    "minecraft:grass_block": "dawnduskterminal:rosy_grass",
    "minecraft:dirt": "dawnduskterminal:rosy_dirt",
    "minecraft:stone": "dawnduskterminal:canopy_stone",
    "minecraft:granite": "dawnduskterminal:ochre",
    "minecraft:diorite": "dawnduskterminal:greenschist",
    "minecraft:andesite": "dawnduskterminal:eclogite",
    "minecraft:deepslate": "dawnduskterminal:charnockite",
    "minecraft:tuff": "dawnduskterminal:twilight_stone",
    "minecraft:sand": "dawnduskterminal:road_sand",
    "minecraft:red_sand": "dawnduskterminal:unconscious_sand",
    "minecraft:sandstone": "dawnduskterminal:siltstone",
    "minecraft:red_sandstone": "dawnduskterminal:siltstone",
    "minecraft:ice": "dawnduskterminal:frozen_canopy",
    "minecraft:packed_ice": "dawnduskterminal:frozen_canopy",
    "minecraft:snow": "dawnduskterminal:canopy_frost",
    "minecraft:snow_block": "dawnduskterminal:canopy_frost_block",
    "minecraft:water": "dawnduskterminal:portal_fluid",
    "minecraft:lava": "dawnduskterminal:enthu_fluid",
}


# --------------------------------------------------------------------------------------
# 定位原版数据包
# --------------------------------------------------------------------------------------


def gradle_cache_roots() -> list[Path]:
    roots = []
    env = os.environ.get("GRADLE_USER_HOME")
    if env:
        roots.append(Path(env))
    home = Path(os.path.expanduser("~"))
    roots.append(home / ".gradle")
    return [r for r in roots if r.is_dir()]


def find_minecraft_client_jars() -> list[Path]:
    found: list[Path] = []
    for root in gradle_cache_roots():
        for pattern in (
            "caches/neoformruntime/artifacts/minecraft_*_client.jar",
            "caches/neoformruntime/intermediate_results/*_resourcesOutput.jar",
            "caches/modules-2/files-2.1/net.neoforged/minecraft*/**/*.jar",
        ):
            found.extend(sorted(root.glob(pattern)))
    # 只保留真正带原版世界生成数据包的归档
    usable = []
    for jar in found:
        try:
            with zipfile.ZipFile(jar) as zf:
                if any(n.startswith("data/minecraft/worldgen/noise_settings/") for n in zf.namelist()):
                    usable.append(jar)
        except zipfile.BadZipFile:
            continue
    return usable


def extract_vanilla_worldgen(dest: Path) -> Path:
    jars = find_minecraft_client_jars()
    if not jars:
        sys.exit(
            "error: could not find a Minecraft client jar containing data/minecraft/worldgen/.\n"
            "       Run `gradlew build` (or runClient) once so NeoForm populates the Gradle cache."
        )
    jar = jars[0]
    print(f"vanilla source : {jar}")
    worldgen_root = dest / "worldgen"
    tag_root = dest / "tags" / "biome"
    block_tag_root = dest / "tags" / "block"
    prefixes = (
        ("data/minecraft/worldgen/", worldgen_root),
        ("data/minecraft/tags/worldgen/biome/", tag_root),
        ("data/minecraft/tags/block/", block_tag_root),
    )
    counts = {root: 0 for _, root in prefixes}
    with zipfile.ZipFile(jar) as zf:
        for name in zf.namelist():
            if name.endswith("/"):
                continue
            for prefix, root in prefixes:
                if name.startswith(prefix):
                    target = root / name[len(prefix):]
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(zf.read(name))
                    counts[root] += 1
                    break
    print(f"extracted      : {counts[worldgen_root]} worldgen files, {counts[tag_root]} biome tags")
    return worldgen_root


# --------------------------------------------------------------------------------------
# 引用解析
# --------------------------------------------------------------------------------------


def rel_id(resource_id: str) -> str:
    return resource_id.split(":", 1)[1] if ":" in resource_id else resource_id


class Vanilla:
    def __init__(self, root: Path) -> None:
        self.root = root
        self.df = {p.relative_to(root / "density_function").with_suffix("").as_posix()
                   for p in (root / "density_function").rglob("*.json")}
        self.noise = {p.relative_to(root / "noise").with_suffix("").as_posix()
                      for p in (root / "noise").rglob("*.json")}

    def classify(self, value: str) -> tuple[str, str] | None:
        """Return ('df'|'noise', relative_path) when `value` is a portable vanilla id."""
        if not value.startswith(f"{VANILLA_NS}:"):
            return None
        path = rel_id(value)
        if path in self.df:
            return ("df", path)
        if path in self.noise:
            return ("noise", path)
        return None

    def read(self, kind: str, path: str):
        folder = "density_function" if kind == "df" else "noise"
        return json.loads((self.root / folder / f"{path}.json").read_text(encoding="utf-8"))


def iter_reference_strings(node, key: str | None = None):
    """Yield every string that sits in a position that can hold a worldgen reference."""
    if isinstance(node, dict):
        for k, v in node.items():
            yield from iter_reference_strings(v, k)
    elif isinstance(node, list):
        for v in node:
            yield from iter_reference_strings(v, key)
    elif isinstance(node, str) and key not in NEVER_REWRITE_KEYS:
        yield node


def iter_block_names(node):
    """Yield every block-state `Name` in a settings document."""
    if isinstance(node, dict):
        for k, v in node.items():
            if k == "Name" and isinstance(v, str):
                yield v
            else:
                yield from iter_block_names(v)
    elif isinstance(node, list):
        for v in node:
            yield from iter_block_names(v)


def compute_closure(vanilla: Vanilla, settings_doc) -> tuple[set[str], set[str]]:
    """Transitively collect the density functions and noise params that must be ported."""
    df: set[str] = set()
    noise: set[str] = set()

    queue: list[object] = []
    for name in PORT_DENSITY_FUNCTIONS:
        if name not in vanilla.df:
            sys.exit(f"error: vanilla density function `{name}` not found in the extracted datapack")
        queue.append(vanilla.read("df", name))
    queue.append(settings_doc)

    while queue:
        doc = queue.pop()
        for value in iter_reference_strings(doc):
            hit = vanilla.classify(value)
            if hit is None:
                continue
            kind, path = hit
            if kind == "df":
                if path not in df:
                    df.add(path)
                    queue.append(("df", vanilla.read("df", path)))
            else:
                noise.add(path)

    for name in PORT_DENSITY_FUNCTIONS:
        df.add(name)
    return df, noise


def rewrite(node, df: set[str], noise: set[str], key: str | None = None):
    if isinstance(node, dict):
        return {k: rewrite(v, df, noise, k) for k, v in node.items()}
    if isinstance(node, list):
        return [rewrite(v, df, noise, key) for v in node]
    if isinstance(node, str) and key not in NEVER_REWRITE_KEYS:
        if node.startswith(f"{VANILLA_NS}:"):
            path = rel_id(node)
            if path in df or path in noise:
                return f"{MOD_ID}:{path}"
    return node


def apply_palette(node):
    """Swaps every block-state `Name` the worldgen settings can own onto the chrono palette."""
    if isinstance(node, dict):
        return {
            k: (PALETTE.get(v, v) if k == "Name" and isinstance(v, str) else apply_palette(v))
            for k, v in node.items()
        }
    if isinstance(node, list):
        return [apply_palette(v) for v in node]
    return node


# --------------------------------------------------------------------------------------
# 调色板层：群系以及它们铺的特征
# --------------------------------------------------------------------------------------
# 凡是经由*群系特征*出现的，噪声设置都够不到，
# 因为群系特征表属于全局群系注册表。所以维度自己拥有一份
# 会碰到调色板方块的群系与特征副本，并互相接好：
#
#   configured_feature  （换掉方块提供器与判据）
#        ^
#   placed_feature      （指向我们的 configured feature）
#        ^
#   biome               （特征表指向我们的 placed feature）
#        ^
#   multi_noise 参数表  （指向我们的群系）
#
# 不受影响的保持原版 id，副本往下一层也沿用原版的 id


def load_json_dir(root: Path) -> dict[str, object]:
    out: dict[str, object] = {}
    if not root.is_dir():
        return out
    for p in sorted(root.rglob("*.json")):
        out[p.relative_to(root).with_suffix("").as_posix()] = json.loads(p.read_text(encoding="utf-8"))
    return out


def write_json(path: Path, doc) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(doc, indent=2) + "\n", encoding="utf-8")


def references_palette(node) -> bool:
    """True when the document names one of the blocks/fluids the palette owns."""
    if isinstance(node, dict):
        return any(references_palette(v) for v in node.values())
    if isinstance(node, list):
        return any(references_palette(v) for v in node)
    return isinstance(node, str) and node in PALETTE


def references_any(node, mapping: dict[str, str]) -> bool:
    if isinstance(node, dict):
        return any(references_any(v, mapping) for v in node.values())
    if isinstance(node, list):
        return any(references_any(v, mapping) for v in node)
    return isinstance(node, str) and node in mapping


def swap_block_ids(node, key: str | None = None):
    """Swaps block/fluid id references, everywhere except under a codec `type` key."""
    if isinstance(node, dict):
        return {k: (v if k == "type" else swap_block_ids(v, k)) for k, v in node.items()}
    if isinstance(node, list):
        return [swap_block_ids(v, key) for v in node]
    if isinstance(node, str) and key != "type" and node in PALETTE:
        return PALETTE[node]
    return node


def retarget(node, mapping: dict[str, str]):
    """Rewrites worldgen id references, e.g. a placed feature's configured feature."""
    if isinstance(node, dict):
        return {k: retarget(v, mapping) for k, v in node.items()}
    if isinstance(node, list):
        return [retarget(v, mapping) for v in node]
    if isinstance(node, str) and node in mapping:
        return mapping[node]
    return node


def collect_refs(node, names: set[str]) -> set[str]:
    """Relative ids out of `names` that the document references, in either namespace."""
    found: set[str] = set()
    if isinstance(node, dict):
        for v in node.values():
            found |= collect_refs(v, names)
    elif isinstance(node, list):
        for v in node:
            found |= collect_refs(v, names)
    elif isinstance(node, str):
        for ns in (VANILLA_NS, MOD_ID):
            if node.startswith(f"{ns}:"):
                path = rel_id(node)
                if path in names:
                    found.add(path)
                break
    return found


def overworld_biome_names(vanilla_tag_root: Path) -> set[str]:
    """The biomes `minecraft:is_overworld` lists - the only ones this dimension can ever reach."""
    tag = vanilla_tag_root / "is_overworld.json"
    if not tag.exists():
        return set()
    names: set[str] = set()
    for entry in json.loads(tag.read_text(encoding="utf-8")).get("values", []):
        name = entry.get("id") if isinstance(entry, dict) else entry
        if isinstance(name, str) and name.startswith(f"{VANILLA_NS}:"):
            names.add(rel_id(name))
    return names


def id_map(names) -> dict[str, str]:
    return {f"{VANILLA_NS}:{k}": f"{MOD_ID}:{k}" for k in names}


# 拷贝后的特征要判断的方块标签。原版自己的标签与主世界共用，
# 所以维度用自己那份，拷贝的特征指向它们，而不是去拓宽原版的标签，
# 拓宽还会让每个矿石特征替换掉所有调色板石头，而不只是石头
BLOCK_TAG_RETARGET = (
    "base_stone_overworld",
    "stone_ore_replaceables",
    "deepslate_ore_replaceables",
    "overworld_carver_replaceables",
    "lush_ground_replaceable",
    "dirt",
    "dirt_replaceable",
    "sand",
    "snow",
    "ice",
    "leaves",
    "logs",
)


def retarget_biome_ids(node, biome_names: set[str], key: str | None = None):
    """Points biome conditions at our copies.

    Vanilla's overworld surface rule names biomes directly (`biome_is: ["minecraft:desert", ...]`)
    rather than through tags. In this dimension the real biome is the copy, so an unrewritten
    `minecraft:desert` never matches, every branch misses, and the whole world falls through to the
    default grass/dirt branch.
    """
    if isinstance(node, dict):
        return {k: retarget_biome_ids(v, biome_names, k) for k, v in node.items()}
    if isinstance(node, list):
        return [retarget_biome_ids(v, biome_names, key) for v in node]
    if isinstance(node, str) and key in ("biome_is", "biomes"):
        ns, sep, path = node.partition(":")
        if sep and ns == VANILLA_NS and path in biome_names:
            return f"{MOD_ID}:{path}"
    return node


def retarget_block_tags(node, key: str | None = None):
    """Points block tag predicates at our own tags, only where a tag is really being referenced."""
    if isinstance(node, dict):
        return {k: retarget_block_tags(v, k) for k, v in node.items()}
    if isinstance(node, list):
        return [retarget_block_tags(v, key) for v in node]
    if isinstance(node, str) and key in ("tag", "matching_block_tag", "replaceable"):
        prefix = "#" if node.startswith("#") else ""
        ns, sep, path = node[len(prefix):].partition(":")
        if sep and ns == VANILLA_NS and path in BLOCK_TAG_RETARGET:
            return f"{prefix}{MOD_ID}:{path}"
    return node


def build_palette_layer(vanilla_root: Path, vanilla_tag_root: Path, out_root: Path) -> dict:
    cf_all = load_json_dir(vanilla_root / "configured_feature")
    pf_all = load_json_dir(vanilla_root / "placed_feature")
    biome_all = load_json_dir(vanilla_root / "biome")
    for biome in biome_all.values():
        if "features" in biome:
            biome["features"] = [
                [feature for feature in step if feature != "minecraft:glow_lichen"]
                for step in biome["features"]
            ]
    carver_all = load_json_dir(vanilla_root / "configured_carver")
    overworld = overworld_biome_names(vanilla_tag_root)

    # 1. 会放置或判断调色板方块的配置特征，并向上闭包：否则原版父特征
    #    会继续指向那个铺调色板方块的原版子特征
    cf_keep = {k for k, v in cf_all.items() if references_palette(v)}
    while True:
        grown = cf_keep | {k for k, v in cf_all.items() if collect_refs(v, cf_keep)}
        if grown == cf_keep:
            break
        cf_keep = grown

    # 2. 能到达保留的配置特征、或自己就碰到调色板方块的放置特征
    pf_keep = {k for k, v in pf_all.items() if references_palette(v) or collect_refs(v, cf_keep)}

    # 3. 特征表引用了保留放置特征的主世界群系
    biome_keep = {k for k, v in biome_all.items() if k in overworld and collect_refs(v, pf_keep)}

    # 4. 雕刻器是从群系的 `carvers` 映射而不是特征表到达的配置特征，
    #    它们同样按方块标签雕刻，漏掉就没有洞穴
    carvers = {rel_id(ref) for k in biome_keep for refs in (biome_all[k].get("carvers") or {}).values()
               for ref in refs}

    # 5. 把特征集裁剪到保留群系真正能到达的范围（顺带丢掉下界特征）
    biome_docs = {k: retarget(swap_block_ids(biome_all[k]),
                              id_map(pf_keep) | id_map(carvers)) for k in biome_keep}
    pf_keep = collect_refs(biome_docs, pf_all)
    cf_keep = collect_refs({k: pf_all[k] for k in pf_keep}, cf_keep)
    while True:
        grown = cf_keep | collect_refs({k: cf_all[k] for k in cf_keep}, cf_all)
        if grown == cf_keep:
            break
        cf_keep = grown
    # 雕刻器有自己的注册表（worldgen/carver），不在 configured_feature，而且它们按
    # `replaceable` 雕刻，没拷贝的雕刻器找不到匹配方块，什么也雕不出来
    for k in sorted(carvers & set(carver_all)):
        write_json(out_root / "configured_carver" / f"{k}.json",
                   retarget_block_tags(swap_block_ids(carver_all[k])))

    # 群系引用只改写一次，而且要在两个集合都定稿之后：第 3 步是用一份临时的
    # pf_keep 建的，像 freeze_top_layer 这种只属于群系的特征当时还不在里面
    biome_docs = {k: retarget(swap_block_ids(biome_all[k]),
                              id_map(pf_keep) | id_map(carvers)) for k in biome_keep}

    for k in sorted(cf_keep):
        write_json(out_root / "configured_feature" / f"{k}.json",
                   retarget_block_tags(swap_block_ids(cf_all[k])))
    # `minecraft:freeze_top_layer` 在代码里写死 ICE 与 SNOW，JSON 替换够不到它，
    # 所以维度把特征表指向自己那个铺调色板雪与冰的特征
    write_json(out_root / "configured_feature" / "freeze_top_layer.json",
               {"type": f"{MOD_ID}:freeze_top_layer", "config": {}})
    write_json(out_root / "configured_feature" / "charnockite_lichen_patch.json",
               {"type": f"{MOD_ID}:charnockite_lichen_patch", "config": {}})
    for k in sorted(pf_keep):
        doc = retarget_block_tags(retarget(swap_block_ids(pf_all[k]), id_map(cf_keep)))
        if k == "freeze_top_layer":
            doc["feature"] = f"{MOD_ID}:freeze_top_layer"
        elif k == "sculk_patch_deep_dark":
            doc["feature"] = f"{MOD_ID}:charnockite_lichen_patch"
            for modifier in doc["placement"]:
                if modifier["type"] == "minecraft:height_range":
                    modifier["height"]["max_inclusive"] = {"absolute": 8}
        write_json(out_root / "placed_feature" / f"{k}.json", doc)
    for k in sorted(biome_docs):
        write_json(out_root / "biome" / f"{k}.json", biome_docs[k])

    biome_map = id_map(biome_keep)
    param_lists = 0
    for name, doc in sorted(load_json_dir(vanilla_root / "multi_noise_biome_source_parameter_list").items()):
        write_json(out_root / "multi_noise_biome_source_parameter_list" / f"{name}.json",
                   retarget(doc, biome_map))
        param_lists += 1

    return {
        "configured_feature": len(cf_keep),
        "configured_feature_total": len(cf_all),
        "placed_feature": len(pf_keep),
        "placed_feature_total": len(pf_all),
        "biome": len(biome_keep),
        "biome_total": len(biome_all),
        "parameter_list": param_lists,
        "biome_map": biome_map,
    }


# 原版群系标签是承重的：结构摆放 刷怪门槛 以及一大堆生物与布景
# 行为都挂在它们上面。我们的群系副本是新的 id，所以每个提到原版群系的
# 原版标签也要提到副本。数据包标签默认是合并的，所以每个生成的
# 文件只需列出我们的 id
VANILLA_BIOME_TAG_OUT = REPO_ROOT / "src/main/resources/data/minecraft/tags/worldgen/biome"
PALETTE_BIOME_TAG_OUT = REPO_ROOT / "src/main/resources/data" / MOD_ID / "tags/worldgen/biome"


VANILLA_BLOCK_TAG_OUT = REPO_ROOT / "src/main/resources/data/minecraft/tags/block"
PALETTE_BLOCK_TAG_OUT = REPO_ROOT / "src/main/resources/data" / MOD_ID / "tags/block"


# 这些标签是*原版代码*要读的，不只是数据：SnowyDirtBlock 判断 `#minecraft:snow` 来决定
# 土方块要不要显示积雪贴图，BushBlock/StemBlock/SugarCaneBlock 判断
# `#minecraft:dirt`，CactusBlock 判断 `#minecraft:sand`，Feature.isDirt/isStone 与
# 钟乳石特征判断土与基础石，OceanRuinPieces 判断 `#minecraft:ice`。数据包没法
# 让代码改指另一个标签，所以这些在我们自己的标签之外再留一份原版合并。`leaves` 与 `logs`
# 有意缺席：调色板两者都不映射，没有我们的东西要加
CODE_READ_TAGS = (
    "snow",
    "ice",
    "dirt",
    "sand",
    "base_stone_overworld",
    "stone_ore_replaceables",
    "deepslate_ore_replaceables",
)


def read_tag_entries(path: Path) -> list[str]:
    if not path.exists():
        return []
    entries: list[str] = []
    for entry in json.loads(path.read_text(encoding="utf-8")).get("values", []):
        name = entry.get("id") if isinstance(entry, dict) else entry
        if isinstance(name, str):
            entries.append(name)
    return entries


def build_block_tags(vanilla_tag_root: Path) -> int:
    """Our own block tags, holding the palette equivalents of vanilla's entries.

    These used to be `data/minecraft/tags/block/*` merges instead, which is a global edit for a
    dimension-local need: it reached the Overworld, and because `base_stone_overworld` ended up
    containing every stone in the palette, each ore feature could replace any of the others.
    """
    written = 0
    for name in BLOCK_TAG_RETARGET:
        # 嵌套的原版标签被展平，这样不会有人再指回原版标签
        pending = [f"#{VANILLA_NS}:{name}"]
        pending += read_tag_entries(vanilla_tag_root / f"{name}.json")
        pending += read_tag_entries(VANILLA_BLOCK_TAG_OUT / f"{name}.json")
        seen: set[str] = set()
        values: list[str] = []
        while pending:
            entry = pending.pop()
            if entry.startswith("#"):
                ref = rel_id(entry)
                if ref in seen:
                    continue
                seen.add(ref)
                pending += read_tag_entries(vanilla_tag_root / f"{ref}.json")
                continue
            values.append(PALETTE.get(entry, entry))
        mine = sorted(v for v in dict.fromkeys(values) if v.startswith(f"{MOD_ID}:"))
        if name in CODE_READ_TAGS:
            write_json(VANILLA_BLOCK_TAG_OUT / f"{name}.json", {"replace": False, "values": mine})
        write_json(PALETTE_BLOCK_TAG_OUT / f"{name}.json",
                   {"replace": False, "values": sorted(dict.fromkeys(values))})
        written += 1
    return written


def build_biome_tags(vanilla_tag_root: Path, biome_map: dict[str, str]) -> int:
    if VANILLA_BIOME_TAG_OUT.exists():
        shutil.rmtree(VANILLA_BIOME_TAG_OUT)
    written = 0
    for p in sorted(vanilla_tag_root.rglob("*.json")):
        tag = json.loads(p.read_text(encoding="utf-8"))
        ours = []
        for entry in tag.get("values", []):
            name = entry.get("id") if isinstance(entry, dict) else entry
            if not isinstance(name, str) or name.startswith("#"):
                continue
            if name in biome_map:
                ours.append(biome_map[name])
        if not ours:
            continue
        write_json(VANILLA_BIOME_TAG_OUT / p.relative_to(vanilla_tag_root),
                   {"replace": False, "values": sorted(set(ours))})
        written += 1

    # 用一个标签列出所有群系副本，让维度能把群系源指向这个集合
    write_json(PALETTE_BIOME_TAG_OUT / "palette_biomes.json",
               {"replace": False, "values": sorted(biome_map.values())})
    return written


# --------------------------------------------------------------------------------------
# 校验
# --------------------------------------------------------------------------------------


def validate(out_root: Path, vanilla: Vanilla) -> int:
    problems: list[str] = []
    leftover: dict[str, set[str]] = {}

    def check_doc(label: str, doc) -> None:
        for value in iter_reference_strings(doc):
            if value.startswith(f"{MOD_ID}:"):
                path = rel_id(value)
                if not (out_root / "density_function" / f"{path}.json").exists() and not (
                    out_root / "noise" / f"{path}.json"
                ).exists():
                    problems.append(f"{label}: unresolved reference {value}")
            elif value.startswith(f"{VANILLA_NS}:"):
                path = rel_id(value)
                is_df = path in vanilla.df
                is_noise = path in vanilla.noise
                if is_df or is_noise:
                    leftover.setdefault(path, set()).add(label)

    for p in sorted((out_root / "density_function").rglob("*.json")):
        check_doc(p.relative_to(out_root).as_posix(), json.loads(p.read_text(encoding="utf-8")))
    for p in sorted((out_root / "noise_settings").rglob("*.json")):
        check_doc(p.relative_to(out_root).as_posix(), json.loads(p.read_text(encoding="utf-8")))

    print()
    print("validation")
    print("----------")
    if problems:
        for p in problems:
            print(f"  FAIL  {p}")
    else:
        print("  ok    every dawnduskterminal: reference resolves to a generated file")

    if leftover:
        print("  note  these portable vanilla ids are still referenced on purpose:")
        for path, labels in sorted(leftover.items()):
            kind = "density_function" if path in vanilla.df else "noise"
            print(f"          {VANILLA_NS}:{path}  ({kind}) <- {', '.join(sorted(labels))}")

    # 调色板覆盖：设置里不能再产出调色板拥有的方块
    settings_path = out_root / "noise_settings" / "chrono.json"
    if settings_path.exists():
        names: dict[str, int] = {}
        for value in iter_block_names(json.loads(settings_path.read_text(encoding="utf-8"))):
            names[value] = names.get(value, 0) + 1
        survived = sorted(name for name in names if name in PALETTE)
        for name in survived:
            problems.append(f"noise_settings/chrono.json still emits the palette source {name}")
        print(f"  palette: the settings emit {len(names)} distinct block states")
        for name, count in sorted(names.items(), key=lambda kv: (-kv[1], kv[0])):
            if name in PALETTE:
                mark = "PALETTE SOURCE - should be gone"
            elif name.startswith(f"{MOD_ID}:"):
                mark = "chrono palette"
            else:
                mark = "left vanilla by design"
            print(f"          {name:<38} x{count:<4} {mark}")
    return 1 if problems else 0


# --------------------------------------------------------------------------------------
# 主流程
# --------------------------------------------------------------------------------------


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--check", action="store_true", help="validate the existing output without writing")
    args = ap.parse_args()

    with tempfile.TemporaryDirectory(prefix="ddt-worldgen-port-") as tmp:
        vanilla_root = extract_vanilla_worldgen(Path(tmp))
        vanilla_tag_root = Path(tmp) / "tags" / "biome"
        vanilla_block_tag_root = Path(tmp) / "tags" / "block"
        vanilla = Vanilla(vanilla_root)
        overworld_settings = json.loads(
            (vanilla_root / "noise_settings" / "overworld.json").read_text(encoding="utf-8")
        )
        print(f"vanilla has    : {len(vanilla.df)} density functions, {len(vanilla.noise)} noise params")

        if args.check:
            return validate(OUT_ROOT, vanilla)

        df_set, noise_set = compute_closure(vanilla, overworld_settings)
        print(f"porting        : {len(df_set)} density functions, {len(noise_set)} noise params")

        # 清掉本脚本负责的一切，不留任何陈旧文件
        for sub in ("density_function", "noise", "noise_settings", "configured_feature",
                    "placed_feature", "biome", "configured_carver",
                    "multi_noise_biome_source_parameter_list"):
            if (OUT_ROOT / sub).exists():
                shutil.rmtree(OUT_ROOT / sub)

        written = 0
        for path in sorted(df_set):
            doc = rewrite(vanilla.read("df", path), df_set, noise_set)
            target = OUT_ROOT / "density_function" / f"{path}.json"
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(json.dumps(doc, indent=2) + "\n", encoding="utf-8")
            written += 1
        for path in sorted(noise_set):
            doc = vanilla.read("noise", path)
            target = OUT_ROOT / "noise" / f"{path}.json"
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(json.dumps(doc, indent=2) + "\n", encoding="utf-8")
            written += 1

        settings = rewrite(overworld_settings, df_set, noise_set)
        settings = apply_palette(settings)
        # 地表规则直接点名群系，所以必须点名我们的副本
        settings = retarget_biome_ids(settings, overworld_biome_names(vanilla_tag_root))
        target = OUT_ROOT / "noise_settings" / "chrono.json"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(settings, indent=2) + "\n", encoding="utf-8")
        written += 1

        stats = build_palette_layer(vanilla_root, vanilla_tag_root, OUT_ROOT)
        tags = build_biome_tags(vanilla_tag_root, stats["biome_map"])
        block_tags = build_block_tags(vanilla_block_tag_root)
        written += (stats["configured_feature"] + stats["placed_feature"]
                    + stats["biome"] + stats["parameter_list"] + block_tags)
        print("biome layer    : "
              f"configured_feature {stats['configured_feature']}/{stats['configured_feature_total']}, "
              f"placed_feature {stats['placed_feature']}/{stats['placed_feature_total']}, "
              f"biome {stats['biome']}/{stats['biome_total']}, "
              f"parameter_list {stats['parameter_list']}, biome tags {tags}, block tags {block_tags}")

    print(f"wrote          : {written} files under {OUT_ROOT.relative_to(REPO_ROOT)}")
    return validate(OUT_ROOT, vanilla)


if __name__ == "__main__":
    raise SystemExit(main())
