package com.dawnduskterminal.structure;

import com.dawnduskterminal.DawnDuskTerminal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/** 单次生成持有目录代次；可变模板的家具判断不跨生成复用。 */
public final class GridTemplateCatalog {
    /** 由模板管理器上的 mixin 提供所有权，不使用进程级管理器索引。 */
    public interface Owner {
        Resources ddt$templateResources();
    }

    /** 管理器当前资源代次的目录与诊断；替换代次后不再接收旧任务的写入。 */
    public static final class Resources {
        private List<ResourceLocation> ids;
        private final Map<String, List<ResourceLocation>> variants = new ConcurrentHashMap<>();
        private final Set<ResourceLocation> missing = ConcurrentHashMap.newKeySet();
        private final Set<String> partial = ConcurrentHashMap.newKeySet();

        private synchronized List<ResourceLocation> ids(StructureTemplateManager manager) {
            if (ids == null) {
                try (var stream = manager.listTemplates()) {
                    ids = stream.filter(id -> id.getNamespace().equals(DawnDuskTerminal.MOD_ID))
                            .sorted()
                            .toList();
                }
            }
            return ids;
        }

        private List<ResourceLocation> variants(StructureTemplateManager manager, String prefix) {
            return variants.computeIfAbsent(prefix, key -> ids(manager).stream()
                    .filter(id -> id.getPath().startsWith(key))
                    .toList());
        }
    }

    private final StructureTemplateManager manager;
    private final Resources resources;
    private final Map<ResourceLocation, Boolean> border = new HashMap<>();
    final Set<ResourceLocation> missing;
    final Set<String> partial;

    GridTemplateCatalog(StructureTemplateManager manager) {
        this.manager = manager;
        this.resources = ((Owner) manager).ddt$templateResources();
        this.missing = resources.missing;
        this.partial = resources.partial;
    }

    List<ResourceLocation> variants(String prefix) {
        return resources.variants(manager, prefix);
    }

    /** 仅检查模板范围内的边框，不计四角原木；模板完整保存的非空气边框方块视为家具。 */
    boolean hasBorderFurniture(ResourceLocation id) {
        return border.computeIfAbsent(id, key -> {
            StructureTemplate template = manager.get(key).orElse(null);
            if (template == null) {
                return false;
            }
            Vec3i size = template.getSize();
            int top = Math.min(4, size.getY() - 1);
            List<StructureTemplate.StructureBlockInfo> blocks =
                    template.filterBlocks(BlockPos.ZERO, new StructurePlaceSettings(), Blocks.AIR);
            Set<BlockPos> air = HashSet.newHashSet(blocks.size());
            for (StructureTemplate.StructureBlockInfo info : blocks) {
                BlockPos pos = info.pos();
                if (pos.getY() < 1 || pos.getY() > top) {
                    continue;
                }
                boolean edgeX = pos.getX() == 0 || pos.getX() == size.getX() - 1;
                boolean edgeZ = pos.getZ() == 0 || pos.getZ() == size.getZ() - 1;
                if (edgeX != edgeZ) {
                    air.add(pos);
                }
            }
            BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
            for (int y = 1; y <= top; y++) {
                for (int x = 0; x < size.getX(); x++) {
                    for (int z = 0; z < size.getZ(); z++) {
                        boolean edgeX = x == 0 || x == size.getX() - 1;
                        boolean edgeZ = z == 0 || z == size.getZ() - 1;
                        if (edgeX == edgeZ) {
                            continue;
                        }
                        if (!air.contains(probe.set(x, y, z))) {
                            return true;
                        }
                    }
                }
            }
            return false;
        });
    }
}
