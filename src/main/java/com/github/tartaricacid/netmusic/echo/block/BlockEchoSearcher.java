
package com.github.tartaricacid.netmusic.echo.block;

import com.github.tartaricacid.netmusic.echo.inventory.EchoSearcherMenu;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class BlockEchoSearcher extends BaseEntityBlock {
    public static final MapCodec<BlockEchoSearcher> CODEC = simpleCodec(BlockEchoSearcher::new);

    public BlockEchoSearcher(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState blockState, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        } else {
            player.openMenu(blockState.getMenuProvider(level, pos));
            player.awardStat(Stats.INTERACT_WITH_GRINDSTONE);
            return InteractionResult.CONSUME;
        }
    }

    @Override
    public @Nullable MenuProvider getMenuProvider(BlockState blockState, Level level, BlockPos blockPos) {
        return new SimpleMenuProvider((id, inventory, player) -> new EchoSearcherMenu(id, inventory), Component.translatable("block.netmusic_echo_addon.echo_searcher"));
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("block.netmusic_echo_addon.echo_searcher.desc"));
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        // 不需要任何服务端状态（菜单开/关、烧录进度都走 BlockEntity 之外的容器），
        // 但 1.21 要求 BaseEntityBlock 必须有 BlockEntity，所以返回一个空的 dummy。
        return new EchoSearcherBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    /**
     * 仅作占位：BlockEchoSearcher 不需要持久化任何服务端状态，
     * 但 1.21 的 BaseEntityBlock 要求 newBlockEntity() 返回非 null。
     */
    public static class EchoSearcherBlockEntity extends BlockEntity {
        public EchoSearcherBlockEntity(BlockPos pos, BlockState state) {
            super(InitBlockEntities.ECHO_SEARCHER_TYPE.get(), pos, state);
        }
    }
}
