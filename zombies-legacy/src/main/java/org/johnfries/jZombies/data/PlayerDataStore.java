package org.johnfries.jZombies.data;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public final class PlayerDataStore {
    private final JavaPlugin plugin;
    private final File file;
    private final Map<UUID, PlayerProfile> profiles = new HashMap<>();

    public PlayerDataStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "players.yml");
    }

    public void load() {
        profiles.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        for (String uuidKey : players.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(uuidKey);
                ConfigurationSection section = players.getConfigurationSection(uuidKey);
                if (section == null) {
                    continue;
                }
                PlayerProfile profile = new PlayerProfile(uuid);
                profile.setCoins(section.getInt("coins", 0));
                profile.setSkinTokens(section.getInt("skinTokens", 0));
                profile.setSkillPoints(section.getInt("skillPoints", 0));
                String selectedTree = section.getString("selectedSkillTree");
                if (selectedTree != null && !selectedTree.isBlank()) {
                    profile.setSelectedSkillTree(selectedTree);
                }

                ConfigurationSection skillLevels = section.getConfigurationSection("skillLevels");
                if (skillLevels != null) {
                    for (String skillKey : skillLevels.getKeys(false)) {
                        profile.setSkillLevel(skillKey, skillLevels.getInt(skillKey, 0));
                    }
                }

                Set<String> owned = Set.copyOf(section.getStringList("ownedSkins"));
                for (String skin : owned) {
                    profile.grantSkin(skin);
                }

                ConfigurationSection equipped = section.getConfigurationSection("equippedSkins");
                if (equipped != null) {
                    for (String weaponId : equipped.getKeys(false)) {
                        String skinId = equipped.getString(weaponId);
                        if (skinId != null && !skinId.isBlank()) {
                            profile.equipSkin(weaponId, skinId);
                        }
                    }
                }
                profiles.put(uuid, profile);
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Ignoring invalid UUID in players.yml: " + uuidKey);
            }
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (PlayerProfile profile : profiles.values()) {
            String path = "players." + profile.getUuid();
            yaml.set(path + ".coins", profile.getCoins());
            yaml.set(path + ".skinTokens", profile.getSkinTokens());
            yaml.set(path + ".skillPoints", profile.getSkillPoints());
            yaml.set(path + ".selectedSkillTree", profile.getSelectedSkillTree());
            yaml.set(path + ".ownedSkins", profile.getOwnedSkins().stream().sorted().toList());
            yaml.set(path + ".skillLevels", profile.getSkillLevels());
            yaml.set(path + ".equippedSkins", profile.getEquippedSkins());
        }
        try {
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Failed saving player data.", ex);
        }
    }

    public PlayerProfile getProfile(UUID uuid) {
        return profiles.computeIfAbsent(uuid, PlayerProfile::new);
    }
}
