package com.github.tartaricacid.netmusic.echo.network.message;

import com.github.tartaricacid.netmusic.echo.EchoLogger;
import com.github.tartaricacid.netmusic.echo.NetMusicEchoAddon;
import com.github.tartaricacid.netmusic.echo.api.KuGouApiClient;
import com.github.tartaricacid.netmusic.echo.inventory.EchoSearcherMenu;
import com.github.tartaricacid.netmusic.echo.support.CdNbtHelper;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：酷狗 CD 烧录。
 * <p>
 * 1.20.1 父 mod 的 {@code ItemMusicCD.SongInfo.serializeNBT} / {@code deserializeNBT}
 * 在 1.21.1 已删除（DataComponent 化），新版本用
 * {@link ItemMusicCD.SongInfo#STREAM_CODEC} 直接做流编解码。
 * 该 codec 是 {@code StreamCodec<ByteBuf, SongInfo>}，而我们的 payload 走
 * {@code RegistryFriendlyByteBuf}，运行期是兼容的，做一个 raw cast 即可。
 */
public record EchoBurnMessage(
        ItemMusicCD.SongInfo song,
        String fileHash,
        String albumId
) implements CustomPacketPayload {

    public static final Type<EchoBurnMessage> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(NetMusicEchoAddon.MOD_ID, "echo_burn"));

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static final StreamCodec<RegistryFriendlyByteBuf, ItemMusicCD.SongInfo> SONG_INFO_CODEC =
            (StreamCodec) ItemMusicCD.SongInfo.STREAM_CODEC;

    public static final StreamCodec<RegistryFriendlyByteBuf, EchoBurnMessage> STREAM_CODEC = StreamCodec.composite(
            SONG_INFO_CODEC, EchoBurnMessage::song,
            ByteBufCodecs.STRING_UTF8, EchoBurnMessage::fileHash,
            ByteBufCodecs.STRING_UTF8, EchoBurnMessage::albumId,
            EchoBurnMessage::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(EchoBurnMessage message, IPayloadContext context) {
        if (!context.flow().isServerbound()) {
            return;
        }
        context.enqueueWork(() -> {
            if (!(context.player() instanceof net.minecraft.server.level.ServerPlayer player)) {
                return;
            }
            if (!(player.containerMenu instanceof EchoSearcherMenu menu)) {
                return;
            }
            try {
                String url = KuGouApiClient.getSongUrl(message.fileHash, message.albumId).get();
                if (url == null || url.isEmpty()) {
                    EchoLogger.error("Failed to get song URL for hash: {}", message.fileHash);
                    return;
                }
                message.song.songUrl = url;
                menu.burnCD(message.song);

                ItemStack burnedCd = menu.getOutput().getStackInSlot(0);
                if (CdNbtHelper.isMusicCd(burnedCd)) {
                    CdNbtHelper.writeOriginalInfo(burnedCd, message.fileHash, message.albumId);
                    EchoLogger.info("EchoBurnMessage: stored fileHash={} on burned CD for future URL refresh", message.fileHash);
                    fetchAndStoreLyric(burnedCd, message);
                } else {
                    EchoLogger.error("Failed to get song URL for hash: {}", message.fileHash);
                }
            } catch (Exception e) {
                EchoLogger.error("Failed to burn CD", e);
            }
        });
    }

    /**
     * 拉取歌词并写入 CD DataComponent。best-effort，
     * 任何步骤失败都只打日志不抛异常，避免影响主刻录流程。
     */
    private static void fetchAndStoreLyric(ItemStack burnedCd, EchoBurnMessage message) {
        try {
            String singer = (message.song.artists == null || message.song.artists.isEmpty())
                    ? ""
                    : String.join(", ", message.song.artists);
            String song = message.song.songName == null ? "" : message.song.songName;
            String keyword = (singer.isEmpty() ? "" : singer + " - ") + song;
            if (keyword.isEmpty()) {
                return;
            }
            int duration = message.song.songTime * 1000;

            KuGouApiClient.LyricCandidate candidate = KuGouApiClient
                    .searchLyric(message.fileHash, keyword, duration)
                    .get();
            if (candidate == null) {
                EchoLogger.info("EchoBurnMessage: no lyric candidate for hash={}, keyword={}",
                        message.fileHash, keyword);
                return;
            }

            KuGouApiClient.LyricContent content = KuGouApiClient
                    .getLyric(candidate.id, candidate.accessKey, "lrc")
                    .get();
            if ((content == null || content.lyricContent == null || content.lyricContent.isEmpty()) && candidate != null) {
                content = KuGouApiClient
                        .getLyric(candidate.id, candidate.accessKey, "krc")
                        .get();
            }
            if (content == null || content.lyricContent == null || content.lyricContent.isEmpty()) {
                EchoLogger.info("EchoBurnMessage: lyric body empty for hash={}", message.fileHash);
                return;
            }

            CdNbtHelper.writeLyric(burnedCd, content.lyricContent, song);
            EchoLogger.info("EchoBurnMessage: stored lyric ({} chars, fmt={}) for hash={}",
                    content.lyricContent.length(), content.format, message.fileHash);
        } catch (Exception e) {
            EchoLogger.warn("EchoBurnMessage: lyric fetch failed: {}", e.getMessage());
        }
    }
}
