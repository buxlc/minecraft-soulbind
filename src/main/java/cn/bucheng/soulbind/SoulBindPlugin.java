package cn.bucheng.soulbind;

import cn.bucheng.soulbind.core.BindingService;
import cn.bucheng.soulbind.gui.SoulMenus;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class SoulBindPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {
    private Messages messages;
    private BindingService bindings;
    private SoulMenus menus;
    private EconomyTransactions transactions;

    @Override public void onEnable() {
        try {
            saveDefaultConfig();
            if (!new File(getDataFolder(), "lang.yml").exists()) saveResource("lang.yml", false);
            reloadSettings();
            bindings = new BindingService(this);
            configureCore();
            bindings.load();
            transactions = new EconomyTransactions(() -> {
                var service = getServer().getServicesManager().getRegistration(Economy.class);
                return service == null ? null : service.getProvider();
            });
            menus = new SoulMenus(this);
            getServer().getPluginManager().registerEvents(bindings, this);
            getServer().getPluginManager().registerEvents(menus, this);
            Objects.requireNonNull(getCommand("soulbind")).setExecutor(this);
            Objects.requireNonNull(getCommand("soulbind")).setTabCompleter(this);
            getLogger().info("灵魂绑定已启用，绑定价格=" + price(true) + "，解绑价格=" + price(false));
        } catch (Exception exception) {
            getLogger().log(java.util.logging.Level.SEVERE, "启动失败；请检查配置和持久化数据。", exception);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void configureCore() {
        bindings.setMessages((key, vars) -> messages.text("prefix") + messages.text(key, vars));
        bindings.setLoreFormat(messages.text("lore.bound"));
    }

    public void reloadSettings() throws Exception {
        YamlConfiguration candidate = new YamlConfiguration();
        candidate.load(new File(getDataFolder(), "config.yml"));
        try (var input = Objects.requireNonNull(getResource("config.yml"));
             var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            candidate.setDefaults(YamlConfiguration.loadConfiguration(reader));
        }
        validatePrice(candidate.get("prices.bind"));
        validatePrice(candidate.get("prices.unbind"));
        for (String key : List.of("allow-drop", "auto-return", "allow-others-pickup")) {
            if (!(candidate.get("defaults." + key) instanceof Boolean)) throw new IllegalArgumentException("defaults." + key + " 必须为 true 或 false");
        }
        Messages candidateLanguage = new Messages(this);
        if (!candidateLanguage.text("lore.bound").contains("{owner}")) throw new IllegalArgumentException("lore.bound 必须包含 {owner}");
        reloadConfig();
        messages = candidateLanguage;
        if (bindings != null) configureCore();
        if (menus != null) menus.closeAll();
    }

    public static double validatePrice(Object value) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue()) || number.doubleValue() < 0) {
            throw new IllegalArgumentException("绑定和解绑价格必须为大于等于 0 的有限数字");
        }
        return number.doubleValue();
    }

    @Override public void onDisable() {
        if (menus != null) menus.closeAll();
        if (bindings != null) {
            try { bindings.shutdown(); }
            catch (Exception exception) { getLogger().log(java.util.logging.Level.SEVERE, "保存绑定数据失败", exception); }
        }
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String action = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (action.equals("reload")) {
            if (!sender.hasPermission("soulbind.reload") && !sender.hasPermission("soulbind.admin")) { sender.sendMessage(messages.text("messages.no-permission")); return true; }
            try { reloadSettings(); sender.sendMessage(messages.text("prefix") + messages.text("messages.reloaded")); }
            catch (Exception exception) {
                sender.sendMessage(messages.text("prefix") + messages.text("messages.reload-failed", Map.of("error", String.valueOf(exception.getMessage()))));
                getLogger().warning("配置重载失败：" + exception.getMessage());
            }
            return true;
        }
        if (!(sender instanceof Player player)) { sender.sendMessage(messages.text("messages.player-only")); return true; }
        if (!canUse(player)) { messages.send(player, "messages.no-permission"); return true; }
        switch (action) {
            case "", "gui" -> menus.openMain(player, false);
            case "settings" -> menus.openSettings(player);
            case "vault" -> menus.openVault(player, 0);
            case "admin" -> { if (player.hasPermission("soulbind.admin")) menus.openMain(player, true); else messages.send(player, "messages.no-permission"); }
            default -> messages.send(player, "messages.help");
        }
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        List<String> options = new ArrayList<>(List.of("settings", "vault"));
        if (sender.hasPermission("soulbind.admin")) options.add("admin");
        if (sender.hasPermission("soulbind.reload") || sender.hasPermission("soulbind.admin")) options.add("reload");
        return options.stream().filter(v -> v.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
    }

    public boolean canUse(Player player) { return player.hasPermission("soulbind.use") || player.hasPermission("soulbind.admin"); }
    public Messages messages() { return messages; }
    public BindingService bindings() { return bindings; }
    public EconomyTransactions transactions() { return transactions; }
    public double price(boolean bind) { return getConfig().getDouble(bind ? "prices.bind" : "prices.unbind", 1000); }
    public String formatPrice(double price) { return java.math.BigDecimal.valueOf(price).stripTrailingZeros().toPlainString(); }
}
