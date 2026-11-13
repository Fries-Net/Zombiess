package org.johnfries.jZombies.listener;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;
import org.johnfries.jZombies.config.ConfigService;
import org.johnfries.jZombies.config.LangService;
import org.johnfries.jZombies.data.PlayerDataStore;
import org.johnfries.jZombies.data.PlayerProfile;
import org.johnfries.jZombies.game.GameManager;
import org.johnfries.jZombies.game.GameState;
import org.johnfries.jZombies.service.SkillService;
import org.johnfries.jZombies.service.SkillTreeGuiService;
import org.johnfries.jZombies.service.WeaponService;

import java.util.Random;

public final class GameListener implements Listener {
    private final JavaPlugin plugin;
    private final GameManager gameManager;
    private final ConfigService configService;
    private final LangService langService;
    private final PlayerDataStore dataStore;
    private final SkillService skillService;
    private final WeaponService weaponService;
    private final SkillTreeGuiService skillTreeGuiService;
    private final Random random = new Random();

    public GameListener(
            JavaPlugin plugin,
            GameManager gameManager,
            ConfigService configService,
            LangService langService,
            PlayerDataStore dataStore,
            SkillService skillService,
            WeaponService weaponService,
            SkillTreeGuiService skillTreeGuiService
    ) {
        this.plugin = plugin;
        this.gameManager = gameManager;
        this.configService = configService;
        this.langService = langService;
        this.dataStore = dataStore;
        this.skillService = skillService;
        this.weaponService = weaponService;
        this.skillTreeGuiService = skillTreeGuiService;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        gameManager.refreshPlayerStats(event.getPlayer());
        gameManager.refreshHud(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        gameManager.onQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (gameManager.isTrackedMob(event.getEntity())) {
            event.getDrops().clear();
            event.setDroppedExp(0);
        }
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
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        if (!gameManager.isParticipant(player)) {
            return;
        }

        Action action = event.getAction();
        if (action == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
            if (gameManager.handleBlockInteract(player, event.getClickedBlock(), event.getBlockFace(), player.isSneaking())) {
                event.setCancelled(true);
                return;
            }
        }

        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            if (gameManager.handleWeaponShoot(player)) {
                event.setCancelled(true);
            }
            return;
        }
        if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
            if (gameManager.handleWeaponReload(player)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamageBoostAndContribution(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity living)) {
            return;
        }
        Player attacker = asPlayerAttacker(event.getDamager());
        if (attacker == null || !gameManager.isParticipant(attacker) || gameManager.isDowned(attacker)) {
            return;
        }

        if (weaponService.isWeapon(attacker.getInventory().getItemInMainHand())) {
            return;
        }
        gameManager.recordMobDamage(attacker, living, event.getFinalDamage());
        if (gameManager.getState() != GameState.RUNNING) {
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
    public void onGoldPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        Item item = event.getItem();
        if (gameManager.handleGoldPickup(player, item)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (!configService.isDisableHungerLoss()) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!gameManager.isParticipant(player)) {
            return;
        }
        if (event.getFoodLevel() < player.getFoodLevel()) {
            event.setCancelled(true);
            player.setFoodLevel(20);
            player.setSaturation(20.0F);
            player.setExhaustion(0.0F);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (skillTreeGuiService.handleClick(event)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!gameManager.isParticipant(player)) {
            return;
        }
        if (!configService.isLockInventory()) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        skillTreeGuiService.handleClose(event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (skillTreeGuiService.isMenuView(player, event.getView().getTitle())) {
            event.setCancelled(true);
            return;
        }
        if (!gameManager.isParticipant(player)) {
            return;
        }
        if (!configService.isLockInventory()) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (!gameManager.isParticipant(player)) {
            return;
        }
        if (!configService.isLockInventory()) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        if (!gameManager.isParticipant(player)) {
            return;
        }
        if (!configService.isLockInventory()) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler
    public void onHeldSlot(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        if (!gameManager.isParticipant(player)) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> gameManager.refreshHud(player), 1L);
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
