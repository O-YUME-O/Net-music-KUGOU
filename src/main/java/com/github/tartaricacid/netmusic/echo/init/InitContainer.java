package com.github.tartaricacid.netmusic.echo.init;

import com.github.tartaricacid.netmusic.echo.NetMusicEchoAddon;
import com.github.tartaricacid.netmusic.echo.inventory.EchoSearcherMenu;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 1.21.1 改造：{@code ForgeRegistries.MENU_TYPES} → {@code BuiltInRegistries.MENU}，
 * {@code IForgeMenuType.create(...)} → {@code IMenuTypeExtension.create(...)}。
 */
public class InitContainer {
    public static final DeferredRegister<MenuType<?>> CONTAINER_TYPES =
            DeferredRegister.create(BuiltInRegistries.MENU, NetMusicEchoAddon.MOD_ID);

    public static final DeferredHolder<MenuType<?>, MenuType<EchoSearcherMenu>> ECHO_SEARCHER =
            CONTAINER_TYPES.register("echo_searcher",
                    () -> IMenuTypeExtension.create((windowId, inv, data) -> new EchoSearcherMenu(windowId, inv)));

    public static void init(IEventBus eventBus) {
        CONTAINER_TYPES.register(eventBus);
    }
}
