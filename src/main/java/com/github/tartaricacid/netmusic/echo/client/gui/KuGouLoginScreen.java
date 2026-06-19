package com.github.tartaricacid.netmusic.echo.client.gui;

import com.github.tartaricacid.netmusic.echo.api.KuGouLoginApi;
import com.github.tartaricacid.netmusic.echo.util.QrCodeRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class KuGouLoginScreen extends Screen {
    private final Runnable onLoginSuccess;
    private Button closeButton;
    private Button refreshButton;
    private Component status = Component.literal("正在获取二维码...");
    private String qrKey = "";
    private String qrCodeUrl = "";
    private boolean[][] qrMatrix = null;  // 二维码图像数据 (true=黑)
    private int currentState = -1; // -1=idle, 0=expired, 1=waiting, 2=confirming, 4=success
    private ScheduledExecutorService pollExecutor;
    private volatile boolean isPolling = false;

    public KuGouLoginScreen(Runnable onLoginSuccess) {
        super(Component.literal("酷狗登录"));
        this.onLoginSuccess = onLoginSuccess;
    }

    @Override
    protected void init() {
        int buttonWidth = 100;
        int buttonHeight = 20;
        int closeX = (this.width - buttonWidth) / 2;
        int closeY = this.height - 50;

        this.closeButton = Button.builder(Component.literal("关闭"), button -> onClose())
                .pos(closeX, closeY)
                .size(buttonWidth, buttonHeight)
                .build();
        this.addRenderableWidget(this.closeButton);

        int refreshX = (this.width - buttonWidth) / 2;
        int refreshY = closeY - 30;

        this.refreshButton = Button.builder(Component.literal("刷新二维码"), button -> fetchQrCode())
                .pos(refreshX, refreshY)
                .size(buttonWidth, buttonHeight)
                .build();
        this.addRenderableWidget(this.refreshButton);

        fetchQrCode();
    }

    private void fetchQrCode() {
        this.currentState = -1;
        this.status = Component.literal("正在获取二维码...");
        this.qrMatrix = null;

        // 从酷狗服务器获取真实二维码 Key（异步）
        KuGouLoginApi.fetchQrKey().thenAccept(result -> {
            this.minecraft.execute(() -> {
                if (result.isSuccess()) {
                    this.qrKey = result.qrcode;
                    this.qrCodeUrl = result.qrUrl;
                    // 生成二维码图像
                    this.qrMatrix = QrCodeRenderer.generate(qrCodeUrl, 120);
                    this.currentState = 1;
                    this.status = Component.literal("请使用酷狗APP扫描二维码");
                    startPolling();
                } else {
                    this.status = Component.literal("获取二维码失败: " + result.error);
                    this.currentState = 0;
                }
            });
        });
    }

    private void startPolling() {
        if (isPolling) {
            stopPolling();
        }

        isPolling = true;
        pollExecutor = Executors.newSingleThreadScheduledExecutor();
        pollExecutor.scheduleAtFixedRate(() -> {
            if (!isPolling) return;

            KuGouLoginApi.checkQrCode(qrKey).thenAccept(result -> {
                this.currentState = result.status;

                switch (result.status) {
                    case 4: // 成功
                        this.status = Component.literal("登录成功!");
                        stopPolling();
                        if (onLoginSuccess != null) {
                            onLoginSuccess.run();
                        }
                        onClose();
                        break;
                    case 0: // 过期
                        this.status = Component.literal("二维码已过期，请刷新");
                        stopPolling();
                        break;
                    case 2: // 待确认
                        this.status = Component.literal("请在手机端确认登录");
                        break;
                    case 1: // 等待扫码
                        this.status = Component.literal("等待扫码...");
                        break;
                    default:
                        if (result.status < 0) {
                            this.status = Component.literal("检查失败: " + result.message);
                            stopPolling();
                        }
                        break;
                }
            });
        }, 0, 3, TimeUnit.SECONDS);
    }

    private void stopPolling() {
        isPolling = false;
        if (pollExecutor != null) {
            pollExecutor.shutdownNow();
            pollExecutor = null;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);

        int centerX = this.width / 2;
        int startY = this.height / 2 - 110;

        graphics.drawCenteredString(this.font, this.title, centerX, startY, 0xFFFFFF);

        int color = currentState == 4 ? 0x00FF00 :
                (currentState == -2 || currentState == 0 ? 0xFF0000 : 0xAAAAAA);
        graphics.drawCenteredString(this.font, this.status, centerX, startY + 25, color);

        // 绘制二维码图像（替代文字 URL）
        if (qrMatrix != null && qrMatrix.length > 0) {
            final int qrSize = 100;
            int qrX = centerX - qrSize / 2;
            int qrY = startY + 45;

            // 白色背景
            graphics.fill(qrX - 3, qrY - 3, qrX + qrSize + 3, qrY + qrSize + 3, 0xFFFFFFFF);
            QrCodeRenderer.render(graphics, qrX, qrY, qrMatrix, qrSize);

            graphics.drawCenteredString(this.font, "用酷狗APP扫描上方二维码", centerX, qrY + qrSize + 6, 0xAAAAAA);
        } else if (qrCodeUrl.isEmpty()) {
            graphics.drawCenteredString(this.font, "加载中...", centerX, startY + 80, 0xAAAAAA);
        } else {
            graphics.drawCenteredString(this.font, "生成二维码失败，请点击刷新", centerX, startY + 80, 0xFF5555);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        stopPolling();
        Minecraft.getInstance().setScreen(null);
    }

    @Override
    public void removed() {
        stopPolling();
        super.removed();
    }
}
