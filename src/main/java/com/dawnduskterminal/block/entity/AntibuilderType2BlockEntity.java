package com.dawnduskterminal.block.entity;

import com.dawnduskterminal.registry.ModBlockEntities;
import com.dawnduskterminal.world.AntibuilderType2Field;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 反建器二型的方块实体：只为了把场心登记进 {@link AntibuilderType2Field}
 *
 * <p>没有数据、没有界面、服务端也没有 ticker：半径那一条状态挂在方块上，方块实体不另存一份，
 * 于是场每次判定都读方块状态本身，不存在两份数值对不上的可能。区块载入时 {@link #onLoad} 登记，
 * 区块卸载时 {@link #setRemoved} 撤掉；摆下与挖掉那两次由方块的 {@code onPlace} / {@code onRemove}
 * 当场做掉，所以方块落地就生效
 *
 * <p>客户端那颗 ticker 挂在方块的 {@code getTicker} 上，只负责把场的立方框画出来，与本类的数据无关，
 * 见 {@code world/CubeFieldMarker}
 */
public class AntibuilderType2BlockEntity extends BlockEntity {
    public AntibuilderType2BlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ANTIBUILDER_TYPE2.get(), pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        AntibuilderType2Field.track(this.level, this.worldPosition);
    }

    @Override
    public void setRemoved() {
        AntibuilderType2Field.untrack(this.level, this.worldPosition);
        super.setRemoved();
    }
}
