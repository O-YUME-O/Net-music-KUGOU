
package com.github.tartaricacid.netmusic.echo.init;

import com.github.tartaricacid.netmusic.echo.NetMusicEchoAddon;
import com.github.tartaricacid.netmusic.echo.block.BlockEchoSearcher;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class InitBlocks {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, NetMusicEchoAddon.MOD_ID);

    public static final RegistryObject<Block> ECHO_SEARCHER = BLOCKS.register("echo_searcher",
            () -> new BlockEchoSearcher(
                    BlockBehaviour.Properties.of().strength(2.0F, 3.0F)));

    public static void init(IEventBus eventBus) {
        BLOCKS.register(eventBus);
    }
}
