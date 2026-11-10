"""把蓝色沙漠三种植物的贴图烘成一列色相带：每个像素只转色相，明度 V 与饱和度 S 原样。

每张底图生成 `<名字>_hues.png`：宽度不变，高度 = 原高 × 档数，第 i 档在 y = i × 原高。
方块渲染时按坐标选一档（见 `client/model/HueBandModel` 与 `ChronoPlantTints#fairyDianthusBand`）。

色相是 HSV 里的环形比例，不是乘数：转色相时先把该像素换成 HSV，h 加上偏移量，
再把 V 与 S 原样带回去。所以「一个通道到 255 之后固定住、继续压另一个通道」是这套运算的必然结果——
V 不变意味着最大通道不动，色相继续转只能去压其余通道。

档数、每档步长、中位档必须与 `ChronoPlantTints` 里的常量一致：

| 项 | 值 | Java 侧 |
|---|---|---|
| 档数 | 25 | `DIANTHUS_BANDS` |
| 每档步长 | 5° | `DIANTHUS_HUE_STEP` |
| 中位档（偏移 0°） | 12 | `DIANTHUS_NEUTRAL_BAND` |

用法：`python tools/generate_hue_sheets.py`
"""

import colorsys
import os
import sys

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEXTURE_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "dawnduskterminal", "textures", "block")

# 三种植物：蓝色沙漠里的全部植物都走同一套色相带
PLANTS = ["fairy_dianthus", "fairy_dianthus_small", "night_glow_bush"]

BANDS = 25
STEP_DEGREES = 5.0
CENTER = BANDS // 2

# 校验容差：8 位通道上 1 个最低位就能挪动低饱和像素的色相十几度，所以只在彩度够的像素上查色相
CHROMA_FOR_HUE_CHECK = 40
HUE_TOLERANCE = 4.0
VALUE_TOLERANCE = 1


def shift_hue(pixel, degrees):
    """把一个像素的色相转 degrees，V 与 S 原样返回"""
    red, green, blue, alpha = pixel
    hue, saturation, value = colorsys.rgb_to_hsv(red / 255.0, green / 255.0, blue / 255.0)
    hue = (hue + degrees / 360.0) % 1.0
    shifted = colorsys.hsv_to_rgb(hue, saturation, value)
    return (
        round(shifted[0] * 255.0),
        round(shifted[1] * 255.0),
        round(shifted[2] * 255.0),
        alpha,
    )


def hue_of(pixel):
    hue, _, _ = colorsys.rgb_to_hsv(pixel[0] / 255.0, pixel[1] / 255.0, pixel[2] / 255.0)
    return hue * 360.0


def build_sheet(name, problems, stats):
    source_path = os.path.join(TEXTURE_DIR, name + ".png")
    base = Image.open(source_path).convert("RGBA")
    width, height = base.size
    sheet = Image.new("RGBA", (width, height * BANDS), (0, 0, 0, 0))
    source = list(base.get_flattened_data())

    for band in range(BANDS):
        # 第 0 档偏蓝、末档偏绿，与 ChronoPlantTints#fairyDianthusHueShift 的符号一致
        degrees = (CENTER - band) * STEP_DEGREES
        shifted = [shift_hue(pixel, degrees) for pixel in source]
        band_image = Image.new("RGBA", (width, height))
        band_image.putdata(shifted)
        sheet.paste(band_image, (0, band * height))

        # 校验：明度 V 不变（最大通道不动）、透明度不变、色相确实转了 degrees
        checked = 0
        worst = 0.0
        for index, (before, after) in enumerate(zip(source, shifted)):
            if before[3] != after[3]:
                problems.append(f"{name} band {band} pixel {index}: alpha changed")
                break
            if max(before[:3]) - max(after[:3]) > VALUE_TOLERANCE:
                problems.append(
                    f"{name} band {band} pixel {index}: value dropped {max(before[:3])} -> {max(after[:3])}")
                break
            if max(before[:3]) - min(before[:3]) < CHROMA_FOR_HUE_CHECK:
                continue
            expected = (hue_of(before) + degrees) % 360.0
            actual = hue_of(after)
            error = min(abs(expected - actual), 360.0 - abs(expected - actual))
            checked += 1
            worst = max(worst, error)
            if error > HUE_TOLERANCE:
                problems.append(
                    f"{name} band {band} pixel {index}: hue {hue_of(before):.1f} + {degrees:.0f}"
                    f" should be {expected:.1f}, got {actual:.1f}")
                break
        stats.append((name, band, degrees, checked, worst))

    out_path = os.path.join(TEXTURE_DIR, name + "_hues.png")
    sheet.save(out_path)
    return out_path, sheet.size


def main():
    problems = []
    stats = []
    for name in PLANTS:
        path, size = build_sheet(name, problems, stats)
        print(f"{os.path.relpath(path, ROOT)}: {size[0]}x{size[1]} ({BANDS} bands, {STEP_DEGREES:g} deg each)")
    if problems:
        for problem in problems:
            print("FAIL " + problem, file=sys.stderr)
        return 1
    worst = max(error for _, _, _, _, error in stats)
    checked = sum(count for _, _, _, count, _ in stats)
    print(f"verified: value and alpha unchanged in every band; hue checked on {checked} opaque pixels"
          f" (chroma >= {CHROMA_FOR_HUE_CHECK}), worst error {worst:.1f} deg")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
