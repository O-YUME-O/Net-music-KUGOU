
package com.github.tartaricacid.netmusic.echo.client.gui;

import com.github.tartaricacid.netmusic.echo.NetMusicEchoAddon;
import com.github.tartaricacid.netmusic.echo.inventory.EchoSearcherMenu;
import com.github.tartaricacid.netmusic.echo.network.NetworkHandler;
import com.github.tartaricacid.netmusic.echo.network.message.EchoSearchMessage;
import com.github.tartaricacid.netmusic.echo.network.message.EchoBurnMessage;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.github.tartaricacid.netmusic.NetMusic;
import com.google.common.collect.Lists;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import org.apache.commons.lang3.StringUtils;

public class EchoSearcherScreen extends AbstractContainerScreen<EchoSearcherMenu> {
    private static final ResourceLocation BG = new ResourceLocation(NetMusic.MOD_ID, "textures/gui/cd_burner.png");
    private static final int RESULTS_PER_PAGE = 5;
    private EditBox searchBox;
    private Component tips = Component.empty();
    private int selectedIndex = -1;

    public EchoSearcherScreen(EchoSearcherMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = 176;
        this.imageHeight = 186;
    }

    @Override
    protected void init() {
        super.init();
        
        this.searchBox = new EditBox(this.font, this.leftPos + 12, this.topPos + 58, 100, 16, Component.literal("Search"));
        this.searchBox.setMaxLength(50);
        this.searchBox.setTextColor(0xFFFFFF);
        this.searchBox.setBordered(true);
        this.addWidget(this.searchBox);
        this.setInitialFocus(this.searchBox);

        this.addRenderableWidget(Button.builder(Component.translatable("gui.netmusic_echo_addon.search"), b -> performSearch())
                .pos(this.leftPos + 118, this.topPos + 56)
                .size(45, 18)
                .build());

        this.addRenderableWidget(Button.builder(Component.literal("<"), b -> previousPage())
                .pos(this.leftPos + 12, this.topPos + 140)
                .size(20, 18)
                .build());

        this.addRenderableWidget(Button.builder(Component.literal(">"), b -> nextPage())
                .pos(this.leftPos + 144, this.topPos + 140)
                .size(20, 18)
                .build());

        this.addRenderableWidget(Button.builder(Component.translatable("gui.netmusic_echo_addon.burn"), b -> burnSelected())
                .pos(this.leftPos + 56, this.topPos + 140)
                .size(66, 18)
                .build());
    }

    private void performSearch() {
        String keyword = this.searchBox.getValue();
        if (!keyword.isEmpty()) {
            NetworkHandler.CHANNEL.sendToServer(new EchoSearchMessage(keyword, 1));
            this.tips = Component.translatable("gui.netmusic_echo_addon.searching");
            this.selectedIndex = -1;
        }
    }

    private void previousPage() {
        int newPage = Math.max(1, this.menu.getCurrentPage() - 1);
        String keyword = this.searchBox.getValue();
        if (!keyword.isEmpty()) {
            NetworkHandler.CHANNEL.sendToServer(new EchoSearchMessage(keyword, newPage));
            this.selectedIndex = -1;
        }
    }

    private void nextPage() {
        int newPage = this.menu.getCurrentPage() + 1;
        String keyword = this.searchBox.getValue();
        if (!keyword.isEmpty()) {
            NetworkHandler.CHANNEL.sendToServer(new EchoSearchMessage(keyword, newPage));
            this.selectedIndex = -1;
        }
    }

