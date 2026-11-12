#!/usr/bin/env python3
"""把 Ogg Vorbis 从立体声降成单声道

**为什么必须单声道**：Minecraft 把声音交给 OpenAL，而 OpenAL 对多声道缓冲区不做空间化——
非单声道的源一律按「直达」处理，既不平移也不做距离衰减。于是这些声音在游戏里表现为
「远近一样响、出了服务端的广播半径（`16 × 音量` 格）立刻没有」，正是「传送门流体的受伤声
听起来没有衰减」那个症状。原版音效（`mob.*`、`block.*` 之类）全是单声道，也是同一个原因。

把声音降成单声道之后，客户端才会按 `sounds.json` 的 `attenuation_distance`（缺省 16 格）
做线性衰减；广播半径与衰减距离两边因此能对上，不会在边界上突然断掉。
音乐（`requiem/*`、`bgmm3` 这类非定位的曲子）保持立体声，不进这个脚本。

**不原地写**：先写到同目录的临时文件，帧数与时长都对上之后才原子替换。
一次大缓冲的读写在某些长素材上会让 libsndfile 崩掉并留下只有文件头的空文件，
所以这里按 64K 帧分块，原文件在最后一步之前始终不动。

用法：

    python tools/audio/downmix_to_mono.py <sounds 目录> <名字> [<名字> ...]

`<名字>` 是相对 `<sounds 目录>` 的路径，可以不带 `.ogg`。已经是单声道的文件直接跳过。
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

import soundfile as sf

BLOCK_FRAMES = 65536


def resolve(root: Path, name: str) -> Path | None:
    candidate = root / (name if name.lower().endswith(".ogg") else name + ".ogg")
    if candidate.is_file():
        return candidate
    for found in root.rglob("*.ogg"):
        if found.relative_to(root).with_suffix("").as_posix() == name:
            return found
    return None


def downmix(path: Path) -> None:
    info = sf.info(str(path))
    if info.channels == 1:
        print(f"skip  {path.name:34s} already mono ({info.samplerate} Hz)")
        return
    if info.channels != 2:
        print(f"skip  {path.name:34s} {info.channels} channels, only stereo is handled")
        return

    temp = path.with_name(path.name + ".mono.tmp")
    try:
        written = 0
        with sf.SoundFile(str(path)) as reader:
            with sf.SoundFile(
                str(temp), "w", samplerate=reader.samplerate, channels=1, format="OGG", subtype="VORBIS"
            ) as writer:
                while True:
                    block = reader.read(BLOCK_FRAMES, dtype="int16", always_2d=True)
                    if len(block) == 0:
                        break
                    mono = (block[:, 0].astype("int32") + block[:, 1].astype("int32")) // 2
                    writer.write(mono.astype("int16"))
                    written += len(block)
        after = sf.info(str(temp))
        if after.channels != 1:
            raise SystemExit(f"{path.name}: 降混之后仍是 {after.channels} 声道")
        if after.frames != info.frames:
            raise SystemExit(f"{path.name}: 帧数 {info.frames} -> {after.frames}，不替换")
        if abs(after.duration - info.duration) >= 0.05:
            raise SystemExit(f"{path.name}: 时长 {info.duration} -> {after.duration}，不替换")
        os.replace(temp, path)
        print(
            f"mono  {path.name:34s} 2 -> 1 ch  {info.samplerate} Hz  "
            f"{info.duration:5.2f}s  {written} frames  {info.frames} checked"
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
