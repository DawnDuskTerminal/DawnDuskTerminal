package com.dawnduskterminal.client.animation.functions;

import com.dawnduskterminal.geckolib3.core.processor.ILocationBone;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3f;

/** 验证 YSM 绝对 pivot 与渲染器的骨骼层级变换一致 */
@GameTestHolder("dawnduskterminal")
@PrefixGameTestTemplate(false)
public final class BonePivotAbsTests {
    private static final float EPSILON = 1.0E-4F;

    private BonePivotAbsTests() {}

    @GameTest(template = "ddt_empty")
    public static void absolutePivotUsesRenderHierarchy(GameTestHelper helper) {
        TestBone root = new TestBone()
                .positionX(16.0F)
                .rotationX((float) (Math.PI / 2.0D))
                .scaleX(2.0F);
        TestBone child = new TestBone().rotationZ((float) (Math.PI / 2.0D));
        TestBone target = new TestBone().pivot(0.0F, 16.0F, 0.0F);

        Vector3f nested = BonePivotAbs.absolutePivot(List.of(root, child, target));
        assertNear(helper, "nested x", nested.x, 48.0F);
        assertNear(helper, "nested y", nested.y, 0.0F);
        assertNear(helper, "nested z", nested.z, 0.0F);

        TestBone single = new TestBone()
                .pivot(-8.0F, 12.0F, 4.0F)
                .rotationX(1.2F)
                .rotationY(-0.7F)
                .rotationZ(0.4F)
                .scale(3.0F, 2.0F, 4.0F);
        Vector3f ownPivot = BonePivotAbs.absolutePivot(List.of(single));
        assertNear(helper, "target pivot x", ownPivot.x, 8.0F);
        assertNear(helper, "target pivot y", ownPivot.y, 12.0F);
        assertNear(helper, "target pivot z", ownPivot.z, 4.0F);
        helper.succeed();
    }

    private static void assertNear(GameTestHelper helper, String label, float actual, float expected) {
        helper.assertTrue(Math.abs(actual - expected) <= EPSILON, label + ": expected " + expected + ", got " + actual);
    }

    private static final class TestBone implements ILocationBone {
        private float rotationX;
        private float rotationY;
        private float rotationZ;
        private float positionX;
        private float positionY;
        private float positionZ;
        private float scaleX = 1.0F;
        private float scaleY = 1.0F;
        private float scaleZ = 1.0F;
        private float pivotX;
        private float pivotY;
        private float pivotZ;

        private TestBone rotationX(float value) {
            this.rotationX = value;
            return this;
        }

        private TestBone rotationY(float value) {
            this.rotationY = value;
            return this;
        }

        private TestBone rotationZ(float value) {
            this.rotationZ = value;
            return this;
        }

        private TestBone positionX(float value) {
            this.positionX = value;
            return this;
        }

        private TestBone scaleX(float value) {
            this.scaleX = value;
            return this;
        }

        private TestBone scale(float x, float y, float z) {
            this.scaleX = x;
            this.scaleY = y;
            this.scaleZ = z;
            return this;
        }

        private TestBone pivot(float x, float y, float z) {
            this.pivotX = x;
            this.pivotY = y;
            this.pivotZ = z;
            return this;
        }

        @Override
        public float getRotationX() {
            return this.rotationX;
        }

        @Override
        public float getRotationY() {
            return this.rotationY;
        }

        @Override
        public float getRotationZ() {
            return this.rotationZ;
        }

        @Override
        public float getPositionX() {
            return this.positionX;
        }

        @Override
        public float getPositionY() {
            return this.positionY;
        }

        @Override
        public float getPositionZ() {
            return this.positionZ;
        }

        @Override
        public float getScaleX() {
            return this.scaleX;
        }

        @Override
        public float getScaleY() {
            return this.scaleY;
        }

        @Override
        public float getScaleZ() {
            return this.scaleZ;
        }

        @Override
        public float getPivotX() {
            return this.pivotX;
        }

        @Override
        public float getPivotY() {
            return this.pivotY;
        }

        @Override
        public float getPivotZ() {
            return this.pivotZ;
        }
    }
}
