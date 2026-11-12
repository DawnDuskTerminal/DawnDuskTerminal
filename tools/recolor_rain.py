#!/usr/bin/env python3
"""把原版雨丝与水花贴图复制一份，色相改成传送门流体的色相

雨丝是 `textures/environment/rain.png`（64x256），地面水花是 `textures/particle/splash_0..3.png`（8x8）。
两者原版都是 224° 的冷蓝。这个维度的雨要与 `portal_fluid` 同一个色相，所以逐像素转一次色相：

    h := PORTAL_HUE（由 0x7F159E 算出来的 286.4°）
    s, v 原样，alpha 原样

**色相是 HSV 里的环形比例，不是乘数。** 正片叠底（把紫色乘到贴图上）只能把蓝色乘暗，
到不了环的另一侧：蓝的 h≈224°，紫的 h≈286°，乘法改的是亮度与彩度，改不了 h。
所以这条路只能复制贴图再逐像素转色相——与 `generate_hue_sheets.py` 处理仙人石竹用的是同一套运算。

生成的贴图进仓库（`assets/dawnduskterminal/textures/`），原版贴图不进：来源是 NeoForge 的
client-extra 资源 jar，开发环境在 `build/moddev/artifacts/` 下，路径也可以从参数传进来。

用法：

    python tools/recolor_rain.py [client-extra.jar]
"""

import colorsys
import glob
import io
import os
import sys
import zipfile

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets", "dawnduskterminal")
ENVIRONMENT_DIR = os.path.join(ASSETS, "textures", "environment")
PARTICLE_DIR = os.path.join(ASSETS, "textures", "particle")

# 传送门流体的浸没雾色，与 ChronoPlantTints#PURPLE 同一个值
PORTAL_RGB = (0x7F, 0x15, 0x9E)
PORTAL_HUE = colorsys.rgb_to_hsv(*[channel / 255.0 for channel in PORTAL_RGB])[0]

# 原版条目 -> 本模组的条目
RAIN = ("assets/minecraft/textures/environment/rain.png", "chrono_rain.png")
SPLASHES = [
    (f"assets/minecraft/textures/particle/splash_{index}.png", f"chrono_splash_{index}.png")
    for index in range(4)
]

# 低饱和像素的色相没有意义（灰的 h 是算出来的噪声），只在彩度够的像素上校验
CHROMA_FOR_HUE_CHECK = 0.05
HUE_TOLERANCE_DEGREES = 0.5


def find_source_jar():
    if len(sys.argv) > 1:
        return sys.argv[1]
    pattern = os.path.join(ROOT, "build", "moddev", "artifacts", "*client-extra*.jar")
    matches = glob.glob(pattern)
    if not matches:
        raise SystemExit(
            "找不到 client-extra 资源 jar：先跑一次 ./gradlew createMinecraftArtifacts，"
            "或把 jar 路径作为第一个参数传进来"
        )
    return matches[0]


def recolor(image):
    """逐像素只换色相，S 与 V 原样带回；返回新图与「被查过的像素数」"""
    source = image.convert("RGBA")
    target = Image.new("RGBA", source.size)
    source_pixels = source.load()
    target_pixels = target.load()
    checked = 0
    for y in range(source.size[1]):
        for x in range(source.size[0]):
            r, g, b, a = source_pixels[x, y]
            if a == 0:
                target_pixels[x, y] = (0, 0, 0, 0)
                continue
            h, s, v = colorsys.rgb_to_hsv(r / 255.0, g / 255.0, b / 255.0)
            if s >= CHROMA_FOR_HUE_CHECK:
                checked += 1
            nr, ng, nb = colorsys.hsv_to_rgb(PORTAL_HUE, s, v)
            target_pixels[x, y] = (round(nr * 255), round(ng * 255), round(nb * 255), a)
    return target, checked


def verify(source, recolored):
    """校验：彩度够的像素色相一致；S 与 V 与原图逐像素相同（8 位取整的误差内）"""
    source_pixels = source.convert("RGBA").load()
    recolored_pixels = recolored.load()
    worst_hue = 0.0
    worst_value = 0
    width, height = source.size
    for y in range(height):
        for x in range(width):
            sr, sg, sb, sa = source_pixels[x, y]
            if sa == 0:
                continue
            tr, tg, tb, ta = recolored_pixels[x, y]
            assert ta == sa, "alpha 必须原样"
            sh, ss, sv = colorsys.rgb_to_hsv(sr / 255.0, sg / 255.0, sb / 255.0)
            th, ts, tv = colorsys.rgb_to_hsv(tr / 255.0, tg / 255.0, tb / 255.0)
            if ss >= CHROMA_FOR_HUE_CHECK:
                diff = abs(th - PORTAL_HUE)
                diff = min(diff, 1.0 - diff) * 360.0
                worst_hue = max(worst_hue, diff)
                assert diff <= HUE_TOLERANCE_DEGREES, f"色相偏了 {diff:.2f}°"
            worst_value = max(worst_value, abs(ts - ss), abs(tv - sv))
    return worst_hue, worst_value


def main():
    jar_path = find_source_jar()
    print(f"源：{jar_path}")
    print(f"传送门流体色相：{PORTAL_HUE * 360.0:.2f}°")
    os.makedirs(ENVIRONMENT_DIR, exist_ok=True)
    os.makedirs(PARTICLE_DIR, exist_ok=True)
    with zipfile.ZipFile(jar_path) as jar:
        for entry, out_name in [RAIN, *SPLASHES]:
            with jar.open(entry) as stream:
                source = Image.open(io.BytesIO(stream.read())).convert("RGBA")
            recolored, checked = recolor(source)
            worst_hue, worst_value = verify(source, recolored)
            out_dir = ENVIRONMENT_DIR if out_name.startswith("chrono_rain") else PARTICLE_DIR
            out_path = os.path.join(out_dir, out_name)
            recolored.save(out_path)
            print(
                f"{out_name:<20} {source.size[0]}x{source.size[1]}"
                f"  查色相的像素 {checked}"
                f"  最差色相偏差 {worst_hue:.2f}°"
                f"  S/V 最大偏差 {worst_value:.4f}"
            )


if __name__ == "__main__":
    main()
