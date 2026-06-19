package com.github.tartaricacid.netmusic.echo.support;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/**
 * 用来在 CD 物品的 NBT 上存取"原曲识别信息"（fileHash + albumId）。
 * <p>
 * 这些字段是本 addon（netmusic-echo-addon）扩展的，{@code ItemMusicCD} 原版 NBT 里没有。
 * 我们直接挂在 CD 物品自己的 root CompoundTag 上，避开和 netmusic 自有 NBT 结构冲突。
 * <p>
 * 用途：CD 上烧入的 {@code songUrl} 是酷狗的短时签名 URL，过期后会 403。
 * 有了 fileHash + albumId，我们就能在过期时重新调一次 {@code KuGouApiClient.getSongUrl()} 拿新 URL。
 */
public final class CdNbtHelper {
    private CdNbtHelper() {}

    public static final String NBT_FILE_HASH = "netmusic_echo_addon_file_hash";
    public static final String NBT_ALBUM_ID = "netmusic_echo_addon_album_id";
    public static final String NBT_BURN_TIMESTAMP = "netmusic_echo_addon_burn_time";
    public static final String NBT_LRC = "netmusic_echo_addon_lrc";
    public static final String NBT_LRC_SONG = "netmusic_echo_addon_lrc_song";
    /** 酷狗 KRC language 字段（base64 编码的 JSON，含原音/中文翻译） */
    public static final String NBT_LRC_TRANS = "netmusic_echo_addon_lrc_trans";

    /**
     * 把识别信息写到 CD 的 NBT。如果 fileHash/albumId 为空则不写。
     */
    public static void writeOriginalInfo(ItemStack cd, String fileHash, String albumId) {
        if (cd == null || cd.isEmpty() || fileHash == null || fileHash.isEmpty()) {
            return;
        }
        var tag = cd.getOrCreateTag();
        tag.putString(NBT_FILE_HASH, fileHash);
        tag.putString(NBT_ALBUM_ID, albumId == null ? "" : albumId);
        tag.putLong(NBT_BURN_TIMESTAMP, System.currentTimeMillis());
    }

    /**
     * 读取 CD 上记录的原曲识别信息。如果没记录就返回 {@link Optional#empty()}。
     */
    public static Optional<OriginalInfo> readOriginalInfo(ItemStack cd) {
        if (cd == null || cd.isEmpty()) {
            return Optional.empty();
        }
        var tag = cd.getTag();
        if (tag == null) {
            return Optional.empty();
        }
        if (!tag.contains(NBT_FILE_HASH)) {
            return Optional.empty();
        }
        String hash = tag.getString(NBT_FILE_HASH);
        if (hash == null || hash.isEmpty()) {
            return Optional.empty();
        }
        String albumId = tag.getString(NBT_ALBUM_ID);
        long burnTime = tag.getLong(NBT_BURN_TIMESTAMP);
        return Optional.of(new OriginalInfo(hash, albumId, burnTime));
    }

    /**
     * 判断 ItemStack 是不是 netmusic 的音乐 CD（避免对其他物品误操作）
     */
    public static boolean isMusicCd(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() instanceof ItemMusicCD;
    }

    /**
     * 刷新 CD 上的 songUrl 字段。NBT 序列化交给 netmusic 自带逻辑处理。
     */
    public static void updateSongUrl(ItemStack cd, String newUrl) {
        if (!isMusicCd(cd) || newUrl == null || newUrl.isEmpty()) {
            return;
        }
        ItemMusicCD.SongInfo info = ItemMusicCD.getSongInfo(cd);
        if (info == null) {
            return;
        }
        info.songUrl = newUrl;
        ItemMusicCD.setSongInfo(info, cd);
    }

    /**
     * 从 CD 上读取当前 songUrl
     */
    public static String readSongUrl(ItemStack cd) {
        if (!isMusicCd(cd)) {
            return null;
        }
        ItemMusicCD.SongInfo info = ItemMusicCD.getSongInfo(cd);
        return info == null ? null : info.songUrl;
    }

    /**
     * 包含 fileHash + albumId + 烧入时间的不可变记录
     */
    public static final class OriginalInfo {
        public final String fileHash;
        public final String albumId;
        public final long burnTime;

        OriginalInfo(String fileHash, String albumId, long burnTime) {
            this.fileHash = fileHash;
            this.albumId = albumId;
            this.burnTime = burnTime;
        }
    }

    /**
     * 把 LRC 文本写到 CD NBT（烧录时用）。
     * <p>
     * 这里存的是 LRC 原始文本，不是解析后的结构。客户端拿到后再用 {@code LrcConverter} 解析，
     * 这样可以避免 LRC 解析逻辑被版本化到 NBT 上造成兼容性问题。
     */
    public static void writeLyric(ItemStack cd, String lrcText, String songName) {
        if (!isMusicCd(cd) || lrcText == null || lrcText.isEmpty()) {
            return;
        }
        var tag = cd.getOrCreateTag();
        tag.putString(NBT_LRC, lrcText);
        if (songName != null && !songName.isEmpty()) {
            tag.putString(NBT_LRC_SONG, songName);
        }
    }

    /**
     * 读取 CD 上的 LRC 文本和歌曲名。返回 {@code Lyric} 记录（含空 LRC 标记），
     * 没有则返回 {@code null}。
     */
    public static Lyric readLyric(ItemStack cd) {
        if (!isMusicCd(cd)) {
            return null;
        }
        var tag = cd.getTag();
        if (tag == null || !tag.contains(NBT_LRC)) {
            return null;
        }
        String lrc = tag.getString(NBT_LRC);
        if (lrc == null || lrc.isEmpty()) {
            return null;
        }
        String song = tag.getString(NBT_LRC_SONG);
        return new Lyric(lrc, song);
    }

    /**
     * CD 上记录的 LRC 文本 + 歌曲名
     */
    public static final class Lyric {
        public final String lrcText;
        public final String songName;

        Lyric(String lrcText, String songName) {
            this.lrcText = lrcText;
            this.songName = songName;
        }
    }

    /**
     * 写入翻译 JSON 文本（酷狗 KRC language 字段解码后的 JSON 字符串）到 CD NBT。
     */
    public static void writeLyricTranslation(ItemStack cd, String transJson) {
        if (!isMusicCd(cd) || transJson == null || transJson.isEmpty()) {
            return;
        }
        var tag = cd.getOrCreateTag();
        tag.putString(NBT_LRC_TRANS, transJson);
    }

    /**
     * 读取 CD 上的翻译 JSON 文本。没有则返回 null。
     */
    public static String readLyricTranslation(ItemStack cd) {
        if (!isMusicCd(cd)) {
            return null;
        }
        var tag = cd.getTag();
        if (tag == null || !tag.contains(NBT_LRC_TRANS)) {
            return null;
        }
        return tag.getString(NBT_LRC_TRANS);
    }
}
