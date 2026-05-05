package org.johnfries.jZombies.config;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Map;

public final class LangService {
    private final JavaPlugin plugin;
    private YamlConfiguration language = new YamlConfiguration();

    public LangService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load(String languageCode) {
        File langDir = new File(plugin.getDataFolder(), "lang");
        if (!langDir.exists() && !langDir.mkdirs()) {
            plugin.getLogger().warning("Could not create lang directory.");
        }

        String selected = languageCode + ".yml";
        String fallback = "en_US.yml";

        File selectedFile = new File(langDir, selected);
        if (!selectedFile.exists()) {
            saveBundledLang(selected);
        }
        File fallbackFile = new File(langDir, fallback);
        if (!fallbackFile.exists()) {
            saveBundledLang(fallback);
        }

        if (selectedFile.exists()) {
            language = YamlConfiguration.loadConfiguration(selectedFile);
            return;
        }

        language = YamlConfiguration.loadConfiguration(fallbackFile);
        plugin.getLogger().warning("Selected language file not found, using en_US.yml");
    }

    private void saveBundledLang(String name) {
        try {
            plugin.saveResource("lang/" + name, false);
        } catch (IllegalArgumentException ignored) {
            plugin.getLogger().warning("Bundled lang file does not exist: " + name);
        }
    }

    public String msg(String key) {
        String value = language.getString(key, "&cMissing message: " + key);
        return ChatColor.translateAlternateColorCodes('&', value);
    }

    public String msg(String key, Map<String, String> placeholders) {
        String result = msg(key);
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }
}
