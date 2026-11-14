package com.dawnduskterminal.registry;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.portal.PortalState;
import com.dawnduskterminal.progression.BossProgress;
import java.util.function.Supplier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public final class ModAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, DawnDuskTerminal.MOD_ID);

    public static final Supplier<AttachmentType<BossProgress>> BOSS_PROGRESS =
            ATTACHMENTS.register("boss_progress", () -> AttachmentType.builder(BossProgress::empty)
                    .serialize(BossProgress.CODEC)
                    .copyOnDeath()
                    .build());

    public static final Supplier<AttachmentType<PortalState>> PORTAL_STATE =
            ATTACHMENTS.register("portal_state", () -> AttachmentType.builder(PortalState::empty)
                    .serialize(PortalState.CODEC)
                    .copyOnDeath()
                    .build());

    /** 动画由实体持有；不参与存档、同步或死亡复制，也不引用客户端类。 */
    public static final Supplier<AttachmentType<ClientAnimation>> CLIENT_ANIMATION =
            ATTACHMENTS.register("client_animation", () -> AttachmentType.<ClientAnimation>builder(() -> {
                        throw new IllegalStateException("Client animation must be assigned by its renderer");
                    })
                    .build());

    public record ClientAnimation(Object renderer, Object animation) {}

    private ModAttachments() {}

    public static void register(IEventBus bus) {
        ATTACHMENTS.register(bus);
    }
}
