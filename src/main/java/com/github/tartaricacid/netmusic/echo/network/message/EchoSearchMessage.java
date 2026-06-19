

package com.github.tartaricacid.netmusic.echo.network.message;

import com.github.tartaricacid.netmusic.echo.EchoLogger;
import com.github.tartaricacid.netmusic.echo.NetMusicEchoAddon;
import com.github.tartaricacid.netmusic.echo.api.KuGouApiClient;
import com.github.tartaricacid.netmusic.echo.echo.EchoMusicApi;
import com.github.tartaricacid.netmusic.echo.inventory.EchoSearcherMenu;
import com.github.tartaricacid.netmusic.echo.client.gui.EchoSearchScreen;
import com.github.tartaricacid.netmusic.echo.network.NetworkHandler;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class EchoSearchMessage {
    public final String keyword;
    public final int page;
    public final List<EchoSearcherMenu.SearchResult> results;

    public EchoSearchMessage(String keyword, int page, List<EchoSearcherMenu.SearchResult> results) {
        this.keyword = keyword;
        this.page = page;
        this.results = results;
    }

    public EchoSearchMessage(String keyword, int page) {
        this(keyword, page, new ArrayList<>());
    }

    public static EchoSearchMessage decode(FriendlyByteBuf buf) {
        String keyword = buf.readUtf();
        int page = buf.readInt();
        List<EchoSearcherMenu.SearchResult> results = new ArrayList<>();
        if (buf.readBoolean()) {
            ListTag tagList = buf.readNbt().getList("results", Tag.TAG_COMPOUND);
            for (int i = 0; i < tagList.size(); i++) {
                CompoundTag resultTag = tagList.getCompound(i);
                results.add(new EchoSearcherMenu.SearchResult(
                        resultTag.getString("songName"),
                        resultTag.getString("singerName"),
                        resultTag.getString("albumName"),
                        resultTag.getInt("duration"),
                        resultTag.getString("songUrl"),
                        resultTag.getString("fileHash"),
                        resultTag.getString("albumId")
                ));
            }
        }
        return new EchoSearchMessage(keyword, page, results);
    }

    public static void encode(EchoSearchMessage message, FriendlyByteBuf buf) {
        buf.writeUtf(message.keyword);
        buf.writeInt(message.page);
        buf.writeBoolean(!message.results.isEmpty());
        if (!message.results.isEmpty()) {
            CompoundTag tag = new CompoundTag();
            ListTag tagList = new ListTag();
            for (EchoSearcherMenu.SearchResult result : message.results) {
                CompoundTag resultTag = new CompoundTag();
                resultTag.putString("songName", result.songName);
                resultTag.putString("singerName", result.singerName);
                resultTag.putString("albumName", result.albumName);
                resultTag.putInt("duration", result.duration);
                resultTag.putString("songUrl", result.songUrl);
                resultTag.putString("fileHash", result.fileHash);
                resultTag.putString("albumId", result.albumId);
                tagList.add(resultTag);
            }
            tag.put("results", tagList);
            buf.writeNbt(tag);
        }
    }

    public static void handle(EchoSearchMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            if (context.getDirection().getReceptionSide().isServer()) {
                var player = context.getSender();
                if (player != null) {
                    try {
                        // 同步等待搜索结果（在服务器主线程上安全调用）
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
                        NetworkHandler.CHANNEL.send(
                                PacketDistributor.PLAYER.with(() -> player),
                                new EchoSearchMessage(message.keyword, message.page, results));
                    } catch (Exception e) {
                        EchoLogger.error("Failed to search songs", e);
                    }
                }
            } else {
                var screen = net.minecraft.client.Minecraft.getInstance().screen;
                if (screen instanceof EchoSearchScreen searchScreen) {
                    searchScreen.setSearchResults(message.results);
                }
            }
        });
        context.setPacketHandled(true);
    }
}
