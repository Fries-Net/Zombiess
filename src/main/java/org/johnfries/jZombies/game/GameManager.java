package org.johnfries.jZombies.game;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.johnfries.jZombies.config.ConfigService;
import org.johnfries.jZombies.config.LangService;
import org.johnfries.jZombies.data.PlayerDataStore;
import org.johnfries.jZombies.data.PlayerProfile;
import org.johnfries.jZombies.integration.MythicMobsBridge;
import org.johnfries.jZombies.model.MobSpawnDefinition;
import org.johnfries.jZombies.model.WaveDefinition;
import org.johnfries.jZombies.model.WeaponDefinition;
import org.johnfries.jZombies.service.SkillService;
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
    private final Random random = new Random();

    private final Set<UUID> participants = new HashSet<>();
    private final Set<UUID> downedPlayers = new HashSet<>();
    private final Map<UUID, BukkitTask> bleedoutTasks = new HashMap<>();
    private final Map<UUID, Integer> trackedMobs = new HashMap<>();
    private final Map<UUID, BukkitTask> reviveTasks = new HashMap<>();
    private final Map<String, BukkitTask> barricadeRepairTasks = new HashMap<>();
    private BukkitTask waveTask;
    private GameState state = GameState.WAITING;
    private int currentWaveNumber = 0;

    public GameManager(
            JavaPlugin plugin,
            ConfigService configService,
            LangService lang,
            PlayerDataStore dataStore,
            SkillService skillService,
            WeaponService weaponService,
            MythicMobsBridge mythicBridge
    ) {
        this.plugin = plugin;
        this.configService = configService;
        this.lang = lang;
        this.dataStore = dataStore;
        this.skillService = skillService;
        this.weaponService = weaponService;
        this.mythicBridge = mythicBridge;
    }

    public GameState getState() {
        return state;
    }

    public int getCurrentWaveNumber() {
        return currentWaveNumber;
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
        this.participants.clear();
        this.trackedMobs.clear();
        this.downedPlayers.clear();
        cancelAllTasks();

        String starterWeaponId = configService.getWeapons().keySet().stream().sorted().findFirst().orElse(null);
        for (Player player : online) {
            participants.add(player.getUniqueId());
            player.setGameMode(GameMode.ADVENTURE);
            player.getInventory().clear();

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

    public void handleMobDeath(Entity entity, Player killer) {
        if (state != GameState.RUNNING) {
            return;
        }
        Integer baseReward = trackedMobs.remove(entity.getUniqueId());
        if (baseReward == null) {
            return;
        }
        if (killer != null && isParticipant(killer)) {
            PlayerProfile profile = dataStore.getProfile(killer.getUniqueId());
            int reward = skillService.getKillReward(profile, baseReward);
            profile.addCoins(reward);
            killer.sendActionBar(lang.msg("game.kill-reward", Map.of(
                    "reward", String.valueOf(reward),
                    "coins", String.valueOf(profile.getCoins())
            )));
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
        }
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
        WaveDefinition wave = getWave(currentWaveNumber);
        if (wave == null) {
            endGame(true, false);
            return;
        }
        trackedMobs.clear();
        spawnWave(wave);
        broadcast(lang.msg("game.wave-start", Map.of(
                "wave", String.valueOf(currentWaveNumber),
                "total", String.valueOf(configuredMax)
        )));
        if (trackedMobs.isEmpty()) {
            onWaveCleared();
        }
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
        state = GameState.WAITING;
        participants.clear();
        currentWaveNumber = 0;
    }

    private void cancelAllTasks() {
        if (waveTask != null) {
            waveTask.cancel();
            waveTask = null;
        }
        for (BukkitTask task : bleedoutTasks.values()) {
            task.cancel();
        }
        for (BukkitTask task : reviveTasks.values()) {
            task.cancel();
        }
        for (BukkitTask task : barricadeRepairTasks.values()) {
            task.cancel();
        }
        bleedoutTasks.clear();
        reviveTasks.clear();
        barricadeRepairTasks.clear();
    }

    private String blockKey(Block block) {
        return block.getWorld().getName() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    private void broadcast(String message) {
        Bukkit.broadcastMessage(message);
    }
}
