package org.johnfries.jZombies.listener;

import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.johnfries.jZombies.config.ConfigService;
import org.johnfries.jZombies.config.LangService;
import org.johnfries.jZombies.data.PlayerDataStore;
import org.johnfries.jZombies.data.PlayerProfile;
import org.johnfries.jZombies.game.GameManager;
import org.johnfries.jZombies.game.GameState;
import org.johnfries.jZombies.service.SkillService;

import java.util.Random;

public final class GameListener implements Listener {
    private final GameManager gameManager;
    private final ConfigService configService;
    private final LangService langService;
    private final PlayerDataStore dataStore;
    private final SkillService skillService;
    private final Random random = new Random();

    public GameListener(
            GameManager gameManager,
            ConfigService configService,
            LangService langService,
            PlayerDataStore dataStore,
            SkillService skillService
    ) {
        this.gameManager = gameManager;
        this.configService = configService;
        this.langService = langService;
        this.dataStore = dataStore;
        this.skillService = skillService;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        gameManager.refreshPlayerStats(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        gameManager.onQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        gameManager.handleMobDeath(event.getEntity(), killer);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFatalDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        gameManager.applyDownedState(player, event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onReviveInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Player target)) {
            return;
        }
        Player reviver = event.getPlayer();
        if (!reviver.isSneaking()) {
            return;
        }
        gameManager.tryRevive(reviver, target);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamageBoost(EntityDamageByEntityEvent event) {
        if (gameManager.getState() != GameState.RUNNING) {
            return;
        }
        Player attacker = asPlayerAttacker(event.getDamager());
        if (attacker == null || !gameManager.isParticipant(attacker) || gameManager.isDowned(attacker)) {
            return;
        }
        PlayerProfile profile = dataStore.getProfile(attacker.getUniqueId());
        double multiplier = skillService.getDamageMultiplier(profile);
        double critChance = skillService.getCritChance(profile);
        boolean crit = critChance > 0.0D && random.nextDouble() < critChance;
        double finalMultiplier = crit ? (multiplier * 1.5D) : multiplier;
        event.setDamage(event.getDamage() * finalMultiplier);
        if (crit) {
            attacker.sendActionBar(langService.msg("combat.crit"));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBarricadeRepairInteract(PlayerInteractEvent event) {
        if (gameManager.getState() != GameState.RUNNING) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        Material anchor = event.getClickedBlock().getType();
        if (!configService.getBarricadeAnchorMaterials().contains(anchor)) {
            return;
        }
        if (!event.getPlayer().isSneaking()) {
            return;
        }
        var target = event.getClickedBlock().getRelative(event.getBlockFace());
        if (target.getType() != Material.AIR) {
            return;
        }
        gameManager.tryRepairBarricade(event.getPlayer(), target);
        event.setCancelled(true);
    }

    private Player asPlayerAttacker(Entity entity) {
        if (entity instanceof Player player) {
            return player;
        }
        if (!(entity instanceof Projectile projectile)) {
            return null;
        }
        ProjectileSource source = projectile.getShooter();
        if (source instanceof Player player) {
            return player;
        }
        return null;
    }
}
