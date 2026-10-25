package cn.bucheng.soulbind.gui;

import cn.bucheng.soulbind.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import java.util.*;

public final class SoulMenus implements Listener {
    private final SoulBindPlugin plugin;
    private static final int INPUT = 22;
    public SoulMenus(SoulBindPlugin plugin) { this.plugin = plugin; }

    public void openMain(Player player, boolean admin) {
        if (!player.isOnline() || player.isDead() || !plugin.canUse(player)) return;
        if (admin && !player.hasPermission("soulbind.admin")) { message(player, "messages.no-permission"); return; }
        SoulMenuHolder holder = create(player, admin ? SoulMenuHolder.Kind.ADMIN : SoulMenuHolder.Kind.MAIN, 54, 0,
                admin ? "gui.admin-title" : "gui.main-title", Map.of());
        renderMain(player, holder);
        player.openInventory(holder.inventory);
    }

    private void renderMain(Player player, SoulMenuHolder holder) {
        fill(holder.inventory);
        if (holder.selected == null) holder.inventory.setItem(INPUT, icon(Material.HOPPER, "empty-input", Map.of()));
        else {
            ItemStack preview = preview(holder.selected);
            var meta = preview.getItemMeta();
            List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            String name = meta.hasDisplayName() ? meta.getDisplayName() : preview.getType().name();
            lore.addAll(plugin.messages().lines("gui.selected.lore", Map.of("item", name)));
            meta.setLore(lore); preview.setItemMeta(meta);
            holder.inventory.setItem(INPUT, preview);
        }
        if (holder.kind == SoulMenuHolder.Kind.ADMIN) {
            holder.inventory.setItem(31, icon(Material.SHEARS, "force-unbind", Map.of()));
            holder.inventory.setItem(49, icon(Material.ARROW, "back", Map.of()));
        } else {
            holder.inventory.setItem(29, icon(Material.ENCHANTED_BOOK, "bind", Map.of("price", plugin.formatPrice(plugin.price(true)))));
            holder.inventory.setItem(33, icon(Material.SHEARS, "unbind", Map.of("price", plugin.formatPrice(plugin.price(false)))));
            holder.inventory.setItem(45, icon(Material.COMPARATOR, "settings", Map.of()));
            holder.inventory.setItem(49, icon(Material.ENDER_CHEST, "vault", Map.of()));
            if (player.hasPermission("soulbind.admin")) holder.inventory.setItem(53, icon(Material.NETHER_STAR, "admin", Map.of()));
        }
    }

    public void openSettings(Player player) {
        if (!player.isOnline() || player.isDead() || !plugin.canUse(player)) return;
        SoulMenuHolder holder = create(player, SoulMenuHolder.Kind.SETTINGS, 27, 0, "gui.settings-title", Map.of());
        renderSettings(player, holder); player.openInventory(holder.inventory);
    }

    private void renderSettings(Player player, SoulMenuHolder holder) {
        fill(holder.inventory);
        var settings = plugin.bindings().settings(player.getUniqueId());
        holder.inventory.setItem(11, icon(Material.FEATHER, "allow-drop", state(settings.allowDrop())));
        holder.inventory.setItem(13, icon(Material.ENDER_PEARL, "auto-return", state(settings.autoReturn())));
        holder.inventory.setItem(15, icon(Material.GOLD_INGOT, "allow-others-pickup", state(settings.allowOthersPickup())));
        holder.inventory.setItem(22, icon(Material.ARROW, "back", Map.of()));
    }

    private Map<String, String> state(boolean value) { return Map.of("state", plugin.messages().text(value ? "gui.enabled" : "gui.disabled")); }

    public void openVault(Player player, int requestedPage) {
        if (!player.isOnline() || player.isDead() || !plugin.canUse(player)) return;
        List<ItemStack> items = plugin.bindings().vault(player.getUniqueId());
        int pages = Math.max(1, (items.size() + 44) / 45);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        SoulMenuHolder holder = create(player, SoulMenuHolder.Kind.VAULT, 54, page, "gui.vault-title", Map.of("page", "" + (page + 1), "pages", "" + pages));
        holder.vault = items;
        for (int i = 0; i < 45 && page * 45 + i < items.size(); i++) holder.inventory.setItem(i, preview(items.get(page * 45 + i)));
        for (int i = 45; i < 54; i++) holder.inventory.setItem(i, pane());
        if (page > 0) holder.inventory.setItem(45, icon(Material.ARROW, "previous", Map.of()));
        holder.inventory.setItem(49, icon(Material.BARRIER, "back", Map.of()));
        if (page + 1 < pages) holder.inventory.setItem(53, icon(Material.ARROW, "next", Map.of()));
        player.openInventory(holder.inventory);
    }

