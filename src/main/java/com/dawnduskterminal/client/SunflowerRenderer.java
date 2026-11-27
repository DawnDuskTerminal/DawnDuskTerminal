package com.dawnduskterminal.client;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.block.AbstractSunflowerBlock;
import com.dawnduskterminal.block.GhoflowerBlock;
import com.dawnduskterminal.block.LunarGhoflowerBlock;
import com.dawnduskterminal.block.MoonflowerBlock;
import com.dawnduskterminal.block.MoonflowerStemBlock;
import com.dawnduskterminal.block.entity.SunflowerBlockEntity;
import com.dawnduskterminal.world.ChronoLine;
import com.dawnduskterminal.world.ChronoLineState;
import com.dawnduskterminal.world.ChronoSun;
import com.dawnduskterminal.world.SunflowerPlant;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 向日葵的自绘渲染
 *
 * <h2>为什么不用方块模型</h2>
 *
 * <p>每株的叶片数、大小、旋转与整株偏移各不相同，方块状态装不下。
 * 所以几何在客户端按株底坐标现算：柱身与叶片算一次缓存在方块实体上，花盘每帧按太阳或月亮方向摆。
 *
 * <h2>几何</h2>
 *
 * <p>柱身是两片零厚度平面，各自<strong>绕 Y 轴转 ±45 度</strong>，交叉成 X 形，不是正交的十字。
 * 柱身宽 2 像素；下段高 16 像素、顶段只有 8 像素，花盘坐在顶段 8 像素处。
 *
 * <p>叶片 5×7 像素，根在贴图 v 小端、尖端在 v 大端；叶面向外下垂，法线朝上微微外倾。
 *
 * <h2>贴图走方块图集</h2>
 *
 * <p>贴图由 {@code models/block/sunflower.json} 的 {@code textures} 列进方块图集，这里按 sprite 的范围去贴。
 * 不能用 {@link RenderType#entityCutoutNoCull} 直接指文件贴图：那种写法要求贴图已经被
 * {@code TextureManager} 加载，而 {@code textures/block/sunflower.png} 只被 stitch 进图集，
 * 作为独立贴图并不存在，结果整株会变成黑加品红的 missing texture。
 *
 * <h2>两面都发</h2>
 *
 * <p>柱身与叶片各是一片平面，按方块那一套有背面剔除，所以每片都发正反两个四边形。
 * 花盘本来就是正反两片，正面用花正面那一块、背面用花背面那一块。
 */
public final class SunflowerRenderer implements BlockEntityRenderer<SunflowerBlockEntity> {
    private static final ResourceLocation DAY_TEXTURE = DawnDuskTerminal.id("block/sunflower");

    private static final ResourceLocation NIGHT_TEXTURE = DawnDuskTerminal.id("block/sunflower_night");

    /** 贴图边长，与生成脚本的 TEXTURE_SIZE 一致 */
    private static final float TEXTURE_SIZE = 64.0F;

    /** 柱身半宽，像素 */
    private static final float STEM_HALF_WIDTH = 1.0F;

    /** 柱身平面绕 Y 的倾角，正负各一片，交叉成 X */
    private static final float STEM_SKEW_DEGREES = 45.0F;

    /** 非顶段的柱身高，像素 */
    private static final float STEM_HEIGHT = 16.0F;

    /** 叶片尺寸，像素 */
    private static final float LEAF_WIDTH = 5.0F;

    private static final float LEAF_LENGTH = 7.0F;

    /** 花盘半宽、沿朝向探出的距离、两面间距的一半，像素 */
    private static final float HEAD_HALF = 5.5F;

    private static final float HEAD_REACH = 1.5F;

    private static final float HEAD_THICKNESS = 0.5F;

    /** 像素转格 */
    private static final float PIXEL = 1.0F / 16.0F;

    /** 每个顶点 9 个 float：坐标、UV、法线、明暗 */
    private static final int STRIDE = 9;

    /** 图集里的区域，单位像素，与 build_sunflower_assets.py 的 REGIONS 表一致 */
    private static final float FACE_U = 0.0F;

    private static final float FACE_V = 0.0F;

    private static final float BACK_U = 15.0F;

    private static final float BACK_V = 0.0F;

    private static final float HEAD_TEXTURE_SIZE = 11.0F;

    private static final float STEM_U = 0.0F;

    private static final float STEM_V_BOTTOM = 31.0F;

    private static final float LEAF_U = 12.0F;

    /**
     * 叶片区域的上下沿
     *
     * <p>贴在柱身上的那一头（根）取<strong>上沿</strong>，向外伸出的那一头（尖）取下沿。
     * 图集重排到 64×64 之后，叶片区域是 (12,15)–(17,22)：上沿 15 就是原来 32×32 里的 0，
     * 下沿 22 就是原来的 7。重排时把这两个数写反过一次，改图集版式时不要再对调。
     */
    private static final float LEAF_V_ROOT = 15.0F;

    private static final float LEAF_V_TIP = 22.0F;

    private static final Vec3 WORLD_UP = new Vec3(0.0D, 1.0D, 0.0D);

    public SunflowerRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(
            SunflowerBlockEntity blockEntity,
            float partialTick,
            PoseStack poses,
            MultiBufferSource buffers,
            int packedLight,
            int packedOverlay) {
        BlockState state = blockEntity.getBlockState();
        if (!(state.getBlock() instanceof AbstractSunflowerBlock)) {
            return;
        }
        int segment = state.getValue(AbstractSunflowerBlock.SEGMENT);
        boolean head = state.getValue(AbstractSunflowerBlock.HEAD);
        BlockPos pos = blockEntity.getBlockPos();
        BlockPos base = pos.below(segment);
        long seed = SunflowerPlant.seed(base);
        int key = segment | (head ? 4 : 0);

        float[] body = blockEntity.body();
        if (body == null || blockEntity.cachedKey() != key) {
            body = buildBody(seed, segment, head);
            blockEntity.setBody(key, body);
        }

        ChronoLine line = ChronoLineState.clientLine();
        double centerX = pos.getX() + 0.5D;
        double centerZ = pos.getZ() + 0.5D;
        // 方块类型决定用哪套贴图：昼侧固定昼版、夜侧固定夜版，鬼花按它所在的一侧
        boolean night =
                switch (state.getBlock()) {
                    case MoonflowerBlock moonflower -> true;
                    case MoonflowerStemBlock moonflowerStem -> true;
                    case LunarGhoflowerBlock lunarGhoflower -> true;
                    default -> false;
                };
        TextureAtlasSprite sprite = sprite(night ? NIGHT_TEXTURE : DAY_TEXTURE);
        VertexConsumer consumer = buffers.getBuffer(RenderType.cutout());

        poses.pushPose();
        // 方块实体的 pose 原点在方格的最小角，几何按「柱轴在格中心」算，所以先挪半格
        poses.translate(0.5D + SunflowerPlant.offsetX(seed), 0.0D, 0.5D + SunflowerPlant.offsetZ(seed));
        emitBody(consumer, poses.last(), body, sprite, packedLight, packedOverlay);
        if (head) {
            // 鬼花一旦锁定目标，花盘就改朝那个方向；否则按太阳或月亮摆
            Vec3 target = state.getBlock() instanceof GhoflowerBlock && blockEntity.hasAim()
                    ? blockEntity.aim()
                    : ChronoSun.headDirection(line, centerX, centerZ);
            emitHead(consumer, poses.last(), seed, target, sprite, packedLight, packedOverlay);
        }
        poses.popPose();
    }

    @Override
    public AABB getRenderBoundingBox(SunflowerBlockEntity blockEntity) {
        BlockPos pos = blockEntity.getBlockPos();
        // 花盘与最长的叶片都会伸出格子，包围盒放宽一格，免得区块边缘被整段剔掉
        return new AABB(
                pos.getX() - 0.75D,
                pos.getY(),
                pos.getZ() - 0.75D,
                pos.getX() + 1.75D,
                pos.getY() + 1.5D,
                pos.getZ() + 1.75D);
    }

    @Override
    public int getViewDistance() {
        return 96;
    }

    private static TextureAtlasSprite sprite(ResourceLocation location) {
        return Minecraft.getInstance()
                .getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                .apply(location);
    }

    /** 把缓存的顶点写进当前批次；缓存里的 UV 是整张贴图的 0~1，这里映射到 sprite 在图集里的范围 */
    private static void emitBody(
            VertexConsumer consumer,
            PoseStack.Pose pose,
            float[] body,
            TextureAtlasSprite sprite,
            int packedLight,
            int packedOverlay) {
        float u0 = sprite.getU0();
        float du = sprite.getU1() - sprite.getU0();
        float v0 = sprite.getV0();
        float dv = sprite.getV1() - sprite.getV0();
        for (int index = 0; index + STRIDE <= body.length; index += STRIDE) {
            int shade = (int) (body[index + 8] * 255.0F);
            consumer.addVertex(pose, body[index] * PIXEL, body[index + 1] * PIXEL, body[index + 2] * PIXEL)
                    .setColor(shade, shade, shade, 255)
                    .setUv(u0 + body[index + 3] * du, v0 + body[index + 4] * dv)
                    .setOverlay(packedOverlay)
                    .setLight(packedLight)
                    .setNormal(pose, body[index + 5], body[index + 6], body[index + 7]);
        }
    }

    private static float[] buildBody(long seed, int segment, boolean head) {
        Builder out = new Builder();
        float stemHeight = head ? AbstractSunflowerBlock.HEAD_STEM_HEIGHT : STEM_HEIGHT;
        stem(out, stemHeight);
        SunflowerPlant.Template template = SunflowerPlant.template(seed, segment, SunflowerLeaves.templates());
        List<SunflowerPlant.Leaf> leaves = template.leaves();
        for (int index = 0; index < leaves.size(); index++) {
            SunflowerPlant.Leaf leaf = leaves.get(index);
            float yaw = leaf.yaw() + SunflowerPlant.jitter(seed, segment, index, 0) * 12.0F;
            float pitch = leaf.pitch() + SunflowerPlant.jitter(seed, segment, index, 1) * 4.0F;
            float scale = leaf.scale() * (1.0F + SunflowerPlant.jitter(seed, segment, index, 2) * 0.08F);
            float y = Mth.clamp(
                    leaf.y() + SunflowerPlant.jitter(seed, segment, index, 3) * 1.5F,
                    2.0F,
                    Math.max(2.0F, stemHeight - 2.0F));
            leaf(out, yaw, pitch, scale, y);
        }
        return out.toArray();
    }

    /** 两片零厚度平面，各绕 Y 转 ±45 度，交叉成 X；每片正反各一面 */
    private static void stem(Builder out, float height) {
        float u0 = STEM_U / TEXTURE_SIZE;
        float u1 = (STEM_U + 2.0F) / TEXTURE_SIZE;
        float vBottom = STEM_V_BOTTOM / TEXTURE_SIZE;
        float vTop = (STEM_V_BOTTOM - height) / TEXTURE_SIZE;
        for (int side = 0; side < 2; side++) {
            double radians = Math.toRadians(side == 0 ? STEM_SKEW_DEGREES : -STEM_SKEW_DEGREES);
            Vec3 along = new Vec3(Math.cos(radians), 0.0D, Math.sin(radians));
            Vec3 corner = along.scale(STEM_HALF_WIDTH);
            Vec3 normal = new Vec3(along.z, 0.0D, -along.x);
            out.doubleQuad(
                    new Vec3(-corner.x, 0.0D, -corner.z),
                    u0,
                    vBottom,
                    new Vec3(corner.x, 0.0D, corner.z),
                    u1,
                    vBottom,
                    new Vec3(corner.x, height, corner.z),
                    u1,
                    vTop,
                    new Vec3(-corner.x, height, -corner.z),
                    u0,
                    vTop,
                    normal);
        }
    }

    /** 一片叶：根贴在柱身上，尖端朝外并稍稍下压；根在贴图 v 小端，正反各一面 */
    private static void leaf(Builder out, float yawDegrees, float pitchDegrees, float scale, float y) {
        double yaw = Math.toRadians(yawDegrees);
        double tilt = Math.toRadians(pitchDegrees);
        double cos = Math.cos(yaw);
        double sin = Math.sin(yaw);
        Vec3 outward = new Vec3(cos, 0.0D, sin);
        Vec3 side = new Vec3(-sin, 0.0D, cos);
        Vec3 root = outward.scale(STEM_HALF_WIDTH).add(0.0D, y, 0.0D);
        float halfWidth = LEAF_WIDTH * scale * 0.5F;
        float reach = (float) (LEAF_LENGTH * scale * Math.cos(tilt));
        float drop = (float) (LEAF_LENGTH * scale * Math.sin(tilt));
        Vec3 left = root.subtract(side.scale(halfWidth));
        Vec3 right = root.add(side.scale(halfWidth));
        Vec3 leftTip = left.add(outward.scale(reach)).add(0.0D, -drop, 0.0D);
        Vec3 rightTip = right.add(outward.scale(reach)).add(0.0D, -drop, 0.0D);
        Vec3 normal = outward.scale(drop).add(0.0D, reach, 0.0D).normalize();

        float u0 = LEAF_U / TEXTURE_SIZE;
        float u1 = (LEAF_U + LEAF_WIDTH) / TEXTURE_SIZE;
        float vRoot = LEAF_V_ROOT / TEXTURE_SIZE;
        float vTip = LEAF_V_TIP / TEXTURE_SIZE;
        out.doubleQuad(left, u0, vRoot, right, u1, vRoot, rightTip, u1, vTip, leftTip, u0, vTip, normal);
    }

    /**
     * 花盘：法线取 {@link ChronoSun#headDirection} 给出的方向，再叠加 ±3 度的俯仰偏置与 ±12 度的自转
     *
     * <p>两侧各一片，正面用花正面那一块、背面用花背面那一块。
     */
    private static void emitHead(
            VertexConsumer consumer,
            PoseStack.Pose pose,
            long seed,
            Vec3 target,
            TextureAtlasSprite sprite,
            int packedLight,
            int packedOverlay) {
        Vec3 normal = target.add(WORLD_UP.scale(Math.sin(Math.toRadians(SunflowerPlant.headPitchBias(seed)))))
                .normalize();
        Vec3 right = WORLD_UP.cross(normal);
        if (right.lengthSqr() < 1.0E-6D) {
            right = new Vec3(1.0D, 0.0D, 0.0D);
        }
        right = right.normalize();
        Vec3 up = normal.cross(right);
        double roll = Math.toRadians(SunflowerPlant.headRoll(seed));
        double cos = Math.cos(roll);
        double sin = Math.sin(roll);
        Vec3 rotatedRight = right.scale(cos).add(up.scale(sin));
        Vec3 rotatedUp = up.scale(cos).subtract(right.scale(sin));

        Vec3 center = new Vec3(0.0D, AbstractSunflowerBlock.HEAD_STEM_HEIGHT, 0.0D).add(normal.scale(HEAD_REACH));
        Vec3 front = center.add(normal.scale(HEAD_THICKNESS));
        Vec3 back = center.subtract(normal.scale(HEAD_THICKNESS));
        Vec3 a = front.add(rotatedRight.scale(-HEAD_HALF)).add(rotatedUp.scale(-HEAD_HALF));
        Vec3 b = front.add(rotatedRight.scale(HEAD_HALF)).add(rotatedUp.scale(-HEAD_HALF));
        Vec3 c = front.add(rotatedRight.scale(HEAD_HALF)).add(rotatedUp.scale(HEAD_HALF));
        Vec3 d = front.add(rotatedRight.scale(-HEAD_HALF)).add(rotatedUp.scale(HEAD_HALF));
        Vec3 e = back.add(rotatedRight.scale(-HEAD_HALF)).add(rotatedUp.scale(-HEAD_HALF));
        Vec3 f = back.add(rotatedRight.scale(HEAD_HALF)).add(rotatedUp.scale(-HEAD_HALF));
        Vec3 g = back.add(rotatedRight.scale(HEAD_HALF)).add(rotatedUp.scale(HEAD_HALF));
        Vec3 h = back.add(rotatedRight.scale(-HEAD_HALF)).add(rotatedUp.scale(HEAD_HALF));

        float fu0 = mapU(sprite, FACE_U);
        float fu1 = mapU(sprite, FACE_U + HEAD_TEXTURE_SIZE);
        float fv0 = mapV(sprite, FACE_V);
        float fv1 = mapV(sprite, FACE_V + HEAD_TEXTURE_SIZE);
        float bu0 = mapU(sprite, BACK_U);
        float bu1 = mapU(sprite, BACK_U + HEAD_TEXTURE_SIZE);
        float bv0 = mapV(sprite, BACK_V);
        float bv1 = mapV(sprite, BACK_V + HEAD_TEXTURE_SIZE);

        vertex(consumer, pose, a, fu0, fv1, normal, packedLight, packedOverlay);
        vertex(consumer, pose, b, fu1, fv1, normal, packedLight, packedOverlay);
        vertex(consumer, pose, c, fu1, fv0, normal, packedLight, packedOverlay);
        vertex(consumer, pose, d, fu0, fv0, normal, packedLight, packedOverlay);
        // 背面同样按「v 小端是上」贴：h、g 在上，f、e 在下，从背面看左右是反的
        Vec3 backward = normal.scale(-1.0D);
        vertex(consumer, pose, h, bu1, bv0, backward, packedLight, packedOverlay);
        vertex(consumer, pose, g, bu0, bv0, backward, packedLight, packedOverlay);
        vertex(consumer, pose, f, bu0, bv1, backward, packedLight, packedOverlay);
        vertex(consumer, pose, e, bu1, bv1, backward, packedLight, packedOverlay);
    }

    private static float mapU(TextureAtlasSprite sprite, float pixels) {
        return sprite.getU0() + pixels / TEXTURE_SIZE * (sprite.getU1() - sprite.getU0());
    }

    private static float mapV(TextureAtlasSprite sprite, float pixels) {
        return sprite.getV0() + pixels / TEXTURE_SIZE * (sprite.getV1() - sprite.getV0());
    }

    private static void vertex(
            VertexConsumer consumer,
            PoseStack.Pose pose,
            Vec3 point,
            float u,
            float v,
            Vec3 normal,
            int packedLight,
            int packedOverlay) {
        int level = (int) (shade(normal) * 255.0F);
        consumer.addVertex(pose, (float) point.x * PIXEL, (float) point.y * PIXEL, (float) point.z * PIXEL)
                .setColor(level, level, level, 255)
                .setUv(u, v)
                .setOverlay(packedOverlay)
                .setLight(packedLight)
                .setNormal(pose, (float) normal.x, (float) normal.y, (float) normal.z);
    }

    /** 顶面亮、侧面暗，避免整片同色显得扁平 */
    private static float shade(Vec3 normal) {
        return 0.6F + 0.4F * (float) Math.max(0.0D, normal.y);
    }

    /** 顶点收集器，每顶点 {@link #STRIDE} 个 float */
    private static final class Builder {
        private float[] data = new float[512];

        private int size;

        /** 一片双面平面：正反各一个四边形，反面的顶点顺序与法线都取反 */
        private void doubleQuad(
                Vec3 p0,
                float u0,
                float v0,
                Vec3 p1,
                float u1,
                float v1,
                Vec3 p2,
                float u2,
                float v2,
                Vec3 p3,
                float u3,
                float v3,
                Vec3 normal) {
            quad(p0, u0, v0, p1, u1, v1, p2, u2, v2, p3, u3, v3, normal);
            quad(p3, u3, v3, p2, u2, v2, p1, u1, v1, p0, u0, v0, normal.scale(-1.0D));
        }

        private void quad(
                Vec3 p0,
                float u0,
                float v0,
                Vec3 p1,
                float u1,
                float v1,
                Vec3 p2,
                float u2,
                float v2,
                Vec3 p3,
                float u3,
                float v3,
                Vec3 normal) {
            float shade = shade(normal);
            vertex(p0, u0, v0, normal, shade);
            vertex(p1, u1, v1, normal, shade);
            vertex(p2, u2, v2, normal, shade);
            vertex(p3, u3, v3, normal, shade);
        }

        private void vertex(Vec3 point, float u, float v, Vec3 normal, float shade) {
            ensure(STRIDE);
            this.data[this.size++] = (float) point.x;
            this.data[this.size++] = (float) point.y;
            this.data[this.size++] = (float) point.z;
            this.data[this.size++] = u;
            this.data[this.size++] = v;
            this.data[this.size++] = (float) normal.x;
            this.data[this.size++] = (float) normal.y;
            this.data[this.size++] = (float) normal.z;
            this.data[this.size++] = shade;
        }

        private void ensure(int extra) {
            if (this.size + extra <= this.data.length) {
                return;
            }
            float[] grown = new float[this.data.length * 2];
            System.arraycopy(this.data, 0, grown, 0, this.size);
            this.data = grown;
        }

        private float[] toArray() {
            float[] result = new float[this.size];
            System.arraycopy(this.data, 0, result, 0, this.size);
            return result;
        }
    }
}
