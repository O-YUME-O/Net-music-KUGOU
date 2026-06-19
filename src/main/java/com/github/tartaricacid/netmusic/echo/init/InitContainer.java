
package com.github.tartaricacid.netmusic.echo.init;

import com.github.tartaricacid.netmusic.echo.NetMusicEchoAddon;
import com.github.tartaricacid.netmusic.echo.inventory.EchoSearcherMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class InitContainer {
    public static final DeferredRegister<MenuType<?>> CONTAINER_TYPES = DeferredRegister.create(ForgeRegistries.MENU_TYPES, NetMusicEchoAddon.MOD_ID);

    public static final RegistryObject<MenuType<EchoSearcherMenu>> ECHO_SEARCHER = CONTAINER_TYPES.register("echo_searcher",
            () -> IForgeMenuType.create((windowId, inv, data) -> new EchoSearcherMenu(windowId, inv)));

    public static void init(IEventBus eventBus) {
        CONTAINER_TYPES.register(eventBus);
    }
}
