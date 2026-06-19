package com.github.tartaricacid.netmusic.echo.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class CryptoUtils {
    private CryptoUtils() {}

    public static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not found", e);
        }
    }

    public static String randomString(int length) {
        StringBuilder sb = new StringBuilder();
        String chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        for (int i = 0; i < length; i++) {
            int index = (int) (Math.random() * chars.length());
            sb.append(chars.charAt(index));
        }
        return sb.toString();
    }

    public static String randomNumber(int length) {
        StringBuilder sb = new StringBuilder();
        String chars = "1234567890";
        for (int i = 0; i < length; i++) {
            int index = (int) (Math.random() * chars.length());
            sb.append(chars.charAt(index));
        }
        return sb.toString();
    }

    /**
     * 生成 UUID v4 格式的 GUID
     */
    public static String generateGuid() {
        java.util.function.Supplier<String> e = () -> {
            int val = (int) (65536 * (1 + Math.random()));
            return String.format("%04x", val).substring(1);
        };
        return e.get() + e.get() + "-" + e.get() + "-" + e.get() + "-" + e.get() + "-" + e.get() + e.get() + e.get();
    }
}