
package com.github.tartaricacid.netmusic.echo.inventory;

import com.github.tartaricacid.netmusic.echo.init.InitContainer;
import com.github.tartaricacid.netmusic.echo.network.message.EchoBurnMessage;
import com.github.tartaricacid.netmusic.init.InitItems;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.SlotItemHandler;

import java.util.ArrayList;
import java.util.List;

public class EchoSearcherMenu extends AbstractContainerMenu {
    private final ItemStackHandler input = new ItemStackHandler() {
        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return stack.getItem() == InitItems.MUSIC_CD.get();
        }
    };
    private final ItemStackHandler output = new ItemStackHandler() {
        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return false;
        }

        @Override
        protected int getStackLimit(int slot, ItemStack stack) {
            return 1;
        }
    };
    private final List<SearchResult> searchResults = new ArrayList<>();
    private int currentPage = 1;

    public EchoSearcherMenu(int id, Inventory inventory) {
        super(InitContainer.ECHO_SEARCHER.get(), id);

        this.addSlot(new SlotItemHandler(input, 0, 147, 14));
        this.addSlot(new SlotItemHandler(output, 0, 147, 67));

        for (int i = 0; i < 9; ++i) {
            this.addSlot(new Slot(inventory, i, 8 + i * 18, 152));
        }

        for (int i = 0; i < 3; ++i) {
            for (int j = 0; j < 9; ++j) {
                this.addSlot(new Slot(inventory, j + i * 9 + 9, 8 + j * 18, 94 + i * 18));
            }
        }
    }

    public List<SearchResult> getSearchResults() {
        return searchResults;
    }

    public void setSearchResults(List<SearchResult> results) {
        this.searchResults.clear();
        this.searchResults.addAll(results);
    }

    public int getCurrentPage() {
        return currentPage;
    }

    public void setCurrentPage(int page) {
        this.currentPage = page;
    }

    public ItemStackHandler getInput() {
        return input;
    }

    public ItemStackHandler getOutput() {
        return output;
    }

    public void burnCD(ItemMusicCD.SongInfo songInfo) {
        if (!this.input.getStackInSlot(0).isEmpty() && this.output.getStackInSlot(0).isEmpty()) {
            ItemStack itemStack = this.input.extractItem(0, 1, false);
            ItemMusicCD.SongInfo rawSongInfo = ItemMusicCD.getSongInfo(itemStack);
            if (rawSongInfo == null || !rawSongInfo.readOnly) {
                ItemMusicCD.setSongInfo(songInfo, itemStack);
            }
            this.output.setStackInSlot(0, itemStack);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack itemStack = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack slotItem = slot.getItem();
            itemStack = slotItem.copy();
            if (index < 2) {
                if (!this.moveItemStackTo(slotItem, 2, this.slots.size(), false)) {
                    return ItemStack.EMPTY;
                }
            } else if (!this.moveItemStackTo(slotItem, 0, 2, true)) {
                return ItemStack.EMPTY;
            }

            if (slotItem.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
        }

        return itemStack;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        ItemHandlerHelper.giveItemToPlayer(player, input.getStackInSlot(0));
        ItemHandlerHelper.giveItemToPlayer(player, output.getStackInSlot(0));
    }

    public static class SearchResult {
        public String songName;
        public String singerName;
        public String albumName;
        public int duration;
        public String songUrl;
        public String fileHash;
        public String albumId;

        public SearchResult(String songName, String singerName, String albumName, int duration, String songUrl, String fileHash, String albumId) {
            this.songName = songName;
            this.singerName = singerName;
            this.albumName = albumName;
            this.duration = duration;
            this.songUrl = songUrl;
            this.fileHash = fileHash;
            this.albumId = albumId;
        }
    }
}
