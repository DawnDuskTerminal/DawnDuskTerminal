#!/usr/bin/env python3
"""把 Ogg Vorbis 从立体声降成单声道

**为什么必须单声道**：Minecraft 把声音交给 OpenAL，而 OpenAL 对多声道缓冲区不做空间化——
非单声道的源一律按「直达」处理，既不平移也不做距离衰减。于是这些声音在游戏里表现为
「远近一样响、出了服务端的广播半径（`16 × 音量` 格）立刻没有」，正是「传送门流体的受伤声
听起来没有衰减」那个症状。原版音效（`mob.*`、`block.*` 之类）全是单声道，也是同一个原因。

把声音降成单声道之后，客户端才会按 `sounds.json` 的 `attenuation_distance`（缺省 16 格）
做线性衰减；广播半径与衰减距离两边因此能对上，不会在边界上突然断掉。
音乐（`requiem/*`、`bgmm3` 这类非定位的曲子）保持立体声，不进这个脚本。

**取较响的声道，不取平均**：0 距离时两只耳朵分别听到左、右声道，较响的那一只就是实际听到的
音量。取两声道平均会让相关素材整体降低约 6 dB；这里按整段 RMS 挑出较响的声道，原样写出，
不做增益，于是 0 距离时较响那只耳朵的幅度与转换前相同。超过满刻度的样本在实机里本来就会
被削平，这里先按 ±1 削平再编码，避免编码器自行处理。

**不原地写**：先写到同目录的临时文件，帧数、时长与 RMS 都对上之后才原子替换。
一次大缓冲的读写在某些长素材上会让 libsndfile 崩掉并留下只有文件头的空文件，
所以这里按 64K 帧分块，原文件在最后一步之前始终不动。

用法：

    python tools/audio/downmix_to_mono.py <sounds 目录> <名字> [<名字> ...]

`<名字>` 是相对 `<sounds 目录>` 的路径，可以不带 `.ogg`。已经是单声道的文件直接跳过。
"""

from __future__ import annotations

import math
import os
import sys
from pathlib import Path

import numpy as np
import soundfile as sf

BLOCK_FRAMES = 65536

# 0 距离时的满刻度。超过它的样本在实机里会被削平，先削平再编码才能保住同样的实机波形。
FULL_SCALE = 1.0

# 转换前后的 RMS 允许差多少分贝。Vorbis 是有损编码，但同一段素材的 RMS 不会差出这个数。
RMS_TOLERANCE_DB = 0.5


def resolve(root: Path, name: str) -> Path | None:
    candidate = root / (name if name.lower().endswith(".ogg") else name + ".ogg")
    if candidate.is_file():
        return candidate
    for found in root.rglob("*.ogg"):
        if found.relative_to(root).with_suffix("").as_posix() == name:
            return found
    return None


def channel_stats(path: Path) -> dict[str, list[float] | int]:
    """整段统计左、右声道的 RMS、削平后 RMS 与峰值"""
    sums = [0.0, 0.0]
    clipped_sums = [0.0, 0.0]
    peaks = [0.0, 0.0]
    count = 0
    with sf.SoundFile(str(path)) as reader:
        while True:
            block = reader.read(BLOCK_FRAMES, dtype="float64", always_2d=True)
            if len(block) == 0:
                break
            for channel in range(2):
                samples = block[:, channel]
                sums[channel] += float(np.dot(samples, samples))
                peaks[channel] = max(peaks[channel], float(np.max(np.abs(samples))))
                clipped = np.clip(samples, -FULL_SCALE, FULL_SCALE)
                clipped_sums[channel] += float(np.dot(clipped, clipped))
            count += len(block)
    if count == 0:
        return {"rms": [0.0, 0.0], "clipped_rms": [0.0, 0.0], "peak": [0.0, 0.0], "frames": 0}
    return {
        "rms": [math.sqrt(value / count) for value in sums],
        "clipped_rms": [math.sqrt(value / count) for value in clipped_sums],
        "peak": peaks,
        "frames": count,
    }


def mono_rms(path: Path) -> float:
    total = 0.0
    count = 0
    with sf.SoundFile(str(path)) as reader:
        while True:
            block = reader.read(BLOCK_FRAMES, dtype="float64", always_2d=True)
            if len(block) == 0:
                break
            samples = block[:, 0]
            total += float(np.dot(samples, samples))
            count += len(block)
    return math.sqrt(total / count) if count else 0.0


def rms_db(value: float) -> float:
    return 20.0 * math.log10(max(value, 1.0e-12))


def downmix(path: Path) -> None:
    info = sf.info(str(path))
    if info.channels == 1:
        print(f"skip  {path.name:34s} already mono ({info.samplerate} Hz)")
        return
    if info.channels != 2:
        print(f"skip  {path.name:34s} {info.channels} channels, only stereo is handled")
        return

    stats = channel_stats(path)
    levels = stats["rms"]
    peaks = stats["peak"]
    louder = 0 if levels[0] >= levels[1] else 1
    source_clipped = stats["clipped_rms"][louder]

    temp = path.with_name(path.name + ".mono.tmp")
    try:
        written = 0
        with sf.SoundFile(str(path)) as reader:
            with sf.SoundFile(
                str(temp),
                "w",
                samplerate=reader.samplerate,
                channels=1,
                format="OGG",
                subtype="VORBIS",
                compression_level=0.9,
            ) as writer:
                while True:
                    block = reader.read(BLOCK_FRAMES, dtype="float64", always_2d=True)
                    if len(block) == 0:
                        break
                    mono = np.clip(block[:, louder], -FULL_SCALE, FULL_SCALE)
                    writer.write(mono.astype("float32"))
                    written += len(block)
        after = sf.info(str(temp))
        if after.channels != 1:
            raise SystemExit(f"{path.name}: 降混之后仍是 {after.channels} 声道")
        if after.frames != info.frames:
            raise SystemExit(f"{path.name}: 帧数 {info.frames} -> {after.frames}，不替换")
        if abs(after.duration - info.duration) >= 0.05:
            raise SystemExit(f"{path.name}: 时长 {info.duration} -> {after.duration}，不替换")
        output_rms = mono_rms(temp)
        delta = abs(rms_db(output_rms) - rms_db(source_clipped))
        if source_clipped > 1.0e-6 and delta > RMS_TOLERANCE_DB:
            raise SystemExit(
                f"{path.name}: 转换前后 RMS {rms_db(source_clipped):.2f} -> "
                f"{rms_db(output_rms):.2f} dB，相差 {delta:.2f} dB，不替换"
            )
        os.replace(temp, path)
        chosen = "L" if louder == 0 else "R"
        clipped = " clipped" if max(peaks) > FULL_SCALE else ""
        print(
            f"mono  {path.name:34s} 2 -> 1 ch  {info.samplerate} Hz  "
            f"{info.duration:5.2f}s  {written} frames  choose {chosen}  "
            f"RMS L {rms_db(levels[0]):6.2f}  R {rms_db(levels[1]):6.2f} dB  "
            f"out {rms_db(output_rms):6.2f} dB  source {rms_db(source_clipped):6.2f} dB  "
            f"delta {delta:.2f} dB{clipped}"
        )
    finally:
        if temp.exists():
            temp.unlink()


def main(argv: list[str]) -> int:
    if len(argv) < 3:
        print(__doc__)
        return 2
    root = Path(argv[1])
    if not root.is_dir():
        print(f"not a directory: {root}")
        return 2
    missing = 0
    for name in argv[2:]:
        path = resolve(root, name)
        if path is None:
            print(f"miss  {name}")
            missing += 1
            continue
        downmix(path)
    return 1 if missing else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
