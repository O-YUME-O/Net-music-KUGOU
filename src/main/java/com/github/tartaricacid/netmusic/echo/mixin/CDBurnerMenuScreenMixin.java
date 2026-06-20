package com.github.tartaricacid.netmusic.echo.mixin;

import com.github.tartaricacid.netmusic.client.gui.CDBurnerMenuScreen;
import com.github.tartaricacid.netmusic.echo.EchoLogger;
import com.github.tartaricacid.netmusic.echo.NetMusicEchoAddon;
import com.github.tartaricacid.netmusic.echo.api.KuGouApiClient;
import com.github.tartaricacid.netmusic.echo.client.gui.EchoSearchScreen;
import com.github.tartaricacid.netmusic.echo.config.ClientConfig;
import com.github.tartaricacid.netmusic.echo.config.EchoConfig;
import com.github.tartaricacid.netmusic.echo.config.ProviderType;
import com.github.tartaricacid.netmusic.echo.lyric.BurnDataCache;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.github.tartaricacid.netmusic.network.NetworkHandler;
import com.github.tartaricacid.netmusic.network.message.SetMusicIDMessage;
import com.google.common.collect.Lists;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Mixin(value = CDBurnerMenuScreen.class, remap = false)
public abstract class CDBurnerMenuScreenMixin extends AbstractContainerScreen<AbstractContainerMenu> {
    @Shadow
    private EditBox textField;
    @Shadow
    private Checkbox readOnlyButton;
    @Shadow
    private Component tips;

    @Unique
    private Button netmusicecho$providerButton;
    @Unique
    private Button netmusicecho$searchButton;
    @Unique
    private EchoSearchScreen.SearchResult netmusicecho$lastKuGouResult;

    protected CDBurnerMenuScreenMixin(AbstractContainerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Inject(method = "init", at = @At("TAIL"), require = 0, remap = false)
    private void netmusicecho$init(CallbackInfo ci) {
        EchoLogger.info("=========== ECHO ADDON MIXIN CALLED! ===========");
        netmusicecho$initCommon();
    }

    @Inject(method = "resize", at = @At("TAIL"), require = 0, remap = false)
    private void netmusicecho$resize(Minecraft minecraft, int width, int height, CallbackInfo ci) {
        netmusicecho$updateSearchUi();
    }

    @Unique
    private void netmusicecho$initCommon() {
        EchoLogger.info("Initializing Echo buttons...");

        // [酷狗] [搜索] 放在制作唱片和物品栏之间的空白区域
        int rowY = this.topPos + 68;
        this.netmusicecho$providerButton = Button.builder(netmusicecho$getProviderLabel(), button -> netmusicecho$toggleProvider())
                .pos(this.leftPos + 8, rowY)
                .size(50, 20)
                .build();
        this.addRenderableWidget(this.netmusicecho$providerButton);

        this.netmusicecho$searchButton = Button.builder(Component.literal("搜索"), button -> netmusicecho$openSearch())
                .pos(this.leftPos + 60, rowY)
                .size(50, 20)
                .build();
        this.addRenderableWidget(this.netmusicecho$searchButton);

        netmusicecho$updateSearchUi();

        EchoLogger.info("Echo buttons added successfully!");
    }

    @Unique
    private Component netmusicecho$getProviderLabel() {
        return Component.literal(ClientConfig.getProvider().getDisplayName());
    }

    @Unique
    private void netmusicecho$toggleProvider() {
        ClientConfig.setProvider(ClientConfig.getProvider().next());
        this.netmusicecho$lastKuGouResult = null;
        if (this.netmusicecho$providerButton != null) {
            this.netmusicecho$providerButton.setMessage(netmusicecho$getProviderLabel());
        }
        netmusicecho$updateSearchUi();
    }

    @Unique
    private void netmusicecho$openSearch() {
        if (ClientConfig.getProvider() != ProviderType.KUGOU || this.textField == null) {
            return;
        }
        String currentText = this.textField.getValue();
        Minecraft.getInstance().setScreen(new EchoSearchScreen(
                this,
                currentText,
                result -> {
                    this.netmusicecho$lastKuGouResult = result;
                    if (this.textField != null) {
                        this.textField.setValue(result.songName);
                    }
                }));
    }

    @Inject(method = "handleCraftButton", at = @At("HEAD"), cancellable = true, require = 0)
    private void netmusicecho$handleCraftButton(CallbackInfo ci) {
        if (ClientConfig.getProvider() != ProviderType.KUGOU || this.netmusicecho$lastKuGouResult == null) {
            return;
        }

        Slot inputSlot = this.getMenu().getSlot(0);
        ItemStack cd = inputSlot.getItem();
        if (cd.isEmpty()) {
            this.tips = Component.translatable("gui.netmusic.cd_burner.cd_is_empty");
            ci.cancel();
            return;
        }

        ItemMusicCD.SongInfo existingInfo = ItemMusicCD.getSongInfo(cd);
        if (existingInfo != null && existingInfo.readOnly) {
            this.tips = Component.translatable("gui.netmusic.cd_burner.cd_read_only");
            ci.cancel();
            return;
        }

        EchoSearchScreen.SearchResult result = this.netmusicecho$lastKuGouResult;
        String url;
        try {
            url = KuGouApiClient.getSongUrl(result.fileHash, result.albumId).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            EchoLogger.error("Failed to get song URL for KuGou burn", e);
            this.tips = Component.literal("获取歌曲URL失败: " + e.getMessage());
            ci.cancel();
            return;
        }

        if (url == null || url.isEmpty()) {
            this.tips = Component.literal("获取歌曲URL失败");
            ci.cancel();
            return;
        }

        ItemMusicCD.SongInfo songInfo = new ItemMusicCD.SongInfo();
        songInfo.songName = result.songName;
        songInfo.songUrl = url;
        songInfo.songTime = result.duration;
        songInfo.artists = Lists.newArrayList(result.singerName);
        songInfo.readOnly = this.readOnlyButton != null && this.readOnlyButton.selected();

        NetworkHandler.sendToServer(new SetMusicIDMessage(songInfo));
        // 把 fileHash / albumId 写入静态缓存，供服务端 CDBurnerMenuMixin 读取。
        // （集成服务器模式下客户端/服务端共享 JVM，静态变量可传递数据）
        BurnDataCache.set(result.fileHash, result.albumId);
        EchoLogger.info("KuGou song burned: {} (hash={})", result.songName, result.fileHash);

        this.netmusicecho$lastKuGouResult = null;
        ci.cancel();
    }

    @Unique
    private void netmusicecho$updateSearchUi() {
        if (this.textField == null) {
            return;
        }
        boolean showSearch = ClientConfig.getProvider() == ProviderType.KUGOU;
        if (this.netmusicecho$searchButton != null) {
            this.netmusicecho$searchButton.visible = showSearch;
            this.netmusicecho$searchButton.active = showSearch;
        }
    }
}
