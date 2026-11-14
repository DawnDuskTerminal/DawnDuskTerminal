package com.dawnduskterminal.gametest;

import com.dawnduskterminal.registry.ModAttachments;
import com.dawnduskterminal.registry.ModEntities;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** 动画附件不依赖客户端类，专用服务器也必须能够注册并检查其所有权。 */
@GameTestHolder("dawnduskterminal")
@PrefixGameTestTemplate(false)
public final class AnimationAttachmentTests {
    private AnimationAttachmentTests() {}

    @GameTest(template = "ddt_empty")
    public static void animationIsEntityOwnedAndNotPersistent(GameTestHelper helper) {
        for (Entity entity : new Entity[] {
            ModEntities.GHAST_ELITE.get().create(helper.getLevel()),
            ModEntities.EXPOWHEEL.get().create(helper.getLevel()),
            ModEntities.EXPERIMENT_057_BLADE.get().create(helper.getLevel())
        }) {
            helper.assertTrue(entity != null, "the model entity must be constructible");
            var type = ModAttachments.CLIENT_ANIMATION.get();
            helper.assertTrue(entity.getExistingDataOrNull(type) == null, "animation must be lazy");
            Object renderer = new Object();
            // 模拟动画反向持有实体；引用只存在于实体自身的附件中，不建立全局实体索引。
            var state = new ModAttachments.ClientAnimation(renderer, entity);
            entity.setData(type, state);
            helper.assertTrue(entity.getExistingDataOrNull(type) == state, "the same entity must retain its animation");
            var saved = entity.serializeAttachments(helper.getLevel().registryAccess());
            helper.assertTrue(
                    saved == null || !saved.contains("dawnduskterminal:client_animation"),
                    "client animation must never be serialised");
            var replacement = new ModAttachments.ClientAnimation(new Object(), new Object());
            entity.setData(type, replacement);
            helper.assertTrue(
                    entity.getExistingDataOrNull(type) == replacement, "renderer reload must replace old state");
            helper.assertTrue(
                    entity.removeData(type) == replacement, "removing state must release its owner reference");
        }
        helper.succeed();
    }
}
