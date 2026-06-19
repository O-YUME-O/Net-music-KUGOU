
package com.github.tartaricacid.netmusic.echo.network;

import com.github.tartaricacid.netmusic.echo.EchoLogger;
import com.github.tartaricacid.netmusic.echo.NetMusicEchoAddon;
import com.github.tartaricacid.netmusic.echo.network.message.AddCdRefreshInfoMessage;
import com.github.tartaricacid.netmusic.echo.network.message.EchoSearchMessage;
import com.github.tartaricacid.netmusic.echo.network.message.EchoBurnMessage;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public class NetworkHandler {
    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(NetMusicEchoAddon.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    public static void init() {
        int id = 0;
        CHANNEL.registerMessage(id++, EchoSearchMessage.class, EchoSearchMessage::encode, EchoSearchMessage::decode, EchoSearchMessage::handle);
        CHANNEL.registerMessage(id++, EchoBurnMessage.class, EchoBurnMessage::encode, EchoBurnMessage::decode, EchoBurnMessage::handle);
        CHANNEL.registerMessage(id++, AddCdRefreshInfoMessage.class, AddCdRefreshInfoMessage::encode, AddCdRefreshInfoMessage::decode, AddCdRefreshInfoMessage::handle);
        EchoLogger.info("Network Handler initialized!");
    }
}
