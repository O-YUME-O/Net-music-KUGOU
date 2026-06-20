
package com.github.tartaricacid.netmusic.echo.client.init;

import com.github.tartaricacid.netmusic.echo.client.gui.EchoSearcherScreen;
import com.github.tartaricacid.netmusic.echo.init.InitContainer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@EventBusSubscriber(value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public class InitContainerGui {
    @SubscribeEvent
    public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(InitContainer.ECHO_SEARCHER.get(), EchoSearcherScreen::new);
    }
}
