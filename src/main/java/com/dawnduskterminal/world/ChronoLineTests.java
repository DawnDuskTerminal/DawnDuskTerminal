package com.dawnduskterminal.world;

import com.dawnduskterminal.network.ChronoLinePayload;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.util.RandomSource;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** 检查晨昏线的输入边界，以及原版读档失败后的创建分支。 */
@GameTestHolder("dawnduskterminal")
@PrefixGameTestTemplate(false)
public final class ChronoLineTests {
    private static final long SEED = 0x5EEDL;

    private ChronoLineTests() {}

    @GameTest(template = "ddt_empty")
    public static void invalidOverridesUseTheSeededDirection(GameTestHelper helper) {
        ChronoLine expected = ChronoLine.create(RandomSource.create(SEED), "");
        for (String input : new String[] {"NaN,0", "Infinity,0", "1e309,0", "0,0", "1,0,", "invalid"}) {
            ChronoLine actual = ChronoLine.create(RandomSource.create(SEED), input);
            helper.assertTrue(actual.equals(expected), "invalid override must use the seeded direction: " + input);
        }
        ChronoLine valid = ChronoLine.create(RandomSource.create(SEED), "3,4");
        helper.assertTrue(valid.equals(new ChronoLine(0, 0, 0.6, 0.8)), "valid override must be normalised");
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void invalidLinesCannotBeConstructed(GameTestHelper helper) {
        for (double[] values : new double[][] {
            {Double.NaN, 0, 1, 0}, {0, Double.POSITIVE_INFINITY, 1, 0},
            {0, 0, Double.NaN, 0}, {0, 0, Double.POSITIVE_INFINITY, 0},
            {0, 0, 0, 0}, {0, 0, 2, 0}
        }) {
            rejects(
                    helper,
                    IllegalArgumentException.class,
                    () -> new ChronoLine(values[0], values[1], values[2], values[3]));
        }
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void savedLineRoundTrips(GameTestHelper helper) {
        ChronoLine original = new ChronoLine(120, -80, 0.6, 0.8);
        var registries = helper.getLevel().registryAccess();
        CompoundTag encoded = new ChronoLineState(original).save(new CompoundTag(), registries);
        helper.assertTrue(
                ChronoLineState.load(encoded, registries).line().equals(original),
                "a saved chrono line must round-trip without changing direction");
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void malformedSavedLinesAreRejected(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        rejects(helper, IllegalStateException.class, () -> ChronoLineState.load(new CompoundTag(), registries));
        CompoundTag malformed = new CompoundTag();
        CompoundTag line = new CompoundTag();
        line.putString("origin_x", "invalid");
        line.putDouble("origin_z", 0);
        line.putDouble("normal_x", 1);
        line.putDouble("normal_z", 0);
        malformed.put("line", line);
        rejects(helper, IllegalStateException.class, () -> ChronoLineState.load(malformed, registries));
        line.putDouble("origin_x", Double.NaN);
        rejects(helper, IllegalArgumentException.class, () -> ChronoLineState.load(malformed, registries));
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void networkRejectsInvalidNormals(GameTestHelper helper) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(
                Unpooled.buffer(),
                helper.getLevel().registryAccess(),
                net.neoforged.neoforge.network.connection.ConnectionType.NEOFORGE);
        try {
            for (double nx : new double[] {0, 2, Double.NaN, Double.POSITIVE_INFINITY}) {
                buffer.clear();
                buffer.writeDouble(0).writeDouble(0).writeDouble(nx).writeDouble(0);
                rejects(helper, DecoderException.class, () -> ChronoLinePayload.STREAM_CODEC.decode(buffer));
            }
            buffer.clear();
            ChronoLinePayload original = new ChronoLinePayload(new ChronoLine(1, 2, 0.6, 0.8));
            ChronoLinePayload.STREAM_CODEC.encode(buffer, original);
            helper.assertTrue(
                    original.equals(ChronoLinePayload.STREAM_CODEC.decode(buffer)), "valid payload must round-trip");
        } finally {
            buffer.release();
        }
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void unreadableSavedDataIsNotRecreated(GameTestHelper helper) throws IOException {
        Path directory = Files.createTempDirectory("ddt-chrono-line-");
        Path file = directory.resolve("dawnduskterminal_chrono_line.dat");
        try {
            for (int version = 0; version < 2; version++) {
                if (version == 0) {
                    Files.write(file, new byte[] {1, 2, 3});
                } else {
                    // NBT 容器有效，但晨昏线字段缺失；原版同样会捕获解码异常。
                    CompoundTag root = new CompoundTag();
                    root.put("data", new CompoundTag());
                    net.minecraft.nbt.NbtIo.writeCompressed(root, file);
                }
                byte[] original = Files.readAllBytes(file);
                DimensionDataStorage storage = new DimensionDataStorage(
                        directory.toFile(),
                        DataFixers.getDataFixer(),
                        helper.getLevel().registryAccess());
                rejects(helper, IllegalStateException.class, () -> ChronoLineState.get(storage, SEED, file));
                storage.save();
                helper.assertTrue(
                        Arrays.equals(original, Files.readAllBytes(file)),
                        "failed loading must not replace existing data");
            }
        } finally {
            Files.deleteIfExists(file);
            Files.delete(directory);
        }
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void absentSavedDataIsCreatedOnce(GameTestHelper helper) throws IOException {
        Path directory = Files.createTempDirectory("ddt-chrono-line-");
        Path file = directory.resolve("dawnduskterminal_chrono_line.dat");
        try {
            DimensionDataStorage storage = new DimensionDataStorage(
                    directory.toFile(),
                    DataFixers.getDataFixer(),
                    helper.getLevel().registryAccess());
            ChronoLineState created = ChronoLineState.get(storage, SEED, file);
            helper.assertTrue(
                    created == ChronoLineState.get(storage, SEED + 1, file), "the cached line must not be recreated");
        } finally {
            Files.delete(directory);
        }
        helper.succeed();
    }

    private static void rejects(GameTestHelper helper, Class<? extends RuntimeException> type, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException error) {
            helper.assertTrue(type.isInstance(error), "expected " + type.getSimpleName() + ", got " + error);
            return;
        }
        helper.fail("expected " + type.getSimpleName());
    }
}
