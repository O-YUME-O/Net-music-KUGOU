package com.github.tartaricacid.netmusic.echo.network.message;

import com.github.tartaricacid.netmusic.echo.EchoLogger;
import com.github.tartaricacid.netmusic.echo.NetMusicEchoAddon;
import com.github.tartaricacid.netmusic.echo.api.KuGouApiClient;
import com.github.tartaricacid.netmusic.echo.echo.EchoMusicApi;
import com.github.tartaricacid.netmusic.echo.inventory.EchoSearcherMenu;
import com.github.tartaricacid.netmusic.echo.client.gui.EchoSearchScreen;
import com.github.tartaricacid.netmusic.echo.network.NetworkHandler;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端 ↔ 服务端：酷狗音乐搜索结果传输。
 * <p>
 * 客户端 → 服务端：携带 keyword + page，results 为空列表。
 * 服务端 → 客户端：携带 keyword + page + 搜索结果列表。
 */
public record EchoSearchMessage(
        String keyword,
        int page,
        List<EchoSearcherMenu.SearchResult> results
) implements CustomPacketPayload {

    public static final Type<EchoSearchMessage> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(NetMusicEchoAddon.MOD_ID, "echo_search"));

    /**
     * 简化构造器：客户端发起搜索时用，results 默认空。
     */
    public EchoSearchMessage(String keyword, int page) {
        this(keyword, page, new ArrayList<>());
    }

    /**
     * 单条 {@link EchoSearcherMenu.SearchResult} 的编解码器。
     * 7 个字段全部为 String/INT，逐个写最直观，避免 NBT 开销。
     */
    public static final StreamCodec<ByteBuf, EchoSearcherMenu.SearchResult> SEARCH_RESULT_CODEC =
            StreamCodec.of(
                    (buf, r) -> {
                        ByteBufCodecs.STRING_UTF8.encode(buf, r.songName);
                        ByteBufCodecs.STRING_UTF8.encode(buf, r.singerName);
                        ByteBufCodecs.STRING_UTF8.encode(buf, r.albumName);
                        ByteBufCodecs.VAR_INT.encode(buf, r.duration);
                        ByteBufCodecs.STRING_UTF8.encode(buf, r.songUrl);
                        ByteBufCodecs.STRING_UTF8.encode(buf, r.fileHash);
                        ByteBufCodecs.STRING_UTF8.encode(buf, r.albumId);
                    },
                    buf -> new EchoSearcherMenu.SearchResult(
                            ByteBufCodecs.STRING_UTF8.decode(buf),
                            ByteBufCodecs.STRING_UTF8.decode(buf),
                            ByteBufCodecs.STRING_UTF8.decode(buf),
                            ByteBufCodecs.VAR_INT.decode(buf),
                            ByteBufCodecs.STRING_UTF8.decode(buf),
                            ByteBufCodecs.STRING_UTF8.decode(buf),
                            ByteBufCodecs.STRING_UTF8.decode(buf)
                    )
            );

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static final StreamCodec<RegistryFriendlyByteBuf, List<EchoSearcherMenu.SearchResult>> SEARCH_RESULT_LIST_CODEC =
            (StreamCodec) ByteBufCodecs.list(SEARCH_RESULT_CODEC);

    public static final StreamCodec<RegistryFriendlyByteBuf, EchoSearchMessage> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, EchoSearchMessage::keyword,
            ByteBufCodecs.VAR_INT, EchoSearchMessage::page,
            SEARCH_RESULT_LIST_CODEC, EchoSearchMessage::results,
            EchoSearchMessage::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * 处理函数：服务端执行搜索并回复；客户端把结果喂给 {@link EchoSearchScreen}。
     */
    public static void handle(EchoSearchMessage message, IPayloadContext context) {
        if (context.flow().isServerbound()) {
            // 客户端 → 服务端：发起搜索
            context.enqueueWork(() -> {
                if (!(context.player() instanceof net.minecraft.server.level.ServerPlayer player)) {
                    return;
                }
                try {
                    List<EchoMusicApi.Song> songs = KuGouApiClient.search(message.keyword, message.page, 10).get();
                    List<EchoSearcherMenu.SearchResult> results = new ArrayList<>();
                    for (EchoMusicApi.Song song : songs) {
                        results.add(new EchoSearcherMenu.SearchResult(
                                song.name, song.singer, song.album, song.duration,
                                "",
                                song.hash != null ? song.hash : "",
                                song.albumId != null ? song.albumId : ""
                        ));
                    }
                    NetworkHandler.sendToPlayer(player, new EchoSearchMessage(message.keyword, message.page, results));
                } catch (Exception e) {
                    EchoLogger.error("Failed to search songs", e);
                }
            });
        } else {
            // 服务端 → 客户端：填到屏幕
            context.enqueueWork(() -> {
                var screen = net.minecraft.client.Minecraft.getInstance().screen;
                if (screen instanceof EchoSearchScreen searchScreen) {
                    searchScreen.setSearchResults(message.results);
                }
            });
        }
    }
}
