package cn.bucheng.soulbind;

import org.bukkit.ChatColor;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class Messages {
    private final YamlConfiguration language;

    public Messages(JavaPlugin plugin) throws IOException, InvalidConfigurationException {
        language = new YamlConfiguration();
        language.load(new File(plugin.getDataFolder(), "lang.yml"));
        try (InputStream input = Objects.requireNonNull(plugin.getResource("lang.yml"));
             Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            language.setDefaults(YamlConfiguration.loadConfiguration(reader));
        }
    }

    public String text(String key) { return text(key, Map.of()); }

    public String text(String key, Map<String, String> variables) {
        return format(language.getString(key, key), variables);
    }

    public List<String> lines(String key, Map<String, String> variables) {
        return language.getStringList(key).stream().map(line -> format(line, variables)).toList();
    }

    public void send(Player player, String key) { send(player, key, Map.of()); }

    public void send(Player player, String key, Map<String, String> variables) {
        player.sendMessage(text("prefix") + text(key, variables));
    }

    public static String format(String value, Map<String, String> variables) {
        String colored = ChatColor.translateAlternateColorCodes('&', value);
        for (var entry : variables.entrySet()) colored = colored.replace("{" + entry.getKey() + "}", entry.getValue());
        return colored;
    }
}