    private void burnSelected() {
        if (selectedIndex >= 0 && selectedIndex < menu.getSearchResults().size()) {
            EchoSearcherMenu.SearchResult result = menu.getSearchResults().get(selectedIndex);
            
            ItemMusicCD.SongInfo songInfo = new ItemMusicCD.SongInfo();
            songInfo.songName = result.songName;
            songInfo.songTime = result.duration;
            songInfo.artists = Lists.newArrayList(result.singerName);
            if (StringUtils.isNotBlank(result.albumName)) {
                songInfo.transName = result.albumName;
            }
            
            NetworkHandler.CHANNEL.sendToServer(new EchoBurnMessage(songInfo, result.fileHash, result.albumId));
            this.tips = Component.translatable("gui.netmusic_echo_addon.burning");
        } else {
            this.tips = Component.translatable("gui.netmusic_echo_addon.no_selection");
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        renderBackground(graphics);
        int posX = this.leftPos;
        int posY = (this.height - this.imageHeight) / 2;
        graphics.blit(BG, posX, posY, 0, 0, this.imageWidth, this.imageHeight);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        this.searchBox.render(graphics, mouseX, mouseY, partialTick);
        renderResults(graphics, mouseX, mouseY);
        graphics.drawWordWrap(this.font, this.tips, this.leftPos + 12, this.topPos + 175, 152, 0xFF0000);
        renderTooltip(graphics, mouseX, mouseY);
    }

    private void renderResults(GuiGraphics graphics, int mouseX, int mouseY) {
        var results = this.menu.getSearchResults();
        int startY = this.topPos + 80;
        
        for (int i = 0; i < RESULTS_PER_PAGE && i < results.size(); i++) {
            EchoSearcherMenu.SearchResult result = results.get(i);
            int y = startY + i * 16;
            
            boolean isSelected = selectedIndex == i;
            boolean isHovered = mouseX >= this.leftPos + 12 && mouseX <= this.leftPos + 164 &&
                    mouseY >= y && mouseY < y + 14;
            
            if (isSelected || isHovered) {
                graphics.fill(this.leftPos + 11, y - 1, this.leftPos + 165, y + 14, isSelected ? 0xFFA0A0FF : 0x80A0A0A0);
            }
            
            String displayName = result.songName;
            if (displayName.length() > 28) {
                displayName = displayName.substring(0, 25) + "...";
            }
            
            graphics.drawString(this.font, displayName, this.leftPos + 14, y + 2, 0xFFFFFF, false);
            
            String artist = result.singerName;
            if (artist.length() > 18) {
                artist = artist.substring(0, 15) + "...";
            }
            graphics.drawString(this.font, artist, this.leftPos + 100, y + 2, 0xAAAAAA, false);
            
            int seconds = result.duration;
            String timeStr = String.format("%d:%02d", seconds / 60, seconds % 60);
            graphics.drawString(this.font, timeStr, this.leftPos + 148, y + 2, 0xAAAAAA, false);
        }
        
        String pageStr = String.format("Page %d", this.menu.getCurrentPage());
        int pageX = this.leftPos + (this.imageWidth - this.font.width(pageStr)) / 2;
        graphics.drawString(this.font, pageStr, pageX, this.topPos + 163, 0xFFFFFF, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int startY = this.topPos + 80;
            for (int i = 0; i < RESULTS_PER_PAGE && i < menu.getSearchResults().size(); i++) {
                int y = startY + i * 16;
                if (mouseX >= this.leftPos + 12 && mouseX <= this.leftPos + 164 &&
                        mouseY >= y && mouseY < y + 14) {
                    selectedIndex = i;
                    return true;
                }
            }
        }
        
        if (this.searchBox.mouseClicked(mouseX, mouseY, button)) {
            this.setFocused(this.searchBox);
            return true;
        }
        
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == InputConstants.KEY_RETURN && this.searchBox.isFocused()) {
            performSearch();
            return true;
        }
        if (this.searchBox.keyPressed(keyCode, scanCode, modifiers) || this.searchBox.canConsumeInput()) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        return this.searchBox.charTyped(codePoint, modifiers) || super.charTyped(codePoint, modifiers);
    }

    @Override
    protected void insertText(String text, boolean overwrite) {
        if (overwrite) {
            this.searchBox.setValue(text);
        } else {
            this.searchBox.insertText(text);
        }
    }
}
