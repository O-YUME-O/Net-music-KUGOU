

package com.github.tartaricacid.netmusic.echo.network.message;

import com.github.tartaricacid.netmusic.echo.EchoLogger;
import com.github.tartaricacid.netmusic.echo.NetMusicEchoAddon;
import com.github.tartaricacid.netmusic.echo.api.KuGouApiClient;
import com.github.tartaricacid.netmusic.echo.inventory.EchoSearcherMenu;
import com.github.tartaricacid.netmusic.echo.support.CdNbtHelper;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

public class EchoBurnMessage {
    public final ItemMusicCD.SongInfo song;
    public final String fileHash;
    public final String albumId;

    public EchoBurnMessage(ItemMusicCD.SongInfo song, String fileHash, String albumId) {
        this.song = song;
        this.fileHash = fileHash;
        this.albumId = albumId;
    }

    public static EchoBurnMessage decode(FriendlyByteBuf buf) {
        CompoundTag tag = buf.readNbt();
        ItemMusicCD.SongInfo songData = ItemMusicCD.SongInfo.deserializeNBT(tag);
        String fileHash = buf.readUtf();
        String albumId = buf.readUtf();
        return new EchoBurnMessage(songData, fileHash, albumId);
    }

    public static void encode(EchoBurnMessage message, FriendlyByteBuf buf) {
        CompoundTag tag = new CompoundTag();
        ItemMusicCD.SongInfo.serializeNBT(message.song, tag);
        buf.writeNbt(tag);
        buf.writeUtf(message.fileHash);
        buf.writeUtf(message.albumId);
    }

    public static void handle(EchoBurnMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            if (context.getDirection().getReceptionSide().isServer()) {
                var player = context.getSender();
                if (player != null && player.containerMenu instanceof EchoSearcherMenu menu) {
                    try {
                        // 同步等待获取歌曲 URL
                        String url = KuGouApiClient.getSongUrl(message.fileHash, message.albumId).get();
                        if (url != null && !url.isEmpty()) {
                            message.song.songUrl = url;
                            menu.burnCD(message.song);

                            // 把原曲识别信息（fileHash / albumId）写到 CD 物品 NBT，
                            // 这样 UrlRefresher 在 URL 过期时能自动续期。
                            ItemStack burnedCd = menu.getOutput().getStackInSlot(0);
                            if (CdNbtHelper.isMusicCd(burnedCd)) {
                                CdNbtHelper.writeOriginalInfo(burnedCd, message.fileHash, message.albumId);
                                EchoLogger.info("EchoBurnMessage: stored fileHash={} on burned CD for future URL refresh", message.fileHash);

                                // 拉取歌词并写入 CD NBT（不阻塞刻录主流程；失败仅记日志）
                                fetchAndStoreLyric(burnedCd, message);
                            } else {
                                EchoLogger.error("Failed to get song URL for hash: {}", message.fileHash);
                            }
                        } else {
                            EchoLogger.error("Failed to get song URL for hash: {}", message.fileHash);
                        }
                    } catch (Exception e) {
                        EchoLogger.error("Failed to burn CD", e);
                    }
                }
            }
        });
        context.setPacketHandled(true);
    }

    /**
     * 拉取歌词并写入 CD NBT。整个流程是 best-effort，
     * 任何步骤失败都只打日志不抛异常，避免影响主刻录流程。
     */
    private static void fetchAndStoreLyric(ItemStack burnedCd, EchoBurnMessage message) {
        try {
            // 搜索关键字：尽量 "歌手 - 歌名" 形式，命中率最高
            String singer = (message.song.artists == null || message.song.artists.isEmpty())
                    ? ""
                    : String.join(", ", message.song.artists);
            String song = message.song.songName == null ? "" : message.song.songName;
            String keyword = (singer.isEmpty() ? "" : singer + " - ") + song;
            if (keyword.isEmpty()) {
                return;
            }
            // songTime 是秒，酷狗 search_lyric 期望毫秒
            int duration = message.song.songTime * 1000;

            KuGouApiClient.LyricCandidate candidate = KuGouApiClient
                    .searchLyric(message.fileHash, keyword, duration)
                    .get();
            if (candidate == null) {
                EchoLogger.info("EchoBurnMessage: no lyric candidate for hash={}, keyword={}",
                        message.fileHash, keyword);
                return;
            }

            // 先用 lrc 抓一份最轻量的；拿不到再尝试 krc
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
