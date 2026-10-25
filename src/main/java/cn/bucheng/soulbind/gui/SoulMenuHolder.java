package cn.bucheng.soulbind.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import java.util.*;

public final class SoulMenuHolder implements InventoryHolder {
    enum Kind { MAIN, ADMIN, SETTINGS, VAULT }
    final UUID player;
    final Kind kind;
    final int page;
    Inventory inventory;
    int sourceSlot = -1;
    ItemStack selected;
    List<ItemStack> vault = List.of();
    boolean busy;
    SoulMenuHolder(UUID player, Kind kind, int page) { this.player = player; this.kind = kind; this.page = page; }
    @Override public Inventory getInventory() { return Objects.requireNonNull(inventory); }
}
