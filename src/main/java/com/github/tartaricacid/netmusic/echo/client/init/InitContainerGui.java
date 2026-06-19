
package com.github.tartaricacid.netmusic.echo.client.init;

import com.github.tartaricacid.netmusic.echo.client.gui.EchoSearcherScreen;
import com.github.tartaricacid.netmusic.echo.init.InitContainer;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public class InitContainerGui {
    @SubscribeEvent
    public static void onMenuScreenEvent(FMLClientSetupEvent evt) {
        evt.enqueueWork(() -> MenuScreens.register(InitContainer.ECHO_SEARCHER.get(), EchoSearcherScreen::new));
    }
}