    private SoulMenuHolder create(Player player, SoulMenuHolder.Kind kind, int size, int page, String title, Map<String,String> vars) {
        SoulMenuHolder holder = new SoulMenuHolder(player.getUniqueId(), kind, page);
        holder.inventory = Bukkit.createInventory(holder, size, plugin.messages().text(title, vars));
        return holder;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof SoulMenuHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.player.equals(player.getUniqueId()) || holder.busy || player.isDead()) return;
        if (!plugin.canUse(player) || holder.kind == SoulMenuHolder.Kind.ADMIN && !player.hasPermission("soulbind.admin")) {
            message(player, "messages.no-permission"); later(player::closeInventory); return;
        }
        if (!Set.of(ClickType.LEFT, ClickType.RIGHT, ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT).contains(event.getClick())) return;
        int raw = event.getRawSlot();
        if (raw < 0) return;
        if (raw >= holder.inventory.getSize()) {
            if ((holder.kind == SoulMenuHolder.Kind.MAIN || holder.kind == SoulMenuHolder.Kind.ADMIN)
                    && event.getClickedInventory() == player.getInventory() && event.getSlot() >= 0 && event.getSlot() < player.getInventory().getSize()) {
                ItemStack source = player.getInventory().getItem(event.getSlot());
                if (source != null && !source.getType().isAir()) {
                    holder.sourceSlot = event.getSlot(); holder.selected = source.clone(); holder.selected.setAmount(1);
                    renderMain(player, holder);
                }
            }
            return;
        }
        switch (holder.kind) {
            case MAIN, ADMIN -> {
                if (raw == INPUT) { holder.selected = null; holder.sourceSlot = -1; renderMain(player, holder); }
                else if (holder.kind == SoulMenuHolder.Kind.ADMIN) {
                    if (raw == 31) operate(player, holder, false, true);
                    if (raw == 49) later(() -> openMain(player, false));
                } else {
                    if (raw == 29) operate(player, holder, true, false);
                    if (raw == 33) operate(player, holder, false, false);
                    if (raw == 45) later(() -> openSettings(player));
                    if (raw == 49) later(() -> openVault(player, 0));
                    if (raw == 53 && player.hasPermission("soulbind.admin")) later(() -> openMain(player, true));
                }
            }
            case SETTINGS -> {
                var settings = plugin.bindings().settings(player.getUniqueId());
                if (raw == 22) { later(() -> openMain(player, false)); return; }
                if (raw != 11 && raw != 13 && raw != 15) return;
                boolean previous = raw == 11 ? settings.allowDrop() : raw == 13 ? settings.autoReturn() : settings.allowOthersPickup();
                try {
                    changeSetting(settings, raw, !previous);
                    message(player, "messages.setting-saved");
                } catch (RuntimeException failure) {
                    plugin.messages().send(player, "messages.operation-failed", Map.of("error", String.valueOf(failure.getMessage())));
                }
                renderSettings(player, holder);
            }
            case VAULT -> {
                if (raw == 49) { later(() -> openMain(player, false)); return; }
                if (raw == 45 && holder.page > 0) { later(() -> openVault(player, holder.page - 1)); return; }
                if (raw == 53 && (holder.page + 1) * 45 < holder.vault.size()) { later(() -> openVault(player, holder.page + 1)); return; }
                int index = holder.page * 45 + raw;
                if (raw < 45 && index < holder.vault.size()) {
                    List<ItemStack> current = plugin.bindings().vault(player.getUniqueId());
                    if (index < current.size() && current.get(index).equals(holder.vault.get(index))) {
                        try { message(player, plugin.bindings().withdraw(player, index) ? "messages.vault-withdrawn" : "messages.vault-full"); }
                        catch (RuntimeException failure) { plugin.messages().send(player, "messages.operation-failed", Map.of("error", String.valueOf(failure.getMessage()))); }
                    }
                    holder.busy = true; later(() -> openVault(player, holder.page));
                }
            }
        }
    }

    private void changeSetting(cn.bucheng.soulbind.core.PlayerSettings settings, int slot, boolean value) {
        if (slot == 11) settings.setAllowDrop(value);
        if (slot == 13) settings.setAutoReturn(value);
        if (slot == 15) settings.setAllowOthersPickup(value);
    }

    private void operate(Player player, SoulMenuHolder holder, boolean bind, boolean admin) {
        if (holder.selected == null) { message(player, "messages.select-item"); return; }
        if (admin && !player.hasPermission("soulbind.admin")) { message(player, "messages.no-permission"); return; }
        ItemStack source = player.getInventory().getItem(holder.sourceSlot);
        if (source == null || source.getAmount() < 1 || !source.isSimilar(holder.selected)) { message(player, "messages.selection-changed"); return; }
        var service = plugin.bindings();
        if (bind && service.isBound(source)) { message(player, "messages.already-bound"); return; }
        if (!bind && !service.isBound(source)) { message(player, "messages.not-bound"); return; }
        if (!bind && !service.isCurrent(source)) { message(player, "messages.stale-item"); return; }
        if (!bind && !admin && !player.getUniqueId().equals(service.ownerOf(source))) { message(player, "messages.not-owner"); return; }
        // A bound item occupies its source slot; binding one from a stack requires one free storage slot.
        if (source.getAmount() > 1 && player.getInventory().firstEmpty() < 0) { message(player, "messages.need-space"); return; }
        holder.busy = true;
        double price = admin ? 0 : plugin.price(bind);
        EconomyTransactions.Receipt receipt = null;
        ItemStack original = source.clone();
        boolean removed = false;
        try {
            receipt = plugin.transactions().charge(player, price);
            ItemStack remainder = source.clone(); remainder.setAmount(source.getAmount() - 1);
            player.getInventory().setItem(holder.sourceSlot, remainder.getAmount() == 0 ? null : remainder);
            removed = true;
            ItemStack single = original.clone(); single.setAmount(1);
            ItemStack result = bind ? service.bind(player, single) : service.unbind(single);
            if (original.getAmount() == 1) player.getInventory().setItem(holder.sourceSlot, result);
            else player.getInventory().addItem(result);
            holder.selected = null; holder.sourceSlot = -1;
            plugin.messages().send(player, admin ? "messages.admin-unbound" : bind ? "messages.bound" : "messages.unbound", Map.of("price", plugin.formatPrice(price)));
        } catch (EconomyTransactions.PaymentFailure failure) {
            plugin.messages().send(player, failure.key(), Map.of("price", plugin.formatPrice(price), "error", String.valueOf(failure.getMessage())));
        } catch (RuntimeException failure) {
            if (removed) player.getInventory().setItem(holder.sourceSlot, original);
            if (receipt != null) {
                try {
                    if (!receipt.refund()) refundFailed(player, price);
                } catch (RuntimeException refundFailure) { refundFailed(player, price); }
            }
            String errorKey = Set.of("not-single", "already-bound", "not-bound", "stale", "nested-bound").contains(String.valueOf(failure.getMessage()))
                    ? "errors." + failure.getMessage() : "errors.generic";
            plugin.messages().send(player, "messages.operation-failed", Map.of("error", plugin.messages().text(errorKey)));
            plugin.getLogger().log(java.util.logging.Level.WARNING, "物品操作失败，玩家=" + player.getUniqueId(), failure);
        } finally { holder.busy = false; renderMain(player, holder); }
    }

    private void refundFailed(Player player, double price) {
        plugin.messages().send(player, "messages.refund-failed", Map.of("price", plugin.formatPrice(price)));
        plugin.getLogger().severe("Vault退款失败，请人工处理：玩家=" + player.getUniqueId() + ", 金额=" + price);
    }

    @EventHandler(priority = EventPriority.HIGHEST) public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof SoulMenuHolder) event.setCancelled(true);
    }
    public void closeAll() { for (Player player : Bukkit.getOnlinePlayers()) if (player.getOpenInventory().getTopInventory().getHolder() instanceof SoulMenuHolder) player.closeInventory(); }
    private void later(Runnable action) { Bukkit.getScheduler().runTask(plugin, action); }
    private void message(Player player, String key) { plugin.messages().send(player, key); }
    private ItemStack icon(Material material, String key, Map<String,String> vars) {
        ItemStack item = new ItemStack(material); var meta = item.getItemMeta();
        meta.setDisplayName(plugin.messages().text("gui." + key + ".name", vars));
        meta.setLore(plugin.messages().lines("gui." + key + ".lore", vars));
        item.setItemMeta(meta); return item;
    }
    private ItemStack pane() { ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE); var meta = item.getItemMeta(); meta.setDisplayName(" "); item.setItemMeta(meta); return item; }
    private void fill(Inventory inventory) { for (int i = 0; i < inventory.getSize(); i++) inventory.setItem(i, pane()); }
    private ItemStack preview(ItemStack original) {
        ItemStack copy = original.clone(); var meta = copy.getItemMeta();
        for (var key : new HashSet<>(meta.getPersistentDataContainer().getKeys())) {
            if (key.getNamespace().equals(plugin.getName().toLowerCase(Locale.ROOT))) meta.getPersistentDataContainer().remove(key);
        }
        copy.setItemMeta(meta); return copy;
    }
}
