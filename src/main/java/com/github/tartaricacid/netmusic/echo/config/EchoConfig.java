package com.github.tartaricacid.netmusic.echo.config;

import java.util.HashMap;
import java.util.Map;

/**
 * 酷狗登录状态与持久化配置。
 * 注意：不直接依赖 ClothConfig2，避免未安装时触发类加载错误。
 * ClothConfig 配置页面逻辑已移至 EchoConfigScreen。
 */
public class EchoConfig {
    public static Map<String, String> cookies = new HashMap<>();
    public static String token = "";
    public static String userid = "";
    public static String mid = "";
    public static String dfid = "";
    public static String guid = "";
    public static String vipType = "";
    public static String vipToken = "";

    /** 标记配置已变更，需要持久化 */
    public static volatile boolean dirty = false;

    public static void markDirty() {
        dirty = true;
    }

    public static boolean isLoggedIn() {
        return token != null && !token.isEmpty() && userid != null && !userid.isEmpty();
    }

    public static void addCookie(String name, String value) {
        cookies.put(name, value);
    }

    public static String getCookieString() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(entry.getKey()).append("=").append(entry.getValue());
        }
        return sb.toString();
    }

    public static void clearCookies() {
        cookies.clear();
        token = "";
        userid = "";
        vipType = "";
        vipToken = "";
    }
}