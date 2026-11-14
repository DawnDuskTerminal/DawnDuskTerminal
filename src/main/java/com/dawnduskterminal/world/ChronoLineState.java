package com.dawnduskterminal.world;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelResource;

public final class ChronoLineState extends SavedData {
    private static final String DATA_NAME = "dawnduskterminal_chrono_line";
    // 这两份快照会被 worldgen 工作线程与渲染线程读取 主线程写入 必须 volatile
    private static volatile ChronoLine serverLine;
    private static volatile ChronoLine clientLine;

    private ChronoLine line;

    public ChronoLineState(long seed) {
        this.line = ChronoLine.create(RandomSource.create(seed));
        setDirty();
    }

    public ChronoLineState(ChronoLine line) {
        this.line = Objects.requireNonNull(line);
    }

    public ChronoLine line() {
        return this.line;
    }

    public void setLine(ChronoLine line) {
        if (!line.equals(this.line)) {
            this.line = Objects.requireNonNull(line);
            setDirty();
        }
    }

    public static ChronoLineState load(CompoundTag tag, HolderLookup.Provider registries) {
        return new ChronoLineState(ChronoLine.CODEC
                .parse(NbtOps.INSTANCE, tag.getCompound("line"))
                .getOrThrow(error -> new IllegalStateException("Invalid saved chrono line: " + error)));
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put(
                "line",
                ChronoLine.CODEC
                        .encodeStart(NbtOps.INSTANCE, this.line)
                        .getOrThrow(error -> new IllegalStateException("Cannot save chrono line: " + error)));
        return tag;
    }

    public static ChronoLineState get(ServerLevel anyLevel) {
        ServerLevel overworld = anyLevel.getServer().overworld();
        Path dataFile = overworld
                .getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("data")
                .resolve(DATA_NAME + ".dat");
        return get(overworld.getDataStorage(), overworld.getSeed(), dataFile);
    }

    static ChronoLineState get(DimensionDataStorage storage, long seed, Path dataFile) {
        return storage.computeIfAbsent(
                new SavedData.Factory<>(
                        () -> {
                            // 原版会捕获读档异常并尝试创建数据；仅在确认文件不存在时允许创建。
                            if (!Files.notExists(dataFile)) {
                                throw new IllegalStateException("Cannot load chrono line from " + dataFile
                                        + "; refusing to replace existing or inaccessible saved data");
                            }
                            return new ChronoLineState(seed);
                        },
                        ChronoLineState::load,
                        null),
                DATA_NAME);
    }

    public static ChronoLine serverLine() {
        ChronoLine line = serverLine;
        return line != null ? line : ChronoLine.fallback();
    }

    public static boolean hasServerLine() {
        return serverLine != null;
    }

    public static void setServerLine(ChronoLine line) {
        serverLine = line;
    }

    /** 仅在服务端世界生成任务全部结束后清除快照。 */
    public static void resetServerLine() {
        serverLine = null;
    }

    public static ChronoLine clientLine() {
        ChronoLine line = clientLine;
        return line != null ? line : serverLine();
    }

    public static void setClientLine(ChronoLine line) {
        clientLine = line;
    }

    /** 客户端断开连接时清空 否则进新存档头几帧会拿上一档的线算天光与天色 */
    public static void resetClientLine() {
        clientLine = null;
    }
}
