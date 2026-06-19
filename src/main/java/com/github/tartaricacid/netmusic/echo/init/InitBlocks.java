package com.github.tartaricacid.netmusic.echo.init;

import com.github.tartaricacid.netmusic.echo.NetMusicEchoAddon;
import com.github.tartaricacid.netmusic.echo.block.BlockEchoSearcher;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 1.21.1 改造：Forge {@code ForgeRegistries.BLOCKS} 已被
 * NeoForge 1.21+ 的 {@code BuiltInRegistries.BLOCK} 取代。
 * 同时 {@code RegistryObject} 改为 {@code DeferredHolder<Block, Block>}。
 */
public class InitBlocks {
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(BuiltInRegistries.BLOCK, NetMusicEchoAddon.MOD_ID);

    public static final DeferredHolder<Block, Block> ECHO_SEARCHER = BLOCKS.register("echo_searcher",
            () -> new BlockEchoSearcher(
                    BlockBehaviour.Properties.of().strength(2.0F, 3.0F)));

    public static void init(IEventBus eventBus) {
        BLOCKS.register(eventBus);
    }
}
