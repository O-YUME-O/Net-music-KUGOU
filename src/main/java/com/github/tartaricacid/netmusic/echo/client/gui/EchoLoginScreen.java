package com.github.tartaricacid.netmusic.echo.client.gui;

import com.github.tartaricacid.netmusic.echo.EchoLogger;
import com.github.tartaricacid.netmusic.echo.NetMusicEchoAddon;
import com.github.tartaricacid.netmusic.echo.api.KuGouLoginApi;
import com.github.tartaricacid.netmusic.echo.config.ClientConfig;
import com.github.tartaricacid.netmusic.echo.config.EchoConfig;
import com.github.tartaricacid.netmusic.echo.util.QrCodeRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class EchoLoginScreen extends Screen {
    private final Screen parent;
    private String qrKey = "";
    private String qrUrl = "";
    private boolean[][] qrMatrix = null;  // 二维码图像数据 (true=黑)
    private boolean qrChecking = false;
    private long qrStartTime = 0;
    private Component status = Component.empty();
    private Component copyFeedback = Component.empty();  // 复制成功提示

    public EchoLoginScreen(Screen parent) {
        super(Component.literal("酷狗扫码登录"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int panelWidth = 220;
        int panelX = (this.width - panelWidth) / 2;
        int panelHeight = 210;
        int panelY = (this.height - panelHeight) / 2;

        // 标题
        // 二维码面板布局：面板宽220
        // 二维码(25~97) → 文字(103~127) → 按钮(145~165) → 关闭(185)

        // 获取/刷新二维码
        if (qrKey.isEmpty() || !qrChecking) {
            generateQrCode();
        }

        Button copyButton = Button.builder(Component.literal("复制链接"), button -> copyQrUrl())
                .pos(panelX + 35, panelY + 145)
                .size(70, 20)
                .build();
        this.addRenderableWidget(copyButton);

        Button refreshButton = Button.builder(Component.literal("刷新"), button -> generateQrCode())
                .pos(panelX + 115, panelY + 145)
                .size(70, 20)
                .build();
        this.addRenderableWidget(refreshButton);

        // 关闭按钮
        Button closeButton = Button.builder(Component.literal("关闭"), button -> this.onClose())
                .pos((this.width - 60) / 2, panelY + 185)
                .size(60, 20)
                .build();
        this.addRenderableWidget(closeButton);
    }

    /**
     * 复制二维码链接到剪贴板
     */
    private void copyQrUrl() {
        if (qrUrl.isEmpty()) {
            copyFeedback = Component.literal("\u00a7c链接为空，请先获取二维码");
            return;
        }
        this.minecraft.keyboardHandler.setClipboard(qrUrl);
        copyFeedback = Component.literal("\u00a7a已复制链接到剪贴板！");
    }

    /**
     * 从酷狗服务器获取真实二维码 Key（异步）
     */
    private void generateQrCode() {
        status = Component.literal("正在获取二维码...");
        qrChecking = false;
        qrKey = "";
        qrUrl = "";
        qrMatrix = null;

        KuGouLoginApi.fetchQrKey()
                .thenAccept(result -> {
                    this.minecraft.execute(() -> {
                        if (result.isSuccess()) {
                            qrKey = result.qrcode;
                            qrUrl = result.qrUrl;
                            EchoLogger.info("QR URL received, length={}, url={}",
                                    qrUrl.length(), qrUrl.substring(0, Math.min(80, qrUrl.length())));
                            // 生成二维码图像矩阵
                            qrMatrix = QrCodeRenderer.generate(qrUrl, 120);
                            EchoLogger.info("QR matrix result: {}",
                                    qrMatrix != null ? "OK(" + qrMatrix.length + ")" : "null");
                            qrStartTime = System.currentTimeMillis();
                            status = Component.literal("请用酷狗APP扫描二维码");
                            startQrCheck();
                        } else {
                            status = Component.literal("获取二维码失败: " + result.error);
                        }
                    });
                });
    }

    private void startQrCheck() {
        qrChecking = true;
        new Thread(() -> {
            while (qrChecking && !qrKey.isEmpty()) {
                try {
                    Thread.sleep(3000);  // 每3秒轮询一次

                    if (!qrChecking || qrKey.isEmpty()) break;

                    final String keyToCheck = qrKey;
                    KuGouLoginApi.checkQrCode(keyToCheck)
                        .thenAccept(result -> {
                            this.minecraft.execute(() -> {
                                switch (result.status) {
                                    case 0:
                                        status = Component.literal("\u00a7c二维码已过期，请点击刷新");
                                        qrChecking = false;
                                        break;
                                    case 1:
                                        status = Component.literal("等待扫码...");
                                        break;
                                    case 2:
                                        status = Component.literal("\u7ea6\u5df2\u626b\u7801\uff0c\u8bf7\u5728\u624b\u673a\u4e0a\u786e\u8ba4"); // 已扫码，请在手机上确认
                                        break;
                                    case 4:
                                        status = Component.literal("\u00a7a登录成功！");
                                        NetMusicEchoAddon.saveState();
                                        // 同步cookie到配置界面的VIP Cookie字段
                                        String cookieStr = EchoConfig.getCookieString();
                                        if (!cookieStr.isEmpty()) {
                                            ClientConfig.VIP_COOKIE.set(cookieStr);
                                        }
                                        qrChecking = false;
                                        this.minecraft.setScreen(this.parent);
                                        break;
                                    default:
                                        status = Component.literal("状态异常: " + result.status + " - " + result.message);
                                }
                            });
                        });
                } catch (InterruptedException e) {
                    qrChecking = false;
                }
            }
        }).start();
    }

    // ==================== 渲染 ====================

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);

        int panelWidth = 220;
        int panelX = (this.width - panelWidth) / 2;
        int panelHeight = 210;
        int panelY = (this.height - panelHeight) / 2;
        graphics.fill(panelX - 5, panelY - 5, panelX + panelWidth + 5, panelY + panelHeight - 5, 0xAA000000);

        // 标题
        graphics.drawCenteredString(this.font, "酷狗扫码登录", this.width / 2, panelY - 20, 0xFFFFFF);

        renderQrCode(graphics, panelX, panelY);

        super.render(graphics, mouseX, mouseY, partialTick);

        // 状态栏（面板上方）
        if (!status.getString().isEmpty()) {
            graphics.drawCenteredString(this.font, this.status, this.width / 2, panelY - 18, 0xCCCCCC);
        }

        // 底部状态
        String loginStatus = EchoConfig.userid.isEmpty() ? "未登录" : ("已登录 (" + EchoConfig.userid + ")");
        graphics.drawString(this.font, "状态: " + loginStatus, 10, this.height - 35, 0xAAAAAA, false);
    }

    /**
     * 渲染二维码区域
     * 面板宽220：二维码(25~97, 72px) → 文字(103~127) → 按钮(145~165) → 关闭(185)
     */
    private void renderQrCode(GuiGraphics graphics, int panelX, int panelY) {
        if (qrMatrix == null || qrMatrix.length == 0) {
            if (qrUrl.isEmpty()) {
                graphics.drawString(this.font, "加载中...", panelX, panelY + 50, 0xAAAAAA, false);
            } else {
                graphics.drawString(this.font, "生成失败，请点击刷新", panelX, panelY + 55, 0xFF5555, false);
            }
            return;
        }

        // 二维码居中，90px（面板220宽）
        final int qrSize = 90;
        int n = qrMatrix.length;
        int ps = Math.max(1, Math.round((float) qrSize / n));
        int actualQrSize = n * ps;
        int qrX = panelX + (220 - actualQrSize) / 2; // 基于实际尺寸居中
        int qrY = panelY + 25;

        // 白色背景（匹配实际二维码尺寸+边距）
        graphics.fill(qrX - 3, qrY - 3, qrX + actualQrSize + 3, qrY + actualQrSize + 3, 0xFFFFFFFF);

        // 绘制二维码
        QrCodeRenderer.render(graphics, qrX, qrY, qrMatrix, qrSize);

        // 状态文字（二维码下方）
        int textY = qrY + actualQrSize + 6;

        // 复制成功提示
        if (copyFeedback.getString().isEmpty()) {
            graphics.drawCenteredString(this.font, "用酷狗APP扫描上方二维码", this.width / 2, textY, 0xAAAAAA);
        } else {
            graphics.drawCenteredString(this.font, copyFeedback, this.width / 2, textY, 0x55FF55);
        }

        // 等待时间
        long elapsed = (System.currentTimeMillis() - qrStartTime) / 1000;
        String timeStr = String.format("已等待: %d秒", elapsed);
        int timeColor = elapsed > 120 ? 0xFF5555 : 0x888888;
        graphics.drawCenteredString(this.font, timeStr, this.width / 2, textY + 12, timeColor);
    }

    @Override
    public void onClose() {
        qrChecking = false;
        this.minecraft.setScreen(parent);
    }
}
