package com.dawnduskterminal.client.animation.functions;

import com.dawnduskterminal.client.animation.struct.Vec3fStruct;
import com.dawnduskterminal.geckolib3.core.molang.context.IContext;
import com.dawnduskterminal.geckolib3.core.molang.function.entity.EntityFunction;
import com.dawnduskterminal.geckolib3.core.processor.AnimationProcessor;
import com.dawnduskterminal.geckolib3.core.processor.IBone;
import com.dawnduskterminal.geckolib3.core.processor.ILocationBone;
import com.dawnduskterminal.geckolib3.geo.animated.AnimatedGeoBone;
import com.dawnduskterminal.geckolib3.geo.render.built.GeoBone;
import com.dawnduskterminal.molang.runtime.ExecutionContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * {@code ysm.bone_pivot_abs(骨骼名)}：骨骼当前的绝对 pivot，Bedrock 坐标
 *
 * <p>这不是静态值。第 2、3 段的 pivot 依赖上一段自己的旋转，而模型正是靠这条反馈做布条与触手
 * 的链式回复力（源表达式每帧读它当作「质点的当前位置」）。若返回静止 pivot，整条链会退化成
 * 一块刚性平面；若返回 0（官方 3.0-dev 与 OpenYSM 的 `bone_pivot_abs` 都是占位实现），
 * 辅助骨骼的基矩阵会变成反射，触手翻到 90~150 度。
 *
 * <p>x 不取负：YSM 内部骨骼空间是镜像的，Molang 访问器负责换回作者空间（`ContextReads#boneRotation`
 * 对 X、Y 取负即可见），`bone_pivot_abs` 属于同一族。源表达式自己写了 `-ysm.bone_pivot_abs(...).x`。
 *
 * <p>按渲染器相同的局部变换从根到目标骨骼组合矩阵，包含父骨骼的旋转、缩放与动画位置偏移。
 * 返回目标骨骼的 pivot，而不应用目标骨骼自己的旋转与缩放。
 *
 * <p>每次调用都重算，不做缓存：模型骨骼链较短，而这些通道每帧只有几百次。
 */
public class BonePivotAbs extends EntityFunction {
    @Override
    protected Object eval(ExecutionContext<IContext<Entity>> context, ArgumentCollection arguments) {
        String name = arguments.getAsString(context, 0);
        if (name == null || name.isEmpty()) {
            return null;
        }
        AnimationProcessor<?> processor = context.entity().geoInstance().getAnimationProcessor();
        if (!(processor.getBone(name) instanceof AnimatedGeoBone animated)) {
            return null;
        }

        List<ILocationBone> chain = new ArrayList<>();
        for (GeoBone bone = animated.geoBone(); bone != null; bone = bone.parent()) {
            IBone current = processor.getBone(bone.name());
            if (current == null) {
                return null;
            }
            chain.add(current);
        }
        Collections.reverse(chain);
        Vector3f pivot = absolutePivot(chain);
        return new PivotStruct(pivot.x, pivot.y, pivot.z);
    }

    @Override
    public boolean validateArgumentSize(int size) {
        return size == 1;
    }

    /** 骨骼链顺序为根到目标；坐标输入与 {@code RenderUtils.prepMatrixForBone} 一致 */
    static Vector3f absolutePivot(List<? extends ILocationBone> chain) {
        if (chain.isEmpty()) {
            throw new IllegalArgumentException("骨骼链不能为空");
        }
        Matrix4f transform = new Matrix4f();
        for (int index = 0; index < chain.size() - 1; index++) {
            applyBoneTransform(transform, chain.get(index));
        }

        ILocationBone target = chain.get(chain.size() - 1);
        transform.translate(
                -target.getPositionX() / 16.0F, target.getPositionY() / 16.0F, target.getPositionZ() / 16.0F);
        Vector3f pivot = transform.transformPosition(
                target.getPivotX() / 16.0F, target.getPivotY() / 16.0F, target.getPivotZ() / 16.0F, new Vector3f());
        return pivot.mul(-16.0F, 16.0F, 16.0F);
    }

    private static void applyBoneTransform(Matrix4f transform, ILocationBone bone) {
        float pivotX = bone.getPivotX() / 16.0F;
        float pivotY = bone.getPivotY() / 16.0F;
        float pivotZ = bone.getPivotZ() / 16.0F;
        transform.translate(-bone.getPositionX() / 16.0F, bone.getPositionY() / 16.0F, bone.getPositionZ() / 16.0F);
        transform.translate(pivotX, pivotY, pivotZ);
        transform.rotateZYX(bone.getRotationZ(), bone.getRotationY(), bone.getRotationX());
        transform.scale(bone.getScaleX(), bone.getScaleY(), bone.getScaleZ());
        transform.translate(-pivotX, -pivotY, -pivotZ);
    }

    private static final class PivotStruct extends Vec3fStruct {
        private final double x;
        private final double y;
        private final double z;

        private PivotStruct(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        protected float getX() {
            return (float) this.x;
        }

        @Override
        protected float getY() {
            return (float) this.y;
        }

        @Override
        protected float getZ() {
            return (float) this.z;
        }
    }
}
