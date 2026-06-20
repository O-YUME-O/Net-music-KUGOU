
package com.github.tartaricacid.netmusic.echo.init;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

@EventBusSubscriber(bus = EventBusSubscriber.Bus.MOD)
public class CommonRegistry {
    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
    }
}
