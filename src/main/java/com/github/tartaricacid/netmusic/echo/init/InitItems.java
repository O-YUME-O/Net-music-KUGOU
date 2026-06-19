package com.github.tartaricacid.netmusic.echo.init;

import com.github.tartaricacid.netmusic.echo.NetMusicEchoAddon;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 1.21.1 改造：{@code ForgeRegistries.ITEMS} → {@code BuiltInRegistries.ITEM}，
 * {@code RegistryObject<Item>} → {@code DeferredHolder<Item, Item>}。
 */
public class InitItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(BuiltInRegistries.ITEM, NetMusicEchoAddon.MOD_ID);

    public static final DeferredHolder<Item, Item> ECHO_SEARCHER = ITEMS.register("echo_searcher",
            () -> new BlockItem(InitBlocks.ECHO_SEARCHER.get(), new Item.Properties()));

    public static void init(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}
