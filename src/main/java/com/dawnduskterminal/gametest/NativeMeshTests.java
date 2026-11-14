package com.dawnduskterminal.gametest;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.client.NativeMesh;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** JNI 故障会熔断进程，因此该检查使用独立命名空间单独运行。 */
@GameTestHolder("dawnduskterminalnative")
@PrefixGameTestTemplate(false)
public final class NativeMeshTests {
    private NativeMeshTests() {}

    @GameTest(template = "ddt_empty")
    public static void invalidatedHandleDoesNotWriteOutputAndTripsCircuit(GameTestHelper helper) {
        helper.assertTrue(
                NativeMesh.isAvailable(),
                "native fault injection requires a working kernel: " + NativeMesh.failureDescription());
        long pool = NativeMesh.createPool(1);
        long mesh = 0;
        try {
            mesh = NativeMesh.createMesh(
                    new float[] {0, 0, 0, 0, 1, 0, 0, 0}, new int[] {0}, new int[] {1}, new int[] {0});
            ByteBuffer matrices = direct(16 * Float.BYTES);
            ByteBuffer normals = direct(9 * Float.BYTES);
            ByteBuffer ids = direct(Integer.BYTES);
            ByteBuffer output = direct(8 * Float.BYTES);
            for (int index = 0; index < output.capacity(); index++) {
                output.put(index, (byte) 0x5A);
            }
            // 模拟收集完成后句柄失效，验证真实 JNI 边界先拒绝调用，再允许 Java 路径重试。
            NativeMesh.destroyMesh(mesh);
            boolean failed = false;
            try {
                NativeMesh.transform(pool, mesh, matrices, normals, ids, 1, output);
            } catch (NativeMesh.NativeMeshException expected) {
                failed = true;
            }
            helper.assertTrue(failed, "invalidated handles must reach the Java fallback boundary");
            helper.assertFalse(NativeMesh.isAvailable(), "a runtime failure must disable future native submissions");
            for (int index = 0; index < output.capacity(); index++) {
                helper.assertTrue(
                        output.get(index) == (byte) 0x5A, "a rejected submission must not write partial vertices");
            }
            DawnDuskTerminal.LOGGER.info("Native fault injection passed: output untouched and circuit disabled");
        } finally {
            NativeMesh.destroyMesh(mesh);
            NativeMesh.destroyPool(pool);
        }
        helper.succeed();
    }

    private static ByteBuffer direct(int bytes) {
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
    }
}
