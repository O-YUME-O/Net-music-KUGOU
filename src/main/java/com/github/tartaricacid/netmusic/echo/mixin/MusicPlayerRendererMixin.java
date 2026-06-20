package com.github.tartaricacid.netmusic.echo.mixin;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.github.tartaricacid.netmusic.client.event.ConfigEvent;
import com.github.tartaricacid.netmusic.client.renderer.MusicPlayerRenderer;
import com.github.tartaricacid.netmusic.config.GeneralConfig;
import com.github.tartaricacid.netmusic.echo.EchoLogger;
import com.github.tartaricacid.netmusic.echo.config.ClientConfig;
import com.github.tartaricacid.netmusic.echo.lyric.BlockRomajiRegistry;
import com.github.tartaricacid.netmusic.tileentity.TileEntityMusicPlayer;
import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.apache.commons.lang3.StringUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Mixin 到父模组 {@code MusicPlayerRenderer.renderLyric}，把渲染从 2 行（原文 + 翻译）
 * 扩展到最多 3 行（原文 + 翻译 + 罗马音），行数由 {@link ClientConfig#LYRIC_SHOW_TRANSLATION}
 * 和 {@link ClientConfig#LYRIC_SHOW_ROMAJI} 控制。
 * <p>
 * 父模组"停止播放就清空 lyricRecord"的位置我们同步清空
 * {@link BlockRomajiRegistry}，避免侧通道无限增长。
 */
@Mixin(MusicPlayerRenderer.class)
public abstract class MusicPlayerRendererMixin {

    @Shadow
    private Font font;

    @Shadow
    private BlockEntityRenderDispatcher dispatcher;

    /**
     * 一次性诊断日志 key：用歌词第一行作为"当前歌"标识。
     * 同一首歌第一次进入 renderLyric 时打一次（看 BlockRomajiRegistry 有无罗马音），
     * 切歌时歌词第一行变化，自动重新打。
     */
    private static String DIAG_LOG_KEY = null;

    /**
     * @author EchoAddon
     * @reason 把父模组的"原文 + 翻译"双行扩展为"原文 + 翻译 + 罗马音"最多三行，
     *         行数由 ClientConfig 控制。
     */
    @Overwrite(remap = false)
    private void renderLyric(TileEntityMusicPlayer te, PoseStack poseStack,
                              MultiBufferSource bufferIn, int combinedLightIn) {
        if (!GeneralConfig.ENABLE_PLAYER_LYRICS.get()) {
            return;
        }
        LyricRecord lyricRecord = te.lyricRecord;
        if (lyricRecord == null) {
            // 关键：这里不能 BlockRomajiRegistry.remove()！
            // 竞态条件：父模组清空 lyricRecord 是在 NetMusicSound 即将被新实例替换的"间隙"，
            // 旧歌清掉 lyricRecord 触发的 remove，会把下一首 onHandleHead 刚 put 进来的
            // 新 romaji 数据一并清掉（导致 romaji 行永远不显示）。
            // 改为：让 entry 留在 registry，由下一首歌的 put 自然覆盖。
            return;
        }
        Int2ObjectSortedMap<String> lyrics = lyricRecord.getLyrics();
        if (lyrics == null || lyrics.isEmpty()) {
            return;
        }

        // 如果已经停止播放了，直接清空（父模组行为）
        if (!te.isPlay()) {
            te.lyricRecord = null;
            BlockRomajiRegistry.remove(te.getBlockPos());
            return;
        }

        // === 一次性诊断日志：检测 BlockRomajiRegistry 是否对该方块有罗马音数据 ===
        // 防止每帧刷屏：用 songName 作 key（不是 BlockPos，CD 替换时 songName 变）
        Int2ObjectSortedMap<String> romajiMapProbe = BlockRomajiRegistry.get(te.getBlockPos());
        if (DIAG_LOG_KEY != null && DIAG_LOG_KEY.equals(lyrics.get(lyrics.firstIntKey()))) {
            // 已打过诊断，跳过
        } else {
            String firstLine = lyrics.get(lyrics.firstIntKey());
            if (romajiMapProbe == null) {
                // 该 pos 在 registry 里完全没有 entry（说明 onHandleHead 还没跑或失败）
                if (ClientConfig.LYRIC_SHOW_ROMAJI.get()) {
                    EchoLogger.warn(
                            "[EchoAddon] DIAG: block CD at {} has NO romaji entry in registry (firstLine='{}'). onHandleHead 可能没跑成功。",
                            te.getBlockPos(), truncate(firstLine, 30));
                }
            } else if (romajiMapProbe.isEmpty()) {
                if (ClientConfig.LYRIC_SHOW_ROMAJI.get()) {
                    EchoLogger.warn(
                            "[EchoAddon] DIAG: block CD at {} has EMPTY romaji map (0 entries, firstLine='{}'). KRC 的 type=0 罗马音字段缺失或对齐失败。",
                            te.getBlockPos(), truncate(firstLine, 30));
                }
            } else {
                EchoLogger.info(
                        "[EchoAddon] DIAG: block CD at {} has {} romaji entries (firstLine='{}', romajiFirstKey={}, currentKey={})",
                        te.getBlockPos(), romajiMapProbe.size(), truncate(firstLine, 30),
                        romajiMapProbe.firstIntKey(), lyrics.firstIntKey());
            }
            DIAG_LOG_KEY = firstLine;
        }

        Camera camera = this.dispatcher.camera;
        int originalColor = ConfigEvent.PLAYER_ORIGINAL_COLOR;
        int transColor = ConfigEvent.PLAYER_TRANSLATED_COLOR;
        int romajiColor = ConfigEvent.PLAYER_TRANSLATED_COLOR; // 默认同翻译色

        // === 第 1 行：原文 ===
        String lyric = lyrics.get(lyrics.firstIntKey());
        MutableComponent currentLine = StringUtils.isNotBlank(lyric)
                ? Component.literal(lyric)
                : Component.empty();

        // 关键修复：如果当前 tick 的原文为空（前奏/间奏/纯音乐段），
        // 翻译/罗马音即使 map 里有也是"孤儿行"（属于别的 tick），
        // 显示出来会让用户看到对不上的内容 → 一律隐藏。
        boolean mainLyricBlank = StringUtils.isBlank(lyric);

        // === 第 2 行：翻译（按配置）===
        boolean showTranslation = ClientConfig.LYRIC_SHOW_TRANSLATION.get();
        MutableComponent translatedLine = null;
        if (showTranslation && !mainLyricBlank) {
            Int2ObjectSortedMap<String> transLyrics = lyricRecord.getTransLyrics();
            if (transLyrics != null && !transLyrics.isEmpty()) {
                String transLyric = transLyrics.get(transLyrics.firstIntKey());
                if (StringUtils.isNotBlank(transLyric)) {
                    translatedLine = Component.literal(transLyric);
                }
            }
        }

        // === 第 3 行：罗马音（按配置 + 侧通道）===
        boolean showRomaji = ClientConfig.LYRIC_SHOW_ROMAJI.get();
        MutableComponent romajiLine = null;
        if (showRomaji && !mainLyricBlank) {
            Int2ObjectSortedMap<String> romajiMap = BlockRomajiRegistry.get(te.getBlockPos());
            if (romajiMap != null && !romajiMap.isEmpty()) {
                // 关键修复：romajiMap 没有 updateCurrentLine 机制（独立 map），
                // 必须自己用原文的 currentKey 在 romajiMap 里找 <= currentKey 的最大 tick。
                // 同时：currentKey < romajiFirstKey 时罗马音还没开始（标题行/前奏阶段），
                // 不显示，防止第一句罗马音"拉长到前面"。
                int currentKey = lyrics.firstIntKey();
                int romajiFirstKey = romajiMap.firstIntKey();
                if (currentKey >= romajiFirstKey) {
                    int romajiKey = findFloorKey(romajiMap, currentKey);
                    String romajiText = romajiMap.get(romajiKey);
                    if (StringUtils.isNotBlank(romajiText)) {
                        romajiLine = Component.literal(romajiText);
                    }
                }
            }
        }

        // 单行时：原文用翻译色（与父模组原版一致）
        int currentColor = (translatedLine == null && romajiLine == null)
                ? transColor : originalColor;

        // 行数决定 y 偏移：每多一行 y + 0.5（与父模组同公式）
        float y = 0.5f;
        if (translatedLine != null || romajiLine != null) y += 0.5f;
        if (romajiLine != null) y += 0.5f;

        poseStack.pushPose();
        poseStack.translate(0.5, 1.625, 0.5);
        poseStack.mulPose(camera.rotation());
        poseStack.scale(-0.025F, -0.025F, 0.025F);

        float opacity = Minecraft.getInstance().options.getBackgroundOpacity(0.25F);
        int bgColor = (int) (opacity * 255.0F) << 24;

        if (currentLine != null && currentLine != Component.empty()) {
            float currentLineWidth = (float) (-this.font.width(currentLine) / 2);
            this.font.drawInBatch(currentLine, currentLineWidth, -y, currentColor, false,
                    poseStack.last().pose(), bufferIn, Font.DisplayMode.NORMAL,
                    bgColor, combinedLightIn);
        }
        if (translatedLine != null) {
            float w = (float) (-this.font.width(translatedLine) / 2);
            this.font.drawInBatch(translatedLine, w, -y - 12, transColor, false,
                    poseStack.last().pose(), bufferIn, Font.DisplayMode.NORMAL,
                    bgColor, combinedLightIn);
        }
        if (romajiLine != null) {
            float w = (float) (-this.font.width(romajiLine) / 2);
            this.font.drawInBatch(romajiLine, w, -y - 24, romajiColor, false,
                    poseStack.last().pose(), bufferIn, Font.DisplayMode.NORMAL,
                    bgColor, combinedLightIn);
        }

        poseStack.popPose();
    }

    /**
     * 在 sorted map 中找到 &le; targetTick 的最大 key。
     * <p>
     * 罗马音独立于 LyricRecord，没有 updateCurrentLine 的破坏性前进机制，
     * 需要在每帧根据 currentTick 自己定位当前行。
     */
    private static int findFloorKey(Int2ObjectSortedMap<String> map, int targetTick) {
        if (map == null || map.isEmpty()) return 0;
        int firstKey = map.firstIntKey();
        if (targetTick <= firstKey) return firstKey;
        int lastKey = map.lastIntKey();
        if (targetTick >= lastKey) return lastKey;
        int lo = 0, hi = map.size() - 1, best = firstKey;
        int[] keys = map.keySet().toIntArray();
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int k = keys[mid];
            if (k <= targetTick) {
                best = k;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return best;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "<null>";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
