package org.johnfries.jZombies.config;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.java.JavaPlugin;
import org.johnfries.jZombies.model.MobSpawnDefinition;
import org.johnfries.jZombies.model.SkillDefinition;
import org.johnfries.jZombies.model.SkillTree;
import org.johnfries.jZombies.model.SkillType;
import org.johnfries.jZombies.model.WaveDefinition;
import org.johnfries.jZombies.model.WeaponDefinition;
import org.johnfries.jZombies.model.WeaponSkin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ConfigService {
    private final JavaPlugin plugin;

    private String language;
    private boolean mythicEnabled;
    private int startingCoins;
    private int waveIntervalSeconds;
    private int gameStartDelaySeconds;
    private int downedBleedoutSeconds;
    private int reviveBaseSeconds;
    private int barricadeBaseTicks;
    private int skinTokensPerWave;
    private int maxRounds;
    private Material barricadeRepairMaterial;
    private Set<Material> barricadeAnchorMaterials = Set.of();
    private List<Location> spawnPoints = List.of();
    private List<WaveDefinition> waves = List.of();
    private Map<String, WeaponDefinition> weapons = Map.of();
    private Map<String, WeaponSkin> skins = Map.of();
    private Map<String, SkillTree> skillTrees = Map.of();

    public ConfigService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadOrReload() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();

        language = config.getString("language", "en_US");
        mythicEnabled = config.getBoolean("mythic.enabled", true);
        startingCoins = config.getInt("game.starting-coins", 500);
        waveIntervalSeconds = config.getInt("game.wave-interval-seconds", 8);
        gameStartDelaySeconds = config.getInt("game.start-delay-seconds", 10);
        downedBleedoutSeconds = config.getInt("game.downed-bleedout-seconds", 25);
        reviveBaseSeconds = config.getInt("game.revive-base-seconds", 5);
        barricadeBaseTicks = config.getInt("game.barricade-base-ticks", 40);
        skinTokensPerWave = config.getInt("game.skin-tokens-per-wave", 1);
        maxRounds = config.getInt("game.max-rounds", 30);
        barricadeRepairMaterial = Material.matchMaterial(config.getString("game.barricade-repair-material", "OAK_PLANKS"));
        if (barricadeRepairMaterial == null) {
            barricadeRepairMaterial = Material.OAK_PLANKS;
        }
        barricadeAnchorMaterials = parseMaterials(config.getStringList("game.barricade-anchor-materials"));
        spawnPoints = parseLocations(config.getMapList("game.spawn-points"));
        waves = parseWaves(config.getConfigurationSection("waves"));
        weapons = parseWeapons(config.getConfigurationSection("weapons"));
        skins = parseSkins(config.getConfigurationSection("skins"));
        skillTrees = parseSkillTrees(config.getConfigurationSection("skill-trees"));
    }

    private Map<String, WeaponDefinition> parseWeapons(ConfigurationSection section) {
        Map<String, WeaponDefinition> parsed = new HashMap<>();
        if (section == null) {
            return parsed;
        }
        for (String weaponId : section.getKeys(false)) {
            String normalized = weaponId.toLowerCase(Locale.ROOT);
            ConfigurationSection weaponSection = section.getConfigurationSection(weaponId);
            if (weaponSection == null) {
                continue;
            }
            Material material = Material.matchMaterial(weaponSection.getString("material", "WOODEN_HOE"));
            if (material == null) {
                plugin.getLogger().warning("Invalid material for weapon '" + weaponId + "'. Falling back to WOODEN_HOE.");
                material = Material.WOODEN_HOE;
            }
            WeaponDefinition definition = new WeaponDefinition(
                    normalized,
                    weaponSection.getString("display", weaponId),
                    material,
                    weaponSection.getInt("price", 1000),
                    weaponSection.getInt("model-data", 0)
            );
            parsed.put(normalized, definition);
        }
        return parsed;
    }

    private Map<String, WeaponSkin> parseSkins(ConfigurationSection section) {
        Map<String, WeaponSkin> parsed = new HashMap<>();
        if (section == null) {
            return parsed;
        }
        for (String skinId : section.getKeys(false)) {
            ConfigurationSection skinSection = section.getConfigurationSection(skinId);
            if (skinSection == null) {
                continue;
            }
            Set<String> allowed = new HashSet<>();
            for (String weaponId : skinSection.getStringList("allowed-weapons")) {
                allowed.add(weaponId.toLowerCase(Locale.ROOT));
            }
            WeaponSkin skin = new WeaponSkin(
                    skinId,
                    skinSection.getString("display", skinId),
                    skinSection.getInt("custom-model-data", 0),
                    skinSection.getInt("price", 1000),
                    allowed
            );
            parsed.put(skinId.toLowerCase(Locale.ROOT), skin);
        }
        return parsed;
    }

    private Map<String, SkillTree> parseSkillTrees(ConfigurationSection section) {
        Map<String, SkillTree> parsed = new HashMap<>();
        if (section == null) {
            return parsed;
        }
        for (String treeId : section.getKeys(false)) {
            ConfigurationSection treeSection = section.getConfigurationSection(treeId);
            if (treeSection == null) {
                continue;
            }
            ConfigurationSection skillsSection = treeSection.getConfigurationSection("skills");
            Map<String, SkillDefinition> skills = new HashMap<>();
            if (skillsSection != null) {
                for (String skillId : skillsSection.getKeys(false)) {
                    String normalizedSkillId = skillId.toLowerCase(Locale.ROOT);
                    ConfigurationSection skillSection = skillsSection.getConfigurationSection(skillId);
                    if (skillSection == null) {
                        continue;
                    }
                    SkillType skillType;
                    try {
                        skillType = SkillType.valueOf(skillSection.getString("type", "COIN_GAIN").toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException ex) {
                        plugin.getLogger().warning("Unknown skill type for '" + treeId + "." + skillId + "', using COIN_GAIN.");
                        skillType = SkillType.COIN_GAIN;
                    }
                    SkillDefinition skill = new SkillDefinition(
                            normalizedSkillId,
                            skillSection.getString("display", skillId),
                            skillSection.getString("description", ""),
                            skillType,
                            skillSection.getInt("max-level", 5),
                            skillSection.getInt("base-cost", 1),
                            skillSection.getDouble("value-per-level", 0.1D)
                    );
                    skills.put(normalizedSkillId, skill);
                }
            }
            parsed.put(treeId.toLowerCase(Locale.ROOT), new SkillTree(
                    treeId.toLowerCase(Locale.ROOT),
                    treeSection.getString("display", treeId),
                    skills
            ));
        }
        return parsed;
    }

    private List<WaveDefinition> parseWaves(ConfigurationSection section) {
        if (section == null) {
            return List.of();
        }
        List<String> keys = new ArrayList<>(section.getKeys(false));
        keys.sort(Comparator.comparingInt(this::parseInt));

        List<WaveDefinition> parsed = new ArrayList<>();
        for (String key : keys) {
            ConfigurationSection waveSection = section.getConfigurationSection(key);
            if (waveSection == null) {
                continue;
            }
            int waveNumber = parseInt(key);
            int skillPoints = waveSection.getInt("skill-points", 1);
            List<MobSpawnDefinition> mobs = new ArrayList<>();
            for (Map<?, ?> entry : waveSection.getMapList("mobs")) {
                String id = string(entry.get("id"), "mob");
                String mythic = string(entry.get("mythic"), "");
                String typeInput = string(entry.get("type"), "ZOMBIE");
                EntityType entityType = EntityType.fromName(typeInput.toLowerCase(Locale.ROOT));
                if (entityType == null) {
                    try {
                        entityType = EntityType.valueOf(typeInput.toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException ex) {
                        entityType = EntityType.ZOMBIE;
                    }
                }
                mobs.add(new MobSpawnDefinition(
                        id,
                        entityType,
                        mythic.isBlank() ? null : mythic,
                        parseInt(entry.get("amount"), 1),
                        parseDouble(entry.get("health"), 20.0D),
                        parseDouble(entry.get("speed-multiplier"), 1.0D),
                        parseDouble(entry.get("damage"), 3.0D),
                        parseInt(entry.get("kill-reward"), 20)
                ));
            }
            parsed.add(new WaveDefinition(waveNumber, mobs, skillPoints));
        }
        return parsed;
    }

    private List<Location> parseLocations(List<Map<?, ?>> maps) {
        List<Location> parsed = new ArrayList<>();
        for (Map<?, ?> map : maps) {
            String worldName = string(map.get("world"), "");
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                continue;
            }
            double x = parseDouble(map.get("x"), world.getSpawnLocation().getX());
            double y = parseDouble(map.get("y"), world.getSpawnLocation().getY());
            double z = parseDouble(map.get("z"), world.getSpawnLocation().getZ());
            float yaw = (float) parseDouble(map.get("yaw"), 0.0D);
            float pitch = (float) parseDouble(map.get("pitch"), 0.0D);
            parsed.add(new Location(world, x, y, z, yaw, pitch));
        }
        return parsed;
    }

    private int parseInt(String input) {
        try {
            return Integer.parseInt(input);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private Set<Material> parseMaterials(List<String> input) {
        Set<Material> materials = new HashSet<>();
        for (String name : input) {
            Material material = Material.matchMaterial(name);
            if (material != null) {
                materials.add(material);
            }
        }
        return materials;
    }

    private int parseInt(Object input, int defaultValue) {
        if (input instanceof Number number) {
            return number.intValue();
        }
        if (input instanceof String string) {
            try {
                return Integer.parseInt(string);
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private double parseDouble(Object input, double defaultValue) {
        if (input instanceof Number number) {
            return number.doubleValue();
        }
        if (input instanceof String string) {
            try {
                return Double.parseDouble(string);
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private String string(Object input, String fallback) {
        if (input == null) {
            return fallback;
        }
        String value = String.valueOf(input);
        return value.isBlank() ? fallback : value;
    }

    public String getLanguage() {
        return language;
    }

    public boolean isMythicEnabled() {
        return mythicEnabled;
    }

    public int getStartingCoins() {
        return startingCoins;
    }

    public int getWaveIntervalSeconds() {
        return waveIntervalSeconds;
    }

    public int getGameStartDelaySeconds() {
        return gameStartDelaySeconds;
    }

    public int getDownedBleedoutSeconds() {
        return downedBleedoutSeconds;
    }

    public int getReviveBaseSeconds() {
        return reviveBaseSeconds;
    }

    public int getMaxRounds() {
        return maxRounds;
    }

    public int getBarricadeBaseTicks() {
        return barricadeBaseTicks;
    }

    public int getSkinTokensPerWave() {
        return skinTokensPerWave;
    }

    public Material getBarricadeRepairMaterial() {
        return barricadeRepairMaterial;
    }

    public Set<Material> getBarricadeAnchorMaterials() {
        return barricadeAnchorMaterials;
    }

    public List<Location> getSpawnPoints() {
        return spawnPoints;
    }

    public List<WaveDefinition> getWaves() {
        return waves;
    }

    public Map<String, WeaponDefinition> getWeapons() {
        return weapons;
    }

    public Map<String, WeaponSkin> getSkins() {
        return skins;
    }

    public Map<String, SkillTree> getSkillTrees() {
        return skillTrees;
    }
}
