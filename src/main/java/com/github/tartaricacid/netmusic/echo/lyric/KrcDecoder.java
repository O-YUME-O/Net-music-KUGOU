package com.github.tartaricacid.netmusic.echo.lyric;

import com.github.tartaricacid.netmusic.echo.EchoLogger;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * KRC 酷狗私有二进制歌词格式解码器。
 * <p>
 * KRC 文件结构（参考 KRC 客户端 SDK）：
 * <ol>
 *   <li>4 字节魔数头：{@code krc1}（即 {@code 0x6B726331}，ASCII "krc1"）</li>
 *   <li>剩余字节用 16 字节密钥循环 XOR（不带 -128 减法）</li>
 *   <li>对结果做 <b>zlib（带 header）</b> 解压（{@code nowrap=false}）</li>
 *   <li>按行匹配 {@code [mm:ss.fff]<...>(...)text}，剥除 KRC 逐字标签得到 LRC</li>
 * </ol>
 *
 * <p>参考资料：
 * <a href="https://blog.csdn.net/qingzi635533/article/details/30231733">酷狗 krc 歌词解析</a>。
 */
public final class KrcDecoder {

    /**
     * KRC XOR 密钥（16 字节）：
     * {@code '@', 'G', 'a', 'w', '^', '2', 't', 'G', 'Q', '6', '1', '-', 'Î', 'Ò', 'n', 'i'}
     * <p>
     * 注意：这是原始异或字节，<b>不带 -128 减法</b>。
     */
    private static final byte[] DECRYPT_KEY = {
            '@', 'G', 'a', 'w', '^', '2', 't', 'G',
            'Q', '6', '1', '-', (byte) 'Î', (byte) 'Ò', 'n', 'i'
    };

    private KrcDecoder() {}

