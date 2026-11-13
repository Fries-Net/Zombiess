package org.johnfries.jZombies.game;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.johnfries.jZombies.config.ConfigService;
import org.johnfries.jZombies.config.LangService;
import org.johnfries.jZombies.data.PlayerDataStore;
import org.johnfries.jZombies.data.PlayerProfile;
import org.johnfries.jZombies.integration.MythicMobsBridge;
import org.johnfries.jZombies.model.ArmorTier;
import org.johnfries.jZombies.model.BuyableArea;
import org.johnfries.jZombies.model.MobSpawnDefinition;
import org.johnfries.jZombies.model.RangeTarget;
import org.johnfries.jZombies.model.StationDefinition;
import org.johnfries.jZombies.model.StationType;
import org.johnfries.jZombies.model.VendingUpgrade;
import org.johnfries.jZombies.model.VendingUpgradeType;
import org.johnfries.jZombies.model.WaveDefinition;
import org.johnfries.jZombies.model.WeaponDefinition;
import org.johnfries.jZombies.service.AreaHologramService;
import org.johnfries.jZombies.service.SkillService;
import org.johnfries.jZombies.service.TextService;
import org.johnfries.jZombies.service.WeaponService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public final class GameManager {
    private final JavaPlugin plugin;
    private final ConfigService configService;
    private final LangService lang;
    private final PlayerDataStore dataStore;
    private final SkillService skillService;
    private final WeaponService weaponService;
    private final MythicMobsBridge mythicBridge;
    private final AreaHologramService areaHologramService;
    private final Random random = new Random();

    private final Set<UUID> participants = new HashSet<>();
    private final Set<UUID> downedPlayers = new HashSet<>();
    private final Map<UUID, BukkitTask> bleedoutTasks = new HashMap<>();
    private final Map<UUID, Integer> trackedMobs = new HashMap<>();
    private final Map<UUID, BukkitTask> reviveTasks = new HashMap<>();
    private final Map<String, BukkitTask> barricadeRepairTasks = new HashMap<>();
    private final Map<UUID, Map<UUID, Double>> mobDamageContributions = new HashMap<>();
    private final Map<UUID, Set<String>> boughtVendingUpgrades = new HashMap<>();
    private final Map<UUID, Integer> armorTierProgress = new HashMap<>();
    private final Map<UUID, Integer> rangeScores = new HashMap<>();
    private final Map<UUID, Long> fireCooldownUntil = new HashMap<>();
    private final Map<UUID, BukkitTask> reloadTasks = new HashMap<>();
    private final Map<UUID, BukkitTask> lootBoxTasks = new HashMap<>();
    private final Set<String> openedAreas = new HashSet<>();

    private final org.bukkit.NamespacedKey goldDropValueKey;
    private final org.bukkit.NamespacedKey goldDropMarkerKey;

    private BukkitTask waveTask;
    private BukkitTask waveTickTask;
    private GameState state = GameState.WAITING;
    private int currentWaveNumber = 0;
    private int waveTimeRemainingSeconds = 0;

    public GameManager(
            JavaPlugin plugin,
            ConfigService configService,
            LangService lang,
            PlayerDataStore dataStore,
            SkillService skillService,
            WeaponService weaponService,
            MythicMobsBridge mythicBridge,
            AreaHologramService areaHologramService
    ) {
        this.plugin = plugin;
        this.configService = configService;
        this.lang = lang;
        this.dataStore = dataStore;
        this.skillService = skillService;
        this.weaponService = weaponService;
        this.mythicBridge = mythicBridge;
        this.areaHologramService = areaHologramService;
        this.goldDropValueKey = new org.bukkit.NamespacedKey(plugin, "gold_drop_value");
        this.goldDropMarkerKey = new org.bukkit.NamespacedKey(plugin, "gold_drop_marker");
    }

    public GameState getState() {
        return state;
    }

    public int getCurrentWaveNumber() {
        return currentWaveNumber;
    }

    public int getWaveTimeRemainingSeconds() {
        return Math.max(0, waveTimeRemainingSeconds);
    }

    public int getTrackedZombieCount() {
        return trackedMobs.size();
    }

    public boolean isTrackedMob(Entity entity) {
        return entity != null && trackedMobs.containsKey(entity.getUniqueId());
    }

    public List<Player> getParticipantPlayers() {
        List<Player> players = new ArrayList<>();
        for (UUID uuid : participants) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                players.add(player);
            }
        }
        return players;
    }

    public boolean isParticipant(Player player) {
        return participants.contains(player.getUniqueId());
    }

    public boolean isDowned(Player player) {
        return downedPlayers.contains(player.getUniqueId());
    }

    public boolean isPreGame() {
        return state == GameState.WAITING || state == GameState.STARTING;
    }

    public void startGame() {
        if (state == GameState.RUNNING || state == GameState.STARTING) {
            broadcast(lang.msg("game.already-running"));
            return;
        }

        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (online.isEmpty()) {
            broadcast(lang.msg("game.no-players"));
            return;
        }

        this.state = GameState.STARTING;
        this.currentWaveNumber = 0;
        this.waveTimeRemainingSeconds = configService.getWaveDurationSeconds();
        this.participants.clear();
        this.trackedMobs.clear();
        this.downedPlayers.clear();
        this.mobDamageContributions.clear();
        this.boughtVendingUpgrades.clear();
        this.armorTierProgress.clear();
        this.rangeScores.clear();
        this.openedAreas.clear();
        areaHologramService.syncState(configService.getBuyableAreas(), openedAreas);
        this.reloadTasks.clear();
        this.lootBoxTasks.clear();
        cancelAllTasks();

        String starterWeaponId = configService.getWeapons().keySet().stream().sorted().findFirst().orElse(null);
        for (Player player : online) {
            participants.add(player.getUniqueId());
            player.setGameMode(GameMode.ADVENTURE);
            player.getInventory().clear();
            player.setFoodLevel(20);
            player.setSaturation(20.0F);
            player.setExhaustion(0.0F);
            player.setExp(0.0F);
            player.setLevel(0);

            PlayerProfile profile = dataStore.getProfile(player.getUniqueId());
            profile.setCoins(configService.getStartingCoins());
            skillService.applyHealthModifier(player, profile);
            AttributeInstance health = player.getAttribute(Attribute.MAX_HEALTH);
            if (health != null) {
                player.setHealth(Math.min(health.getValue(), health.getBaseValue()));
            }
            player.removePotionEffect(PotionEffectType.SLOWNESS);
            player.removePotionEffect(PotionEffectType.WEAKNESS);
            player.removePotionEffect(PotionEffectType.DARKNESS);
            player.removePotionEffect(PotionEffectType.REGENERATION);

            if (starterWeaponId != null) {
                ItemStack starter = weaponService.createWeaponItem(profile, starterWeaponId);
                if (starter != null && starter.getType() != Material.AIR) {
                    player.getInventory().addItem(starter);
                }
            }
        }

        int delay = configService.getGameStartDelaySeconds();
        broadcast(lang.msg("game.starting", Map.of("seconds", String.valueOf(delay))));
        scheduleWave(delay);
    }

    public void stopGame() {
        if (state == GameState.WAITING) {
            return;
        }
        endGame(false, true);
    }

    public void forceNextWave() {
        if (state != GameState.RUNNING) {
            return;
        }
        if (waveTask != null) {
            waveTask.cancel();
            waveTask = null;
        }
        runNextWave();
    }

    public void rewardRoundSkillPoints(WaveDefinition wave) {
        int tokenGain = configService.getSkinTokensPerWave();
        for (UUID uuid : participants) {
            PlayerProfile profile = dataStore.getProfile(uuid);
            profile.addSkillPoints(wave.skillPointReward());
            if (tokenGain > 0) {
                profile.addSkinTokens(tokenGain);
            }
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                player.sendMessage(lang.msg("skills.points-gained", Map.of(
                        "amount", String.valueOf(wave.skillPointReward()),
                        "total", String.valueOf(profile.getSkillPoints()),
                        "tokenAmount", String.valueOf(tokenGain),
                        "tokenTotal", String.valueOf(profile.getSkinTokens())
                )));
            }
        }
    }

    public boolean applyDownedState(Player player, EntityDamageEvent event) {
        if (state != GameState.RUNNING || !isParticipant(player)) {
            return false;
        }
        if (isDowned(player)) {
            return false;
        }
        double remaining = player.getHealth() - event.getFinalDamage();
        if (remaining > 0) {
            return false;
        }
        event.setCancelled(true);
        downPlayer(player);
        return true;
    }

    public void tryRevive(Player reviver, Player target) {
        if (state != GameState.RUNNING || !isParticipant(reviver) || !isParticipant(target)) {
            return;
        }
        if (!isDowned(target) || isDowned(reviver)) {
            return;
        }
        if (reviveTasks.containsKey(target.getUniqueId())) {
            reviver.sendMessage(lang.msg("revive.already-reviving"));
            return;
        }
        double seconds = skillService.getReviveDurationSeconds(
                dataStore.getProfile(reviver.getUniqueId()),
                configService.getReviveBaseSeconds()
        );
        long ticks = Math.max(20L, Math.round(seconds * 20.0D));
        reviver.sendMessage(lang.msg("revive.started", Map.of(
                "target", target.getName(),
                "seconds", String.valueOf(String.format(Locale.ROOT, "%.1f", seconds))
        )));
        target.sendMessage(lang.msg("revive.being-revived", Map.of("player", reviver.getName())));

        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            reviveTasks.remove(target.getUniqueId());
            if (!reviver.isOnline() || !target.isOnline()) {
                return;
            }
            if (reviver.getLocation().distanceSquared(target.getLocation()) > 9.0D) {
                reviver.sendMessage(lang.msg("revive.failed-distance"));
                return;
            }
            completeRevive(target, reviver);
        }, ticks);
        reviveTasks.put(target.getUniqueId(), task);
    }

    public void recordMobDamage(Player attacker, LivingEntity mob, double damage) {
        if (state != GameState.RUNNING || !participants.contains(attacker.getUniqueId())) {
            return;
        }
        if (!trackedMobs.containsKey(mob.getUniqueId())) {
            return;
        }
        mobDamageContributions
                .computeIfAbsent(mob.getUniqueId(), k -> new HashMap<>())
                .merge(attacker.getUniqueId(), Math.max(0.0D, damage), Double::sum);
    }

    public void handleMobDeath(Entity entity, Player killer) {
        if (state != GameState.RUNNING) {
            return;
        }
        Integer baseReward = trackedMobs.remove(entity.getUniqueId());
        if (baseReward == null) {
            return;
        }

        Map<UUID, Double> contributions = mobDamageContributions.remove(entity.getUniqueId());
        if (contributions != null && !contributions.isEmpty()) {
            double total = contributions.values().stream().mapToDouble(Double::doubleValue).sum();
            for (Map.Entry<UUID, Double> entry : contributions.entrySet()) {
                UUID uuid = entry.getKey();
                Player player = Bukkit.getPlayer(uuid);
                if (player == null || !player.isOnline() || !isParticipant(player)) {
                    continue;
                }
                PlayerProfile profile = dataStore.getProfile(uuid);
                double share = total <= 0.0D ? 0.0D : entry.getValue() / total;
                int sharedGold = (int) Math.round(baseReward * share);
                int damageGold = (int) Math.round(entry.getValue() * configService.getDamageGoldMultiplier());
                int reward = Math.max(1, skillService.getKillReward(profile, configService.getKillBaseGoldDrop() + sharedGold + damageGold));
                dropGold(entity.getLocation(), uuid, reward);
            }
        } else if (killer != null && isParticipant(killer)) {
            PlayerProfile profile = dataStore.getProfile(killer.getUniqueId());
            int reward = skillService.getKillReward(profile, configService.getKillBaseGoldDrop() + baseReward);
            dropGold(entity.getLocation(), killer.getUniqueId(), reward);
        }

        if (trackedMobs.isEmpty()) {
            onWaveCleared();
        }
    }

    public int getWeaponPriceFor(Player player, WeaponDefinition weapon) {
        PlayerProfile profile = dataStore.getProfile(player.getUniqueId());
        return skillService.getWeaponPrice(profile, weapon.price());
    }

    public boolean buyWeapon(Player player, String weaponId) {
        Optional<WeaponDefinition> optional = weaponService.getWeapon(weaponId);
        if (optional.isEmpty()) {
            player.sendMessage(lang.msg("weapon.not-found"));
            return false;
        }
        WeaponDefinition weapon = optional.get();
        if (weaponService.countWeapons(player) >= configService.getMaxWeapons()) {
            player.sendMessage(lang.msg("weapon.max-weapons", Map.of("max", String.valueOf(configService.getMaxWeapons()))));
            return false;
        }
        PlayerProfile profile = dataStore.getProfile(player.getUniqueId());
        int price = getWeaponPriceFor(player, weapon);
        if (!profile.removeCoins(price)) {
            player.sendMessage(lang.msg("economy.not-enough-coins", Map.of(
                    "price", String.valueOf(price),
                    "coins", String.valueOf(profile.getCoins())
            )));
            return false;
        }

        ItemStack item = weaponService.createWeaponItem(profile, weapon.id());
        if (item != null) {
            player.getInventory().addItem(item);
            player.sendMessage(lang.msg("weapon.bought", Map.of(
                    "weapon", weapon.displayName(),
                    "price", String.valueOf(price)
            )));
            sendAmmoHud(player, item);
        }
        return true;
    }

    public boolean handleWeaponShoot(Player player) {
        if (state != GameState.RUNNING || !isParticipant(player) || isDowned(player)) {
            return false;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        String weaponId = weaponService.getWeaponId(hand);
        if (weaponId == null) {
            return false;
        }
        WeaponDefinition definition = configService.getWeapons().get(weaponId);
        if (definition == null) {
            return false;
        }
        if (reloadTasks.containsKey(player.getUniqueId())) {
            return true;
        }
        long now = System.currentTimeMillis();
        long nextAllowed = fireCooldownUntil.getOrDefault(player.getUniqueId(), 0L);
        if (now < nextAllowed) {
            return true;
        }
        int clip = weaponService.getClipAmmo(hand);
        if (clip <= 0) {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6F, 0.8F);
            sendAmmoHud(player, hand);
            return true;
        }

        weaponService.consumeAmmo(hand, 1);
        double skillDamage = skillService.getDamageMultiplier(dataStore.getProfile(player.getUniqueId()));
        double vendingDamage = getVendingDamageMultiplier(player.getUniqueId());
        boolean pap = weaponService.isPackAPunched(hand);
        double baseDamage = weaponService.getDamage(definition, pap, vendingDamage) * skillDamage;

        for (int pellet = 0; pellet < Math.max(1, definition.pellets()); pellet++) {
            Vector direction = applySpread(player.getEyeLocation().getDirection().normalize(), definition.spread());
            fireHitscan(player, definition, baseDamage, direction, pap);
        }
        fireCooldownUntil.put(player.getUniqueId(), now + (definition.fireCooldownTicks() * 50L));
        weaponService.playShootSound(player, definition);
        sendAmmoHud(player, hand);
        return true;
    }

    public boolean handleWeaponReload(Player player) {
        if (state != GameState.RUNNING || !isParticipant(player) || isDowned(player)) {
            return false;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        String weaponId = weaponService.getWeaponId(hand);
        if (weaponId == null) {
            return false;
        }
        WeaponDefinition definition = configService.getWeapons().get(weaponId);
        if (definition == null) {
            return false;
        }
        if (reloadTasks.containsKey(player.getUniqueId())) {
            return true;
        }
        if (weaponService.getClipAmmo(hand) >= definition.clipSize() || weaponService.getReserveAmmo(hand) <= 0) {
            return true;
        }
        weaponService.playReloadSound(player, definition);
        double skillReloadMultiplier = skillService.getReloadMultiplier(dataStore.getProfile(player.getUniqueId()));
        double vendingReloadMultiplier = getVendingReloadMultiplier(player.getUniqueId());
        long ticks = Math.max(5L, Math.round(definition.reloadTicks() * skillReloadMultiplier * vendingReloadMultiplier));
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            reloadTasks.remove(player.getUniqueId());
            if (!player.isOnline()) {
                return;
            }
            ItemStack current = player.getInventory().getItemInMainHand();
            if (!weaponId.equalsIgnoreCase(weaponService.getWeaponId(current))) {
                return;
            }
            weaponService.reload(current, getVendingAmmoMultiplier(player.getUniqueId()));
            sendAmmoHud(player, current);
        }, ticks);
        reloadTasks.put(player.getUniqueId(), task);
        return true;
    }

    public boolean handleBlockInteract(Player player, Block block, org.bukkit.block.BlockFace face, boolean sneaking) {
        if (block == null || !isParticipant(player) || state != GameState.RUNNING) {
            return false;
        }
        String key = configService.blockKey(block.getLocation());

        for (BuyableArea area : configService.getBuyableAreas()) {
            if (!configService.blockKey(area.interactBlock()).equals(key)) {
                continue;
            }
            tryBuyArea(player, area);
            return true;
        }

        StationDefinition station = configService.getStationByBlockKey().get(key);
        if (station != null) {
            useStation(player, station);
            return true;
        }

        if (configService.getBarricadeAnchorMaterials().contains(block.getType()) && sneaking) {
            var target = block.getRelative(face == null ? org.bukkit.block.BlockFace.UP : face);
            if (target.getType() == Material.AIR) {
                tryRepairBarricade(player, target);
                return true;
            }
        }
        return false;
    }

    public boolean handleGoldPickup(Player player, Item itemEntity) {
        if (itemEntity == null || !itemEntity.isValid()) {
            return false;
        }
        ItemStack stack = itemEntity.getItemStack();
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        Integer marker = meta.getPersistentDataContainer().get(goldDropMarkerKey, PersistentDataType.INTEGER);
        if (marker == null || marker != 1) {
            return false;
        }
        Integer amount = meta.getPersistentDataContainer().get(goldDropValueKey, PersistentDataType.INTEGER);
        if (amount == null || amount <= 0) {
            return false;
        }
        PlayerProfile profile = dataStore.getProfile(player.getUniqueId());
        profile.addCoins(amount);
        player.sendActionBar(lang.msg("game.kill-reward", Map.of(
                "reward", String.valueOf(amount),
                "coins", String.valueOf(profile.getCoins())
        )));
        itemEntity.remove();
        return true;
    }

    public void onQuit(Player player) {
        UUID uuid = player.getUniqueId();
        participants.remove(uuid);
        downedPlayers.remove(uuid);
        BukkitTask revive = reviveTasks.remove(uuid);
        if (revive != null) {
            revive.cancel();
        }
        BukkitTask bleedout = bleedoutTasks.remove(uuid);
        if (bleedout != null) {
            bleedout.cancel();
        }
        BukkitTask reload = reloadTasks.remove(uuid);
        if (reload != null) {
            reload.cancel();
        }
        BukkitTask loot = lootBoxTasks.remove(uuid);
        if (loot != null) {
            loot.cancel();
        }
        if (state == GameState.RUNNING && participants.isEmpty()) {
            endGame(false, false);
        }
    }

    public void tryRepairBarricade(Player player, Block targetBlock) {
        if (state != GameState.RUNNING || !isParticipant(player) || isDowned(player)) {
            return;
        }
        if (targetBlock.getType() != Material.AIR) {
            return;
        }
        String key = blockKey(targetBlock);
        if (barricadeRepairTasks.containsKey(key)) {
            return;
        }
        PlayerProfile profile = dataStore.getProfile(player.getUniqueId());
        long repairTicks = Math.round(skillService.getBarricadeDurationTicks(profile, configService.getBarricadeBaseTicks()));
        player.sendActionBar(lang.msg("barricade.repair-start"));
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            barricadeRepairTasks.remove(key);
            if (!player.isOnline() || !isParticipant(player)) {
                return;
            }
            if (targetBlock.getType() == Material.AIR) {
                targetBlock.setType(configService.getBarricadeRepairMaterial(), false);
                player.sendActionBar(lang.msg("barricade.repair-complete"));
                PlayerProfile p = dataStore.getProfile(player.getUniqueId());
                int reward = skillService.getKillReward(p, 15);
                p.addCoins(reward);
            }
        }, Math.max(5L, repairTicks));
        barricadeRepairTasks.put(key, task);
    }

    public void refreshPlayerStats(Player player) {
        PlayerProfile profile = dataStore.getProfile(player.getUniqueId());
        skillService.applyHealthModifier(player, profile);
    }

    public void refreshHud(Player player) {
        if (!isParticipant(player)) {
            return;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (weaponService.isWeapon(held)) {
            sendAmmoHud(player, held);
        } else {
            player.setExp(0.0F);
            player.setLevel(0);
        }
    }

    private void scheduleWave(int delaySeconds) {
        if (waveTask != null) {
            waveTask.cancel();
        }
        waveTask = Bukkit.getScheduler().runTaskLater(plugin, this::runNextWave, delaySeconds * 20L);
    }

    private void runNextWave() {
        waveTask = null;
        if (state == GameState.ENDED || state == GameState.WAITING) {
            return;
        }

        int configuredMax = Math.min(configService.getMaxRounds(), configService.getWaves().size());
        if (currentWaveNumber >= configuredMax) {
            endGame(true, false);
            return;
        }

        state = GameState.RUNNING;
        currentWaveNumber++;
        waveTimeRemainingSeconds = configService.getWaveDurationSeconds();
        startWaveTimerTask();

        WaveDefinition wave = getWave(currentWaveNumber);
        if (wave == null) {
            endGame(true, false);
            return;
        }
        trackedMobs.clear();
        mobDamageContributions.clear();
        spawnWave(wave);
        sendWaveTitle();
        broadcast(lang.msg("game.wave-start", Map.of(
                "wave", String.valueOf(currentWaveNumber),
                "total", String.valueOf(configuredMax)
        )));
        if (trackedMobs.isEmpty()) {
            onWaveCleared();
        }
    }

    private void startWaveTimerTask() {
        if (waveTickTask != null) {
            waveTickTask.cancel();
        }
        waveTickTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (state != GameState.RUNNING) {
                return;
            }
            waveTimeRemainingSeconds--;
            for (Player player : getParticipantPlayers()) {
                applyRegenUpgrade(player);
                refreshHud(player);
            }
            if (waveTimeRemainingSeconds <= 0) {
                endGame(false, false);
            }
        }, 20L, 20L);
    }

    private WaveDefinition getWave(int waveNumber) {
        return configService.getWaves().stream()
                .filter(w -> w.number() == waveNumber)
                .min(Comparator.comparingInt(WaveDefinition::number))
                .orElse(null);
    }

    private void spawnWave(WaveDefinition wave) {
        List<Location> spawnPoints = configService.getSpawnPoints();
        if (spawnPoints.isEmpty()) {
            plugin.getLogger().warning("No spawn points configured. Cannot spawn wave.");
            return;
        }
        for (MobSpawnDefinition mob : wave.mobs()) {
            for (int i = 0; i < mob.amount(); i++) {
                Location spawn = spawnPoints.get(random.nextInt(spawnPoints.size()));
                if (mob.isMythic() && configService.isMythicEnabled() && mythicBridge.isEnabled()) {
                    mythicBridge.spawn(mob.mythicMobId(), spawn).ifPresent(uuid -> trackedMobs.put(uuid, mob.killReward()));
                    continue;
                }
                EntityType type = mob.entityType() == null ? EntityType.ZOMBIE : mob.entityType();
                Entity entity = spawn.getWorld().spawnEntity(spawn, type);
                if (entity instanceof LivingEntity living) {
                    if (living.getAttribute(Attribute.MAX_HEALTH) != null) {
                        living.getAttribute(Attribute.MAX_HEALTH).setBaseValue(Math.max(1.0D, mob.health()));
                        living.setHealth(Math.max(1.0D, mob.health()));
                    }
                    if (living.getAttribute(Attribute.MOVEMENT_SPEED) != null) {
                        double baseSpeed = living.getAttribute(Attribute.MOVEMENT_SPEED).getBaseValue();
                        living.getAttribute(Attribute.MOVEMENT_SPEED).setBaseValue(baseSpeed * Math.max(0.1D, mob.speedMultiplier()));
                    }
                    if (living.getAttribute(Attribute.ATTACK_DAMAGE) != null) {
                        living.getAttribute(Attribute.ATTACK_DAMAGE).setBaseValue(Math.max(1.0D, mob.damage()));
                    }
                }
                trackedMobs.put(entity.getUniqueId(), mob.killReward());
            }
        }
    }

    private void onWaveCleared() {
        WaveDefinition wave = getWave(currentWaveNumber);
        if (wave == null) {
            return;
        }
        rewardRoundSkillPoints(wave);
        broadcast(lang.msg("game.wave-cleared", Map.of("wave", String.valueOf(currentWaveNumber))));
        state = GameState.STARTING;
        waveTimeRemainingSeconds = configService.getWaveIntervalSeconds();
        if (waveTickTask != null) {
            waveTickTask.cancel();
            waveTickTask = null;
        }
        scheduleWave(configService.getWaveIntervalSeconds());
    }

    private void downPlayer(Player player) {
        downedPlayers.add(player.getUniqueId());
        player.setHealth(Math.max(1.0D, player.getHealth()));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, Integer.MAX_VALUE, 5, false, false, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, Integer.MAX_VALUE, 255, false, false, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, Integer.MAX_VALUE, 0, false, false, true));

        broadcast(lang.msg("revive.player-downed", Map.of("player", player.getName())));
        BukkitTask bleedout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            bleedoutTasks.remove(player.getUniqueId());
            if (!player.isOnline() || !downedPlayers.contains(player.getUniqueId())) {
                return;
            }
            eliminatePlayer(player);
        }, configService.getDownedBleedoutSeconds() * 20L);
        bleedoutTasks.put(player.getUniqueId(), bleedout);
    }

    private void completeRevive(Player target, Player reviver) {
        downedPlayers.remove(target.getUniqueId());
        BukkitTask bleedout = bleedoutTasks.remove(target.getUniqueId());
        if (bleedout != null) {
            bleedout.cancel();
        }
        target.removePotionEffect(PotionEffectType.SLOWNESS);
        target.removePotionEffect(PotionEffectType.WEAKNESS);
        target.removePotionEffect(PotionEffectType.DARKNESS);
        AttributeInstance maxHealth = target.getAttribute(Attribute.MAX_HEALTH);
        double healTo = maxHealth == null ? 8.0D : Math.min(maxHealth.getBaseValue(), 8.0D);
        target.setHealth(Math.max(1.0D, healTo));
        target.sendMessage(lang.msg("revive.revived", Map.of("player", reviver.getName())));
        reviver.sendMessage(lang.msg("revive.revive-success", Map.of("target", target.getName())));
    }

    private void eliminatePlayer(Player player) {
        downedPlayers.remove(player.getUniqueId());
        participants.remove(player.getUniqueId());
        player.removePotionEffect(PotionEffectType.SLOWNESS);
        player.removePotionEffect(PotionEffectType.WEAKNESS);
        player.removePotionEffect(PotionEffectType.DARKNESS);
        player.setGameMode(GameMode.SPECTATOR);
        broadcast(lang.msg("game.player-eliminated", Map.of("player", player.getName())));
        if (participants.isEmpty()) {
            endGame(false, false);
        }
    }

    private void endGame(boolean won, boolean forced) {
        state = GameState.ENDED;
        cancelAllTasks();
        trackedMobs.clear();
        mobDamageContributions.clear();
        waveTimeRemainingSeconds = 0;

        for (UUID uuid : downedPlayers) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.removePotionEffect(PotionEffectType.SLOWNESS);
                player.removePotionEffect(PotionEffectType.WEAKNESS);
                player.removePotionEffect(PotionEffectType.DARKNESS);
            }
        }
        downedPlayers.clear();
        bleedoutTasks.clear();
        reviveTasks.clear();
        reloadTasks.clear();
        lootBoxTasks.clear();
        for (BukkitTask task : barricadeRepairTasks.values()) {
            task.cancel();
        }
        barricadeRepairTasks.clear();

        if (forced) {
            broadcast(lang.msg("game.stopped"));
        } else if (won) {
            broadcast(lang.msg("game.completed"));
        } else {
            broadcast(lang.msg("game.failed"));
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.setExp(0.0F);
            player.setLevel(0);
        }
        state = GameState.WAITING;
        participants.clear();
        currentWaveNumber = 0;
    }

    private void cancelAllTasks() {
        if (waveTask != null) {
            waveTask.cancel();
            waveTask = null;
        }
        if (waveTickTask != null) {
            waveTickTask.cancel();
            waveTickTask = null;
        }
        for (BukkitTask task : bleedoutTasks.values()) {
            task.cancel();
        }
        for (BukkitTask task : reviveTasks.values()) {
            task.cancel();
        }
        for (BukkitTask task : reloadTasks.values()) {
            task.cancel();
        }
        for (BukkitTask task : lootBoxTasks.values()) {
            task.cancel();
        }
        for (BukkitTask task : barricadeRepairTasks.values()) {
            task.cancel();
        }
        bleedoutTasks.clear();
        reviveTasks.clear();
        reloadTasks.clear();
        lootBoxTasks.clear();
        barricadeRepairTasks.clear();
    }

    private void sendWaveTitle() {
        for (Player player : getParticipantPlayers()) {
            player.sendTitle(
                    TextService.colorize("&#FFD54FWave " + currentWaveNumber),
                    TextService.colorize("&#FF8A65Survive the horde"),
                    5,
                    35,
                    10
            );
        }
    }

    private String blockKey(Block block) {
        return block.getWorld().getName() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    private void fireHitscan(Player player, WeaponDefinition definition, double baseDamage, Vector direction, boolean pap) {
        Location eye = player.getEyeLocation();
        RayTraceResult blockResult = player.getWorld().rayTraceBlocks(eye, direction, definition.range());
        double maxDistance = definition.range();
        Location end = eye.clone().add(direction.clone().multiply(maxDistance));
        if (blockResult != null && blockResult.getHitPosition() != null) {
            Location blockHit = blockResult.getHitPosition().toLocation(player.getWorld());
            maxDistance = eye.distance(blockHit);
            end = blockHit;
        }

        RayTraceResult entityResult = player.getWorld().rayTraceEntities(
                eye,
                direction,
                maxDistance,
                0.2D,
                entity -> entity instanceof LivingEntity living
                        && !(entity instanceof Player)
                        && !entity.isDead()
                        && trackedMobs.containsKey(living.getUniqueId())
        );

        boolean headshot = false;
        if (entityResult != null && entityResult.getHitEntity() instanceof LivingEntity hit) {
            Location hitPos = entityResult.getHitPosition().toLocation(player.getWorld());
            end = hitPos;
            double headY = hit.getLocation().getY() + (hit.getHeight() * 0.72D);
            headshot = hitPos.getY() >= headY;
            double damage = baseDamage * (headshot ? definition.headshotMultiplier() : 1.0D);
            hit.damage(damage, player);
            recordMobDamage(player, hit, damage);
        } else {
            handleRangeShot(player, eye, direction, definition.range());
        }
        spawnTrail(eye, end, pap, headshot);
    }

    private void spawnTrail(Location start, Location end, boolean pap, boolean headshot) {
        Vector vector = end.toVector().subtract(start.toVector());
        double length = vector.length();
        if (length <= 0.0D) {
            return;
        }
        Vector step = vector.normalize().multiply(0.35D);
        Location cursor = start.clone();
        int points = Math.max(1, (int) (length / 0.35D));
        Particle particle = pap ? Particle.END_ROD : Particle.CRIT;
        for (int i = 0; i < points; i++) {
            start.getWorld().spawnParticle(particle, cursor, 1, 0.0D, 0.0D, 0.0D, 0.0D);
            cursor.add(step);
        }
        if (headshot) {
            start.getWorld().spawnParticle(Particle.FLASH, end, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }

    private Vector applySpread(Vector direction, double spread) {
        if (spread <= 0.0D) {
            return direction.clone();
        }
        Vector modified = direction.clone().add(new Vector(
                (random.nextDouble() - 0.5D) * spread,
                (random.nextDouble() - 0.5D) * spread,
                (random.nextDouble() - 0.5D) * spread
        ));
        return modified.normalize();
    }

    private void sendAmmoHud(Player player, ItemStack weaponItem) {
        int clip = weaponService.getClipAmmo(weaponItem);
        int reserve = weaponService.getReserveAmmo(weaponItem);
        int total = clip + reserve;
        String weaponId = weaponService.getWeaponId(weaponItem);
        player.sendActionBar(TextService.colorize("&#81D4FA" + (weaponId == null ? "Weapon" : weaponId.toUpperCase(Locale.ROOT)) + " &#FFD54F" + clip + "/" + total));
        Optional<WeaponDefinition> optional = weaponId == null ? Optional.empty() : weaponService.getWeapon(weaponId);
        int max = optional.map(WeaponDefinition::clipSize).orElse(1);
        player.setExp(Math.max(0.0F, Math.min(1.0F, (float) clip / (float) Math.max(1, max))));
        player.setLevel(reserve);
    }

    private void dropGold(Location location, UUID owner, int amount) {
        ItemStack stack = new ItemStack(configService.getGoldDropMaterial());
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(TextService.colorize(configService.getGoldDropName()));
            meta.getPersistentDataContainer().set(goldDropValueKey, PersistentDataType.INTEGER, amount);
            meta.getPersistentDataContainer().set(goldDropMarkerKey, PersistentDataType.INTEGER, 1);
            stack.setItemMeta(meta);
        }
        Item item = location.getWorld().dropItemNaturally(location, stack);
        item.setPickupDelay(Math.max(0, configService.getGoldDropPickupDelayTicks()));
        item.setOwner(owner);
    }

    private void useStation(Player player, StationDefinition station) {
        switch (station.type()) {
            case LOOT_BOX -> useLootBox(player, station);
            case VENDING -> useVending(player, station);
            case PACK_A_PUNCH -> usePackAPunch(player, station);
            case ARMOR_SHOP -> useArmorShop(player);
            case ENCHANTING -> useEnchanting(player, station);
        }
    }

    private void useLootBox(Player player, StationDefinition station) {
        if (lootBoxTasks.containsKey(player.getUniqueId())) {
            player.sendMessage(lang.msg("machines.busy"));
            return;
        }
        PlayerProfile profile = dataStore.getProfile(player.getUniqueId());
        if (!profile.removeCoins(station.price())) {
            player.sendMessage(lang.msg("economy.not-enough-coins", Map.of(
                    "price", String.valueOf(station.price()),
                    "coins", String.valueOf(profile.getCoins())
            )));
            return;
        }
        List<WeaponDefinition> pool = configService.getWeapons().values().stream()
                .filter(WeaponDefinition::lootBoxWeapon)
                .toList();
        if (pool.isEmpty()) {
            player.sendMessage(lang.msg("machines.no-loot"));
            profile.addCoins(station.price());
            return;
        }
        int spinTicks = parseInt(station.referenceId(), 80);
        player.sendTitle(
                TextService.colorize("&#CE93D8Mystery Box"),
                TextService.colorize("&#FFF59DRolling..."),
                5,
                Math.max(20, spinTicks - 10),
                5
        );
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            lootBoxTasks.remove(player.getUniqueId());
            if (!player.isOnline() || !isParticipant(player)) {
                return;
            }
            WeaponDefinition selected = weightedWeapon(pool);
            if (weaponService.countWeapons(player) >= configService.getMaxWeapons()) {
                player.sendMessage(lang.msg("weapon.max-weapons", Map.of("max", String.valueOf(configService.getMaxWeapons()))));
                return;
            }
            ItemStack reward = weaponService.createWeaponItem(dataStore.getProfile(player.getUniqueId()), selected.id());
            player.getInventory().addItem(reward);
            player.sendMessage(lang.msg("machines.loot-reward", Map.of("weapon", selected.displayName())));
        }, Math.max(20L, spinTicks));
        lootBoxTasks.put(player.getUniqueId(), task);
    }

    private void useVending(Player player, StationDefinition station) {
        String upgradeId = station.referenceId();
        VendingUpgrade upgrade = configService.getVendingUpgrades().get(upgradeId);
        if (upgrade == null) {
            return;
        }
        Set<String> bought = boughtVendingUpgrades.computeIfAbsent(player.getUniqueId(), ignored -> new HashSet<>());
        if (bought.contains(upgradeId)) {
            player.sendMessage(lang.msg("machines.already-bought"));
            return;
        }
        PlayerProfile profile = dataStore.getProfile(player.getUniqueId());
        if (!profile.removeCoins(upgrade.price())) {
            player.sendMessage(lang.msg("economy.not-enough-coins", Map.of(
                    "price", String.valueOf(upgrade.price()),
                    "coins", String.valueOf(profile.getCoins())
            )));
            return;
        }
        bought.add(upgradeId);
        if (upgrade.type() == VendingUpgradeType.REGENERATION) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 20 * 99999, 0, false, false, true));
        }
        player.sendMessage(lang.msg("machines.vending-bought", Map.of("upgrade", upgrade.displayName())));
    }

    private void usePackAPunch(Player player, StationDefinition station) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!weaponService.isWeapon(held)) {
            player.sendMessage(lang.msg("machines.need-weapon"));
            return;
        }
        if (weaponService.isPackAPunched(held)) {
            player.sendMessage(lang.msg("machines.already-packed"));
            return;
        }
        PlayerProfile profile = dataStore.getProfile(player.getUniqueId());
        if (!profile.removeCoins(station.price())) {
            player.sendMessage(lang.msg("economy.not-enough-coins", Map.of(
                    "price", String.valueOf(station.price()),
                    "coins", String.valueOf(profile.getCoins())
            )));
            return;
        }
        if (weaponService.applyPackAPunch(profile, player, held, 1.2D)) {
            player.sendMessage(lang.msg("machines.packed"));
            refreshHud(player);
        }
    }

    private void useArmorShop(Player player) {
        List<ArmorTier> tiers = configService.getArmorTiers();
        if (tiers.isEmpty()) {
            return;
        }
        int currentTier = armorTierProgress.getOrDefault(player.getUniqueId(), -1);
        int nextTier = currentTier + 1;
        if (nextTier >= tiers.size()) {
            player.sendMessage(lang.msg("machines.max-armor"));
            return;
        }
        ArmorTier tier = tiers.get(nextTier);
        PlayerProfile profile = dataStore.getProfile(player.getUniqueId());
        if (!profile.removeCoins(tier.price())) {
            player.sendMessage(lang.msg("economy.not-enough-coins", Map.of(
                    "price", String.valueOf(tier.price()),
                    "coins", String.valueOf(profile.getCoins())
            )));
            return;
        }
        PlayerInventory inventory = player.getInventory();
        inventory.setHelmet(unbreakable(new ItemStack(tier.helmet())));
        inventory.setChestplate(unbreakable(new ItemStack(tier.chestplate())));
        inventory.setLeggings(unbreakable(new ItemStack(tier.leggings())));
        inventory.setBoots(unbreakable(new ItemStack(tier.boots())));
        armorTierProgress.put(player.getUniqueId(), nextTier);
        player.sendMessage(lang.msg("machines.armor-upgraded", Map.of("tier", tier.displayName())));
    }

    private void useEnchanting(Player player, StationDefinition station) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) {
            player.sendMessage(lang.msg("machines.need-weapon"));
            return;
        }
        PlayerProfile profile = dataStore.getProfile(player.getUniqueId());
        if (!profile.removeCoins(station.price())) {
            player.sendMessage(lang.msg("economy.not-enough-coins", Map.of(
                    "price", String.valueOf(station.price()),
                    "coins", String.valueOf(profile.getCoins())
            )));
            return;
        }
        Enchantment enchant = random.nextBoolean() ? Enchantment.POWER : Enchantment.SHARPNESS;
        held.addUnsafeEnchantment(enchant, 1);
        player.sendMessage(lang.msg("machines.enchanted"));
    }

    private void tryBuyArea(Player player, BuyableArea area) {
        if (openedAreas.contains(area.id())) {
            player.sendMessage(lang.msg("areas.already-open"));
            return;
        }
        PlayerProfile profile = dataStore.getProfile(player.getUniqueId());
        if (!profile.removeCoins(area.price())) {
            player.sendMessage(lang.msg("economy.not-enough-coins", Map.of(
                    "price", String.valueOf(area.price()),
                    "coins", String.valueOf(profile.getCoins())
            )));
            return;
        }
        openedAreas.add(area.id());
        areaHologramService.setAreaOpened(area, true);
        for (Location location : area.openBlocks()) {
            if (location.getWorld() == null) {
                continue;
            }
            location.getBlock().setType(Material.AIR, false);
        }
        broadcast(lang.msg("areas.opened", Map.of(
                "player", player.getName(),
                "area", area.displayName(),
                "price", String.valueOf(area.price())
        )));
    }

    private void handleRangeShot(Player player, Location origin, Vector direction, double range) {
        RangeTarget hit = getHitRangeTarget(origin, direction, range);
        if (hit != null) {
            int score = rangeScores.getOrDefault(player.getUniqueId(), 0) + hit.points();
            rangeScores.put(player.getUniqueId(), score);
            player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.9F, 1.8F);
            player.sendActionBar(lang.msg("range.hit", Map.of("score", String.valueOf(score))));
            return;
        }
        if (configService.isRangeResetOnMiss()) {
            rangeScores.put(player.getUniqueId(), 0);
            player.sendActionBar(lang.msg("range.miss"));
        }
    }

    private RangeTarget getHitRangeTarget(Location origin, Vector direction, double maxRange) {
        List<RangeTarget> targets = configService.getRangeTargets();
        if (targets.isEmpty()) {
            return null;
        }
        RangeTarget best = null;
        double bestT = Double.MAX_VALUE;
        Vector unit = direction.clone().normalize();

        for (RangeTarget target : targets) {
            if (!target.headLocation().getWorld().equals(origin.getWorld())) {
                continue;
            }
            if (origin.distanceSquared(target.headLocation()) > (double) (configService.getRangeMaxDistance() * configService.getRangeMaxDistance())) {
                continue;
            }
            Vector toCenter = target.headLocation().toVector().subtract(origin.toVector());
            double t = toCenter.dot(unit);
            if (t < 0.0D || t > maxRange) {
                continue;
            }
            Vector closest = origin.toVector().add(unit.clone().multiply(t));
            double distance = closest.distance(target.headLocation().toVector());
            if (distance <= target.radius() && t < bestT) {
                best = target;
                bestT = t;
            }
        }
        return best;
    }

    private void applyRegenUpgrade(Player player) {
        Set<String> bought = boughtVendingUpgrades.get(player.getUniqueId());
        if (bought == null || bought.isEmpty() || isDowned(player)) {
            return;
        }
        boolean regen = false;
        for (String id : bought) {
            VendingUpgrade upgrade = configService.getVendingUpgrades().get(id);
            if (upgrade != null && upgrade.type() == VendingUpgradeType.REGENERATION) {
                regen = true;
                break;
            }
        }
        if (!regen) {
            return;
        }
        AttributeInstance max = player.getAttribute(Attribute.MAX_HEALTH);
        if (max == null) {
            return;
        }
        if (player.getHealth() < max.getBaseValue()) {
            player.setHealth(Math.min(max.getBaseValue(), player.getHealth() + 0.5D));
        }
    }

    private double getVendingDamageMultiplier(UUID uuid) {
        double multiplier = 1.0D;
        for (String id : boughtVendingUpgrades.getOrDefault(uuid, Set.of())) {
            VendingUpgrade upgrade = configService.getVendingUpgrades().get(id);
            if (upgrade != null && upgrade.type() == VendingUpgradeType.DAMAGE_MULTIPLIER) {
                multiplier += upgrade.value();
            }
        }
        return multiplier;
    }

    private double getVendingAmmoMultiplier(UUID uuid) {
        double multiplier = 1.0D;
        for (String id : boughtVendingUpgrades.getOrDefault(uuid, Set.of())) {
            VendingUpgrade upgrade = configService.getVendingUpgrades().get(id);
            if (upgrade != null && upgrade.type() == VendingUpgradeType.MAX_AMMO_MULTIPLIER) {
                multiplier += upgrade.value();
            }
        }
        return multiplier;
    }

    private double getVendingReloadMultiplier(UUID uuid) {
        double multiplier = 1.0D;
        for (String id : boughtVendingUpgrades.getOrDefault(uuid, Set.of())) {
            VendingUpgrade upgrade = configService.getVendingUpgrades().get(id);
            if (upgrade != null && upgrade.type() == VendingUpgradeType.RELOAD_MULTIPLIER) {
                multiplier -= upgrade.value();
            }
        }
        return Math.max(0.45D, multiplier);
    }

    private WeaponDefinition weightedWeapon(List<WeaponDefinition> pool) {
        int totalWeight = pool.stream().mapToInt(weapon -> Math.max(1, weapon.lootWeight())).sum();
        int roll = random.nextInt(totalWeight);
        int cumulative = 0;
        for (WeaponDefinition weapon : pool) {
            cumulative += Math.max(1, weapon.lootWeight());
            if (roll < cumulative) {
                return weapon;
            }
        }
        return pool.get(random.nextInt(pool.size()));
    }

    private ItemStack unbreakable(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setUnbreakable(true);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private void broadcast(String message) {
        Bukkit.broadcastMessage(message);
    }
}
