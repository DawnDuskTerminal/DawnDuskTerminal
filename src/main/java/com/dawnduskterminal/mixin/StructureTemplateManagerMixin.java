package com.dawnduskterminal.mixin;

import com.dawnduskterminal.structure.GridTemplateCatalog;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 目录与诊断缓存随管理器及其资源代次生存，不干预原版模板的读取与保存。 */
@Mixin(StructureTemplateManager.class)
public abstract class StructureTemplateManagerMixin implements GridTemplateCatalog.Owner {
    @Unique
    private volatile GridTemplateCatalog.Resources ddt$templateResources;

    @Override
    public GridTemplateCatalog.Resources ddt$templateResources() {
        return ddt$templateResources;
    }

    @Inject(
            method = {"<init>", "onResourceManagerReload", "remove"},
            at = @At("RETURN"))
    private void ddt$replaceTemplateResources(CallbackInfo ci) {
        ddt$templateResources = new GridTemplateCatalog.Resources();
    }

    // getOrCreate 也是普通读取入口；只有缺失分支实际加入模板时才更换代次。
    @Inject(
            method = "getOrCreate",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                            shift = At.Shift.AFTER,
                            remap = false))
    private void ddt$invalidateCreatedTemplate(ResourceLocation id, CallbackInfoReturnable<StructureTemplate> cir) {
        ddt$templateResources = new GridTemplateCatalog.Resources();
    }

    @Inject(method = "save", at = @At("RETURN"))
    private void ddt$invalidateSavedDirectory(ResourceLocation id, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            ddt$templateResources = new GridTemplateCatalog.Resources();
        }
    }
}
