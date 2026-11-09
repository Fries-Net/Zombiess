package org.johnfries.jZombies.config;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.java.JavaPlugin;
import org.johnfries.jZombies.model.ArmorTier;
import org.johnfries.jZombies.model.BuyableArea;
import org.johnfries.jZombies.model.MobSpawnDefinition;
import org.johnfries.jZombies.model.RangeTarget;
import org.johnfries.jZombies.model.SkillDefinition;
import org.johnfries.jZombies.model.SkillTree;
import org.johnfries.jZombies.model.SkillType;
import org.johnfries.jZombies.model.StationDefinition;
import org.johnfries.jZombies.model.StationType;
import org.johnfries.jZombies.model.VendingUpgrade;
import org.johnfries.jZombies.model.VendingUpgradeType;
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
    private int maxWeapons;
    private int waveDurationSeconds;
    private boolean lockInventory;
    private boolean disableHungerLoss;
    private boolean scoreboardEnabled;
    private int scoreboardUpdateTicks;
    private String scoreboardTitle;
    private Material goldDropMaterial;
    private String goldDropName;
    private int goldDropPickupDelayTicks;
    private int killBaseGoldDrop;
    private double damageGoldMultiplier;
    private Material barricadeRepairMaterial;
    private Set<Material> barricadeAnchorMaterials = Set.of();
    private List<Location> spawnPoints = List.of();
    private List<WaveDefinition> waves = List.of();
    private Map<String, WeaponDefinition> weapons = Map.of();
    private Map<String, WeaponSkin> skins = Map.of();
    private Map<String, SkillTree> skillTrees = Map.of();
    private List<BuyableArea> buyableAreas = List.of();
    private Map<String, StationDefinition> stationByBlockKey = Map.of();
    private Map<String, VendingUpgrade> vendingUpgrades = Map.of();
    private List<ArmorTier> armorTiers = List.of();
    private List<RangeTarget> rangeTargets = List.of();
    private boolean rangeResetOnMiss;
    private int rangeMaxDistance;

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
        waveDurationSeconds = config.getInt("game.wave-duration-seconds", 180);
        gameStartDelaySeconds = config.getInt("game.start-delay-seconds", 10);
        downedBleedoutSeconds = config.getInt("game.downed-bleedout-seconds", 25);
        reviveBaseSeconds = config.getInt("game.revive-base-seconds", 5);
        barricadeBaseTicks = config.getInt("game.barricade-base-ticks", 40);
        skinTokensPerWave = config.getInt("game.skin-tokens-per-wave", 1);
        maxRounds = config.getInt("game.max-rounds", 30);
        maxWeapons = config.getInt("game.max-weapons", 3);
        lockInventory = config.getBoolean("game.lock-inventory", true);
        disableHungerLoss = config.getBoolean("game.disable-hunger-loss", true);
        killBaseGoldDrop = config.getInt("game.kill-base-gold-drop", 8);
        damageGoldMultiplier = config.getDouble("game.damage-gold-multiplier", 0.8D);

        ConfigurationSection goldDropSection = config.getConfigurationSection("game.gold-drop");
        goldDropMaterial = Material.matchMaterial(goldDropSection == null ? "GOLD_NUGGET" : goldDropSection.getString("material", "GOLD_NUGGET"));
        if (goldDropMaterial == null) {
            goldDropMaterial = Material.GOLD_NUGGET;
        }
        goldDropName = goldDropSection == null ? "&#FFD54FGold" : goldDropSection.getString("name", "&#FFD54FGold");
        goldDropPickupDelayTicks = goldDropSection == null ? 5 : goldDropSection.getInt("pickup-delay-ticks", 5);

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
        buyableAreas = parseAreas(config.getConfigurationSection("areas"));
        vendingUpgrades = parseVendingUpgrades(config.getConfigurationSection("vending-upgrades"));
        stationByBlockKey = parseStations(config.getConfigurationSection("stations"), vendingUpgrades);
        armorTiers = parseArmorTiers(config.getConfigurationSection("armor-tiers"));
        rangeTargets = parseRangeTargets(config.getConfigurationSection("shooting-range.targets"));
        rangeResetOnMiss = config.getBoolean("shooting-range.reset-on-miss", true);
        rangeMaxDistance = config.getInt("shooting-range.max-distance", 80);

        ConfigurationSection score = config.getConfigurationSection("scoreboard");
        scoreboardEnabled = score == null || score.getBoolean("enabled", true);
        scoreboardUpdateTicks = score == null ? 20 : score.getInt("update-ticks", 20);
        scoreboardTitle = score == null ? "&#FBC02D&lBLOCKING DEAD" : score.getString("title", "&#FBC02D&lBLOCKING DEAD");
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
                    weaponSection.getInt("model-data", 0),
                    weaponSection.getInt("clip-size", 12),
                    weaponSection.getInt("max-reserve-ammo", 84),
                    weaponSection.getDouble("damage", 6.0D),
                    weaponSection.getDouble("headshot-multiplier", 1.8D),
                    weaponSection.getInt("pellets", 1),
                    weaponSection.getDouble("spread", 0.015D),
                    weaponSection.getDouble("range", 72.0D),
                    weaponSection.getInt("fire-cooldown-ticks", 4),
                    weaponSection.getInt("reload-ticks", 40),
                    weaponSection.getBoolean("loot-box-weapon", true),
                    weaponSection.getInt("loot-weight", 10),
                    weaponSection.getString("shoot-sound", "ENTITY_FIREWORK_ROCKET_BLAST"),
                    weaponSection.getString("reload-sound", "ITEM_ARMOR_EQUIP_IRON")
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
                    skinId.toLowerCase(Locale.ROOT),
                    skinSection.getString("display", skinId),
                    skinSection.getInt("custom-model-data", 0),
                    skinSection.getInt("price", 1000),
                    allowed
            );
            parsed.put(skin.id(), skin);
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

    private List<BuyableArea> parseAreas(ConfigurationSection section) {
        List<BuyableArea> parsed = new ArrayList<>();
        if (section == null) {
            return parsed;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection area = section.getConfigurationSection(id);
            if (area == null) {
                continue;
            }
            Location interact = parseLocation(area.getConfigurationSection("interact-block"));
            if (interact == null) {
                continue;
            }
            List<Location> openBlocks = parseLocations(area.getMapList("open-blocks"));
            parsed.add(new BuyableArea(
                    id.toLowerCase(Locale.ROOT),
                    area.getString("display", id),
                    area.getInt("price", 750),
                    interact,
                    openBlocks
            ));
        }
        return parsed;
    }

    private Map<String, VendingUpgrade> parseVendingUpgrades(ConfigurationSection section) {
        Map<String, VendingUpgrade> parsed = new HashMap<>();
        if (section == null) {
            return parsed;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection upgrade = section.getConfigurationSection(id);
            if (upgrade == null) {
                continue;
            }
            VendingUpgradeType type;
            try {
                type = VendingUpgradeType.valueOf(upgrade.getString("type", "DAMAGE_MULTIPLIER").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                type = VendingUpgradeType.DAMAGE_MULTIPLIER;
            }
            parsed.put(id.toLowerCase(Locale.ROOT), new VendingUpgrade(
                    id.toLowerCase(Locale.ROOT),
                    upgrade.getString("display", id),
                    upgrade.getInt("price", 1000),
                    type,
                    upgrade.getDouble("value", 0.1D)
            ));
        }
        return parsed;
    }

    private Map<String, StationDefinition> parseStations(ConfigurationSection section, Map<String, VendingUpgrade> upgrades) {
        Map<String, StationDefinition> parsed = new HashMap<>();
        if (section == null) {
            return parsed;
        }
        ConfigurationSection lootBoxes = section.getConfigurationSection("loot-boxes");
        if (lootBoxes != null) {
            for (String id : lootBoxes.getKeys(false)) {
                ConfigurationSection box = lootBoxes.getConfigurationSection(id);
                if (box == null) {
                    continue;
                }
                Location location = parseLocation(box.getConfigurationSection("interact-block"));
                if (location == null) {
                    continue;
                }
                StationDefinition station = new StationDefinition(
                        id.toLowerCase(Locale.ROOT),
                        StationType.LOOT_BOX,
                        location,
                        box.getInt("price", 950),
                        String.valueOf(box.getInt("spin-ticks", 80))
                );
                parsed.put(blockKey(location), station);
            }
        }

        ConfigurationSection vendingStations = section.getConfigurationSection("vending");
        if (vendingStations != null) {
            for (String id : vendingStations.getKeys(false)) {
                ConfigurationSection vending = vendingStations.getConfigurationSection(id);
                if (vending == null) {
                    continue;
                }
                Location location = parseLocation(vending.getConfigurationSection("interact-block"));
                String upgradeId = vending.getString("upgrade", "").toLowerCase(Locale.ROOT);
                if (location == null || !upgrades.containsKey(upgradeId)) {
                    continue;
                }
                VendingUpgrade upgrade = upgrades.get(upgradeId);
                StationDefinition station = new StationDefinition(
                        id.toLowerCase(Locale.ROOT),
                        StationType.VENDING,
                        location,
                        upgrade.price(),
                        upgradeId
                );
                parsed.put(blockKey(location), station);
            }
        }

        ConfigurationSection pack = section.getConfigurationSection("pack-a-punch");
        if (pack != null) {
            for (String id : pack.getKeys(false)) {
                ConfigurationSection pap = pack.getConfigurationSection(id);
                if (pap == null) {
                    continue;
                }
                Location location = parseLocation(pap.getConfigurationSection("interact-block"));
                if (location == null) {
                    continue;
                }
                parsed.put(blockKey(location), new StationDefinition(
                        id.toLowerCase(Locale.ROOT),
                        StationType.PACK_A_PUNCH,
                        location,
                        pap.getInt("price", 5000),
                        pap.getString("id", "pap")
                ));
            }
        }

        ConfigurationSection armor = section.getConfigurationSection("armor-shop");
        if (armor != null) {
            for (String id : armor.getKeys(false)) {
                ConfigurationSection a = armor.getConfigurationSection(id);
                if (a == null) {
                    continue;
                }
                Location location = parseLocation(a.getConfigurationSection("interact-block"));
                if (location == null) {
                    continue;
                }
                parsed.put(blockKey(location), new StationDefinition(
                        id.toLowerCase(Locale.ROOT),
                        StationType.ARMOR_SHOP,
                        location,
                        0,
                        id.toLowerCase(Locale.ROOT)
                ));
            }
        }

        ConfigurationSection enchant = section.getConfigurationSection("enchanting");
        if (enchant != null) {
            for (String id : enchant.getKeys(false)) {
                ConfigurationSection e = enchant.getConfigurationSection(id);
                if (e == null) {
                    continue;
                }
                Location location = parseLocation(e.getConfigurationSection("interact-block"));
                if (location == null) {
                    continue;
                }
                parsed.put(blockKey(location), new StationDefinition(
                        id.toLowerCase(Locale.ROOT),
                        StationType.ENCHANTING,
                        location,
                        e.getInt("price", 3500),
                        id.toLowerCase(Locale.ROOT)
                ));
            }
        }
        return parsed;
    }

    private List<ArmorTier> parseArmorTiers(ConfigurationSection section) {
        List<ArmorTier> parsed = new ArrayList<>();
        if (section == null) {
            return parsed;
        }
        List<String> keys = new ArrayList<>(section.getKeys(false));
        keys.sort(Comparator.comparingInt(this::parseInt));
        for (String key : keys) {
            ConfigurationSection tier = section.getConfigurationSection(key);
            if (tier == null) {
                continue;
            }
            parsed.add(new ArmorTier(
                    key.toLowerCase(Locale.ROOT),
                    tier.getString("display", key),
                    parseMaterial(tier.getString("helmet"), Material.LEATHER_HELMET),
                    parseMaterial(tier.getString("chestplate"), Material.LEATHER_CHESTPLATE),
                    parseMaterial(tier.getString("leggings"), Material.LEATHER_LEGGINGS),
                    parseMaterial(tier.getString("boots"), Material.LEATHER_BOOTS),
                    tier.getInt("price", 1000)
            ));
        }
        return parsed;
    }

    private List<RangeTarget> parseRangeTargets(ConfigurationSection section) {
        List<RangeTarget> parsed = new ArrayList<>();
        if (section == null) {
            return parsed;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection target = section.getConfigurationSection(id);
            if (target == null) {
                continue;
            }
            Location location = parseLocation(target);
            if (location == null) {
                continue;
            }
            parsed.add(new RangeTarget(
                    id.toLowerCase(Locale.ROOT),
                    location,
                    target.getDouble("radius", 0.45D),
                    target.getInt("points", 10)
            ));
        }
        return parsed;
    }

    private Location parseLocation(ConfigurationSection section) {
        if (section == null) {
            return null;
        }
        String worldName = section.getString("world", "");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        double x = section.getDouble("x");
        double y = section.getDouble("y");
        double z = section.getDouble("z");
        float yaw = (float) section.getDouble("yaw", 0.0D);
        float pitch = (float) section.getDouble("pitch", 0.0D);
        return new Location(world, x, y, z, yaw, pitch);
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

    private Material parseMaterial(String input, Material fallback) {
        Material material = Material.matchMaterial(input == null ? "" : input);
        return material == null ? fallback : material;
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

    public String blockKey(Location location) {
        return location.getWorld().getName() + ":" + location.getBlockX() + ":" + location.getBlockY() + ":" + location.getBlockZ();
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

    public int getWaveDurationSeconds() {
        return waveDurationSeconds;
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

    public int getMaxWeapons() {
        return maxWeapons;
    }

    public boolean isLockInventory() {
        return lockInventory;
    }

    public boolean isDisableHungerLoss() {
        return disableHungerLoss;
    }

    public Material getGoldDropMaterial() {
        return goldDropMaterial;
    }

    public String getGoldDropName() {
        return goldDropName;
    }

    public int getGoldDropPickupDelayTicks() {
        return goldDropPickupDelayTicks;
    }

    public int getKillBaseGoldDrop() {
        return killBaseGoldDrop;
    }

    public double getDamageGoldMultiplier() {
        return damageGoldMultiplier;
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

    public List<BuyableArea> getBuyableAreas() {
        return buyableAreas;
    }

    public Map<String, StationDefinition> getStationByBlockKey() {
        return stationByBlockKey;
    }

    public Map<String, VendingUpgrade> getVendingUpgrades() {
        return vendingUpgrades;
    }

    public List<ArmorTier> getArmorTiers() {
        return armorTiers;
    }

    public List<RangeTarget> getRangeTargets() {
        return rangeTargets;
    }

    public boolean isRangeResetOnMiss() {
        return rangeResetOnMiss;
    }

    public int getRangeMaxDistance() {
        return rangeMaxDistance;
    }

    public boolean isScoreboardEnabled() {
        return scoreboardEnabled;
    }

    public int getScoreboardUpdateTicks() {
        return Math.max(10, scoreboardUpdateTicks);
    }

    public String getScoreboardTitle() {
        return scoreboardTitle;
    }
}