    /**
     * 把 KRC base64 字符串解码为 LRC 文本。
     *
     * @param krcBase64 酷狗 /download 返回的 content 字段（base64 编码的 KRC 二进制）
     * @return 标准 LRC 文本，<b>失败时返回 {@code null}</b>
     */
    public static String decodeToLrc(String krcBase64) {
        if (krcBase64 == null || krcBase64.isEmpty()) {
            return null;
        }
        try {
            byte[] raw;
            try {
                raw = Base64.getDecoder().decode(krcBase64);
            } catch (IllegalArgumentException e) {
                // 不是 base64 的话按原始字节处理
                raw = krcBase64.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            }
            // 跳过 4 字节文件头 ("krc1" 魔数)
            if (raw.length < 4) {
                EchoLogger.warn("[NetMusicEchoAddon] KRC body too short: {} bytes", raw.length);
                return null;
            }
            // 诊断：raw[0..3] hex
            StringBuilder hexHead = new StringBuilder();
            for (int i = 0; i < 4 && i < raw.length; i++) {
                hexHead.append(String.format("%02X ", raw[i] & 0xFF));
            }
            EchoLogger.info(
                    "[NetMusicEchoAddon] KRC raw header (4 bytes hex): {}", hexHead);
            byte[] body = new byte[raw.length - 4];
            System.arraycopy(raw, 4, body, 0, body.length);

            // XOR 解密（不带 -128）
            byte[] decrypted = xorDecrypt(body);

            // zlib 解压（标准 zlib，不是 raw deflate）
            byte[] inflated = inflate(decrypted);

            String text = new String(inflated, java.nio.charset.StandardCharsets.UTF_8);
            // 打印前 5 字节 hex + 前 5 行内容（单行拼接避免 SLF4J 换行展平）
            StringBuilder hex5 = new StringBuilder();
            for (int i = 0; i < 5 && i < inflated.length; i++) {
                hex5.append(String.format("%02X ", inflated[i] & 0xFF));
            }
            String[] lines = text.split("\n");
            StringBuilder first5Lines = new StringBuilder();
            for (int i = 0; i < Math.min(5, lines.length); i++) {
                first5Lines.append(" || LINE").append(i).append("=").append(lines[i]);
            }
            EchoLogger.info(
                    "[NetMusicEchoAddon] KRC inflated: {} bytes, first5Hex={}, totalLines={}, first5Lines: {}",
                    inflated.length, hex5, lines.length, first5Lines);
            text = stripKrcTags(text);
            // 诊断：找 [language:...] 行（EchoMusic 的翻译字段）
            int langIdx = text != null ? text.indexOf("[language:") : -1;
            if (langIdx >= 0) {
                int langLineEnd = text.indexOf(']', langIdx);
                int langLineStart = langIdx;
                int langLineLen = langLineEnd - langLineStart + 1;
                String langLine = text.substring(langLineStart, Math.min(langLineStart + 80, text.length()));
                EchoLogger.info(
                        "[NetMusicEchoAddon] KRC HAS [language:] line at idx={}, totalLen={}, first80={}",
                        langIdx, langLineLen, langLine);
            } else {
                EchoLogger.info(
                        "[NetMusicEchoAddon] KRC NO [language:] line. stripped (first 200 chars): {}",
                        text != null && text.length() > 200 ? text.substring(0, 200) : text);
            }
            if (text == null || text.trim().isEmpty()) {
                EchoLogger.warn("[NetMusicEchoAddon] KRC decoded but body empty");
                return null;
            }
            return text;
        } catch (IllegalArgumentException | DataFormatException e) {
            EchoLogger.warn("[NetMusicEchoAddon] KRC decode failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 逐字节 XOR 16 字节密钥（不带 -128 减法）
     */
    private static byte[] xorDecrypt(byte[] data) {
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            out[i] = (byte) (data[i] ^ DECRYPT_KEY[i % DECRYPT_KEY.length]);
        }
        return out;
    }

    /**
     * 标准 zlib 解压（带 zlib header，不是 raw deflate）
     */
    private static byte[] inflate(byte[] data) throws DataFormatException {
        Inflater inflater = new Inflater(false);
        try {
            inflater.setInput(data);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(data.length * 2, 256));
            byte[] buf = new byte[4096];
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        break;
                    }
                }
                baos.write(buf, 0, n);
            }
            return baos.toByteArray();
        } finally {
            inflater.end();
        }
    }

    /**
     * 把 KRC 文本转成标准 LRC：
     * 保留 {@code [mm:ss.fff]} 时间标签，去掉 KRC 的逐字标记 {@code <...>} 和 {@code (offset,len)} 字标签。
     */
    private static String stripKrcTags(String krcText) {
        if (krcText.isEmpty()) {
            return "";
        }
        // KRC 逐字格式: [00:01.23]<0,120,0>这<120,200,0>是<320,150,0>逐<470,180,0>字
        // 同时去掉歌词末尾的 <...> 段以及单独的 (...) 段
        StringBuilder sb = new StringBuilder(krcText.length());
        int keptLines = 0;
        int droppedLines = 0;
        for (String line : krcText.split("\n")) {
            String trimmed = line.trim();
            int tagEnd = trimmed.indexOf(']');
            if (tagEnd < 0) {
                droppedLines++;
                if (droppedLines <= 3) {
                    EchoLogger.info(
                            "[NetMusicEchoAddon] stripKrcTags dropped line (no ]): '{}'", trimmed);
                }
                continue;
            }
            // 提取时间标签 [mm:ss.fff] 与正文
            String body = trimmed.substring(tagEnd + 1);
            // 去掉 <...> 段
            body = body.replaceAll("<[^>]*>", "");
            // 去掉 (...) 段
            body = body.replaceAll("\\([^)]*\\)", "");
            sb.append(trimmed, 0, tagEnd + 1).append(body).append('\n');
            keptLines++;
        }
        EchoLogger.info(
                "[NetMusicEchoAddon] stripKrcTags: kept={} dropped={} inputLen={} outputLen={}",
                keptLines, droppedLines, krcText.length(), sb.length());
        return sb.toString();
    }
}
