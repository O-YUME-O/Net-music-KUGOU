package com.github.tartaricacid.netmusic.echo.block;

import com.github.tartaricacid.netmusic.echo.NetMusicEchoAddon;
import com.github.tartaricacid.netmusic.echo.init.InitBlocks;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 1.21.1 改造：{@code ForgeRegistries.BLOCK_ENTITY_TYPES} → {@code BuiltInRegistries.BLOCK_ENTITY_TYPE}。
 * <p>本注册器只为 {@link BlockEchoSearcher} 提供占位 BlockEntity 类型（1.21 的
 * {@code BaseEntityBlock} 要求非 null 的 newBlockEntity），并不存储任何服务端数据。
 */
public class InitBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, NetMusicEchoAddon.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BlockEchoSearcher.EchoSearcherBlockEntity>> ECHO_SEARCHER_TYPE =
            BLOCK_ENTITY_TYPES.register("echo_searcher", () ->
                    BlockEntityType.Builder.of(BlockEchoSearcher.EchoSearcherBlockEntity::new, InitBlocks.ECHO_SEARCHER.get()).build(null));

    public static void init(IEventBus eventBus) {
        BLOCK_ENTITY_TYPES.register(eventBus);
    }
}
