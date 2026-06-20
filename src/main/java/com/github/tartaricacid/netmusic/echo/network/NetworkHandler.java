package com.github.tartaricacid.netmusic.echo.network;

import com.github.tartaricacid.netmusic.echo.EchoLogger;
import com.github.tartaricacid.netmusic.echo.NetMusicEchoAddon;
import com.github.tartaricacid.netmusic.echo.network.message.AddCdRefreshInfoMessage;
import com.github.tartaricacid.netmusic.echo.network.message.EchoBurnMessage;
import com.github.tartaricacid.netmusic.echo.network.message.EchoSearchMessage;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 1.21.1 NeoForge 网络注册入口。
 * <p>
 * 旧版（1.20.1 Forge）使用 {@code SimpleChannel.registerMessage(...)}，
 * 1.21.1 改为在 mod 事件总线上监听
 * {@link RegisterPayloadHandlersEvent}，通过 {@link PayloadRegistrar} 注册
 * {@code CustomPacketPayload}。
 */
public class NetworkHandler {
    private static final String PROTOCOL_VERSION = "1";

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(NetMusicEchoAddon.MOD_ID)
                .versioned(PROTOCOL_VERSION);
        // EchoSearchMessage：客户端发起搜索 + 服务端回传结果（双向）
        registrar.playBidirectional(EchoSearchMessage.TYPE, EchoSearchMessage.STREAM_CODEC, EchoSearchMessage::handle);
        // EchoBurnMessage：客户端 → 服务端（烧录）
        registrar.playToServer(EchoBurnMessage.TYPE, EchoBurnMessage.STREAM_CODEC, EchoBurnMessage::handle);
        // AddCdRefreshInfoMessage：客户端 → 服务端（写 fileHash/albumId 到 CD）
        registrar.playToServer(AddCdRefreshInfoMessage.TYPE, AddCdRefreshInfoMessage.STREAM_CODEC, AddCdRefreshInfoMessage::handle);
        EchoLogger.info("Network Handler initialized!");
    }

    /**
     * 把 payload 发给指定服务端玩家（player → server 端使用）。
     */
    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    /**
     * 把 payload 发到服务端（client 端使用）。
     */
    public static void sendToServer(CustomPacketPayload payload) {
        PacketDistributor.sendToServer(payload);
    }
}
