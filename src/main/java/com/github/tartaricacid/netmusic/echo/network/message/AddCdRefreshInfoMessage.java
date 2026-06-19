package com.github.tartaricacid.netmusic.echo.network.message;

import com.github.tartaricacid.netmusic.echo.EchoLogger;
import com.github.tartaricacid.netmusic.echo.NetMusicEchoAddon;
import com.github.tartaricacid.netmusic.echo.api.KuGouApiClient;
import com.github.tartaricacid.netmusic.echo.support.CdNbtHelper;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端→服务端：把 fileHash / albumId 写进 CD 的 NBT。
 * <p>
 * 用法：在 SetMusicIDMessage（netmusic 自带）发完之后立刻发这个包，
 * 服务端会在玩家打开的容器 slot 0 找到刚烧好的 CD 并附加识别信息。
 * 之所以要单独一个包，是因为我们不能改 netmusic 自带的 SetMusicIDMessage。
 */
public class AddCdRefreshInfoMessage {
    public final String fileHash;
    public final String albumId;

    public AddCdRefreshInfoMessage(String fileHash, String albumId) {
        this.fileHash = fileHash;
        this.albumId = albumId;
    }

    public static void encode(AddCdRefreshInfoMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.fileHash == null ? "" : msg.fileHash);
        buf.writeUtf(msg.albumId == null ? "" : msg.albumId);
    }

    public static AddCdRefreshInfoMessage decode(FriendlyByteBuf buf) {
        String hash = buf.readUtf();
        String album = buf.readUtf();
        return new AddCdRefreshInfoMessage(hash, album);
    }

    public static void handle(AddCdRefreshInfoMessage msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            if (!ctx.getDirection().getReceptionSide().isServer()) {
                return;
            }
            var player = ctx.getSender();
            if (player == null) {
                return;
            }
            if (msg.fileHash == null || msg.fileHash.isEmpty()) {
                return;
            }
            AbstractContainerMenu menu = player.containerMenu;
            // 取 slot 0 的 CD（刻录机的输入槽）。如果玩家没开刻录机，退而求其次用主手。
            ItemStack cd = ItemStack.EMPTY;
            if (menu != null && menu.slots.size() > 0) {
                cd = menu.getSlot(0).getItem();
            }
            if (!CdNbtHelper.isMusicCd(cd)) {
                cd = player.getMainHandItem();
            }
            if (!CdNbtHelper.isMusicCd(cd)) {
                EchoLogger.warn("AddCdRefreshInfoMessage: no music CD found in slot 0 / main hand, skipping");
                return;
            }
            // 防御一下：必须真的烧过（songUrl 非空）才写
            ItemMusicCD.SongInfo info = ItemMusicCD.getSongInfo(cd);
            if (info == null || info.songUrl == null || info.songUrl.isEmpty()) {
                EchoLogger.warn("AddCdRefreshInfoMessage: CD in slot 0 has no songUrl yet, skipping");
                return;
            }
            CdNbtHelper.writeOriginalInfo(cd, msg.fileHash, msg.albumId);
            EchoLogger.info("AddCdRefreshInfoMessage: stored fileHash={} on burned CD for future URL refresh", msg.fileHash);

            // 异步拉取歌词并写入 CD NBT（best-effort，不阻塞主流程）
            fetchAndStoreLyric(cd, msg);
        });
        ctx.setPacketHandled(true);
    }

    /**
     * 异步拉取酷狗歌词并写入 CD NBT。
     * <p>
     * 流程：searchLyric(hash, keyword) → getLyric(id, accesskey) → writeLyric(cd)
     * 任何步骤失败只记日志不抛异常，不影响主刻录流程。
     */
    private static void fetchAndStoreLyric(ItemStack cd, AddCdRefreshInfoMessage msg) {
        // 从已烧录的 SongInfo 取歌手/歌名
        ItemMusicCD.SongInfo info = ItemMusicCD.getSongInfo(cd);
        if (info == null) return;

        String singer = (info.artists == null || info.artists.isEmpty())
                ? ""
                : String.join(", ", info.artists);
        String song = info.songName == null ? "" : info.songName;
        String keyword = (singer.isEmpty() ? "" : singer + " - ") + song;
        if (keyword.isEmpty()) return;
        int duration = info.songTime * 1000; // 秒→毫秒

        KuGouApiClient.searchLyric(msg.fileHash, keyword, duration)
                .thenAccept(candidate -> {
                    if (candidate == null) {
                        EchoLogger.info("AddCdRefreshInfo: no lyric candidate for hash={}, keyword={}",
                                msg.fileHash, keyword);
                        return;
                    }
                    EchoLogger.info("AddCdRefreshInfo: got lyric candidate: {} - {} (score={})",
                            candidate.singer, candidate.songName, candidate.score);

                    KuGouApiClient.getLyric(candidate.id, candidate.accessKey, "lrc")
                            .thenAccept(content -> {
                                if (content == null || content.lyricContent == null || content.lyricContent.isEmpty()) {
                                    // lrc 没拿到，试 krc
                                    KuGouApiClient.getLyric(candidate.id, candidate.accessKey, "krc")
                                            .thenAccept(krcContent -> {
                                                if (krcContent != null && krcContent.lyricContent != null && !krcContent.lyricContent.isEmpty()) {
                                                    CdNbtHelper.writeLyric(cd, krcContent.lyricContent, song);
                                                    EchoLogger.info(
                                                            "AddCdRefreshInfo: stored lyric ({} chars, fmt=krc) for hash={}",
                                                            krcContent.lyricContent.length(), msg.fileHash);
                                                } else {
                                                    EchoLogger.info(
                                                            "AddCdRefreshInfo: lyric body empty for hash={}", msg.fileHash);
                                                }
                                            });
                                    return;
                                }
                                CdNbtHelper.writeLyric(cd, content.lyricContent, song);
                                EchoLogger.info(
                                        "AddCdRefreshInfo: stored lyric ({} chars, fmt={}) for hash={}",
                                        content.lyricContent.length(), content.format, msg.fileHash);
                            })
                            .exceptionally(e -> {
                                EchoLogger.warn("AddCdRefreshInfo: getLyric failed: {}", e.getMessage());
                                return null;
                            });
                })
                .exceptionally(e -> {
                    EchoLogger.warn("AddCdRefreshInfo: searchLyric failed: {}", e.getMessage());
                    return null;
                });
    }
}
