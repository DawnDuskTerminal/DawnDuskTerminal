package com.dawnduskterminal.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

final class VirtualImageTrace {
    private static final int MAX_FRAMES = 200;
    private static final long MAX_AGE_NANOS = 10_000_000_000L;
    private static final int FRAME_SIZE = 4;
    private static final int VERSION = 1;
    private static final long ON_GROUND = 1L << 32;
    private static final long SPRINTING = 1L << 33;
    private static final long SWING_MAIN = 1L << 34;
    private static final long SWING_OFF = 1L << 35;
    private static final long USING_ITEM = 1L << 36;
    private static final long CROUCHING = 1L << 37;
    private static final long SWIMMING = 1L << 38;
    private static final long USING_OFF_HAND = 1L << 39;

    private final long[] frames;

    private VirtualImageTrace(long[] frames) {
        this.frames = frames;
    }

    int size() {
        return this.frames.length / FRAME_SIZE;
    }

    private Vec3 positionAt(int index) {
        int offset = index * FRAME_SIZE;
        return new Vec3(
                Double.longBitsToDouble(this.frames[offset]),
                Double.longBitsToDouble(this.frames[offset + 1]),
                Double.longBitsToDouble(this.frames[offset + 2]));
    }

    Frame frame(int index) {
        long state = this.frames[index * FRAME_SIZE + 3];
        Pose pose = (state & SWIMMING) != 0 ? Pose.SWIMMING : (state & CROUCHING) != 0 ? Pose.CROUCHING : Pose.STANDING;
        return new Frame(
                this.positionAt(index),
                (short) state * (360.0F / 65536.0F),
                (short) (state >>> 16) * (360.0F / 65536.0F),
                pose,
                (state & ON_GROUND) != 0,
                (state & SPRINTING) != 0,
                (state & SWING_MAIN) != 0,
                (state & SWING_OFF) != 0,
                (state & USING_ITEM) == 0
                        ? null
                        : (state & USING_OFF_HAND) == 0 ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND);
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Version", VERSION);
        tag.putLongArray("Frames", this.frames);
        return tag;
    }

    @Nullable
    static VirtualImageTrace load(CompoundTag tag) {
        if (tag.getInt("Version") != VERSION || !tag.contains("Frames", Tag.TAG_LONG_ARRAY)) {
            return null;
        }
        long[] frames = tag.getLongArray("Frames");
        if (frames.length == 0 || frames.length > MAX_FRAMES * FRAME_SIZE || frames.length % FRAME_SIZE != 0) {
            return null;
        }
        VirtualImageTrace trace = new VirtualImageTrace(frames);
        for (int i = 0; i < trace.size(); i++) {
            Vec3 position = trace.positionAt(i);
            if (!(Math.abs(position.x) < 30_000_000
                    && Math.abs(position.z) < 30_000_000
                    && Math.abs(position.y) < 20_000_000)) {
                return null;
            }
        }
        return trace;
    }

    record Frame(
            Vec3 position,
            float yaw,
            float pitch,
            Pose pose,
            boolean onGround,
            boolean sprinting,
            boolean swingMain,
            boolean swingOff,
            @Nullable InteractionHand usedHand) {}

    static final class Buffer {
        private final long[] frames = new long[MAX_FRAMES * FRAME_SIZE];
        private final long[] times = new long[MAX_FRAMES];
        private int start;
        private int size;

        void sample(Player player, long now, boolean replaceLatest, long actions) {
            while (this.size > 0 && now - this.times[this.start] > MAX_AGE_NANOS) {
                this.start = (this.start + 1) % MAX_FRAMES;
                this.size--;
            }
            if (!replaceLatest || this.size == 0) {
                if (this.size == MAX_FRAMES) {
                    this.start = (this.start + 1) % MAX_FRAMES;
                    this.size--;
                }
                this.size++;
            } else {
                int latest = (this.start + this.size - 1) % MAX_FRAMES;
                actions |= this.frames[latest * FRAME_SIZE + 3] & (SWING_MAIN | SWING_OFF);
            }
            int index = (this.start + this.size - 1) % MAX_FRAMES;
            int offset = index * FRAME_SIZE;
            this.times[index] = now;
            this.frames[offset] = Double.doubleToRawLongBits(player.getX());
            this.frames[offset + 1] = Double.doubleToRawLongBits(player.getY());
            this.frames[offset + 2] = Double.doubleToRawLongBits(player.getZ());
            this.frames[offset + 3] = encodeAngle(player.getYRot())
                    | (encodeAngle(player.getXRot()) << 16)
                    | (player.onGround() ? ON_GROUND : 0L)
                    | (player.isSprinting() ? SPRINTING : 0L)
                    | (player.isUsingItem()
                            ? USING_ITEM | (player.getUsedItemHand() == InteractionHand.OFF_HAND ? USING_OFF_HAND : 0L)
                            : 0L)
                    | (player.getPose() == Pose.CROUCHING ? CROUCHING : 0L)
                    | (player.getPose() == Pose.SWIMMING || player.isFallFlying() ? SWIMMING : 0L)
                    | actions;
        }

        private static long encodeAngle(float angle) {
            return Math.round(Mth.wrapDegrees(angle) * (65536.0F / 360.0F)) & 0xffffL;
        }

        VirtualImageTrace snapshot() {
            int firstPart = Math.min(this.size, MAX_FRAMES - this.start);
            long[] result = new long[this.size * FRAME_SIZE];
            System.arraycopy(this.frames, this.start * FRAME_SIZE, result, 0, firstPart * FRAME_SIZE);
            if (firstPart < this.size) {
                System.arraycopy(this.frames, 0, result, firstPart * FRAME_SIZE, (this.size - firstPart) * FRAME_SIZE);
            }
            return new VirtualImageTrace(result);
        }
    }

    static long swing(InteractionHand hand) {
        return hand == InteractionHand.MAIN_HAND ? SWING_MAIN : SWING_OFF;
    }
}
