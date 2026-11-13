package net.fries.zombies.game;

import net.fries.zombies.common.gameplay.Survivor;
import net.fries.zombies.game.world.MapWorlds;

import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;

/** Routes only owned participants and disposable worlds; hub players retain normal behavior. */
final class MatchListener implements Listener {
    private final MatchCoordinator matches;

    MatchListener(MatchCoordinator matches) {
        this.matches = matches;
    }

    private boolean owned(org.bukkit.World world) {
        var r = matches.current();
        return r != null && r.world == world;
    }

    @EventHandler
    public void join(PlayerJoinEvent e) {
        matches.recoverPlayer(e.getPlayer());
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        matches.quit(e.getPlayer());
    }

    @EventHandler
    public void interact(PlayerInteractEvent e) {
        if (!matches.managed(e.getPlayer().getUniqueId())) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        var r = matches.current();
        if (r == null || !r.alive(e.getPlayer().getUniqueId())) return;
        if (e.getAction().isRightClick()) {
            if (!r.interactions.interact(e.getPlayer())
                    && !r.lives.reviving(e.getPlayer().getUniqueId()))
                r.combat.shoot(e.getPlayer());
        } else if (e.getAction().isLeftClick()) r.combat.reload(e.getPlayer());
    }

    @EventHandler
    public void swap(PlayerSwapHandItemsEvent e) {
        if (!matches.managed(e.getPlayer().getUniqueId())) return;
        e.setCancelled(true);
        var r = matches.current();
        if (r != null && r.combat != null) r.combat.reload(e.getPlayer());
    }

    @EventHandler
    public void held(PlayerItemHeldEvent e) {
        var r = matches.current();
        if (r != null && r.combat != null && matches.managed(e.getPlayer().getUniqueId()))
            r.combat.switched(e.getPlayer());
    }

    @EventHandler
    public void click(InventoryClickEvent e) {
        if (matches.managed(e.getWhoClicked().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void drag(InventoryDragEvent e) {
        if (matches.managed(e.getWhoClicked().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void drop(PlayerDropItemEvent e) {
        if (matches.managed(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void pickup(EntityPickupItemEvent e) {
        if (matches.managed(e.getEntity().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void useEntity(PlayerInteractEntityEvent e) {
        if (matches.managed(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void breakBlock(BlockBreakEvent e) {
        if (owned(e.getBlock().getWorld()) || matches.managed(e.getPlayer().getUniqueId()))
            e.setCancelled(true);
    }

    @EventHandler
    public void place(BlockPlaceEvent e) {
        if (owned(e.getBlock().getWorld()) || matches.managed(e.getPlayer().getUniqueId()))
            e.setCancelled(true);
    }

    @EventHandler
    public void food(FoodLevelChangeEvent e) {
        if (matches.managed(e.getEntity().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void portal(PlayerPortalEvent e) {
        if (matches.managed(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void command(PlayerCommandPreprocessEvent e) {
        if (!matches.managed(e.getPlayer().getUniqueId())) return;
        String root = e.getMessage().split(" ", 2)[0].toLowerCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("/zombies", "/jz", "/friesnetzombies:zombies", "/friesnetzombies:jz")
                .contains(root)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void damage(EntityDamageEvent e) {
        var r = matches.current();
        if (r != null && r.waves != null && r.waves.contains(e.getEntity().getUniqueId())) {
            if (!(e instanceof EntityDamageByEntityEvent by)
                    || !(by.getDamager() instanceof Player)
                    || !r.combat.authorizes(e.getEntity().getUniqueId())) e.setCancelled(true);
            return;
        }
        if (!(e.getEntity() instanceof Player player) || !matches.managed(player.getUniqueId()))
            return;
        e.setCancelled(true);
        if (r == null || !r.alive(player.getUniqueId())) return;
        if (e instanceof EntityDamageByEntityEvent by) {
            if (!r.waves.contains(by.getDamager().getUniqueId())) return;
        }
        r.lives.damage(player, e.getFinalDamage());
    }

    @EventHandler
    public void death(EntityDeathEvent e) {
        var r = matches.current();
        if (r != null && r.waves != null && r.waves.contains(e.getEntity().getUniqueId())) {
            e.getDrops().clear();
            e.setDroppedExp(0);
            r.waves.died(e.getEntity().getUniqueId());
        }
    }

    @EventHandler
    public void target(EntityTargetLivingEntityEvent e) {
        var r = matches.current();
        if (r != null
                && r.waves != null
                && r.waves.contains(e.getEntity().getUniqueId())
                && e.getTarget() != null
                && !r.alive(e.getTarget().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void move(PlayerMoveEvent e) {
        if (e instanceof PlayerTeleportEvent) return;
        var r = matches.current();
        if (r == null || r.world == null || e.getPlayer().getWorld() != r.world) return;
        var s = r.survivor(e.getPlayer().getUniqueId());
        if (s == null) return;
        var to = e.getTo();
        if (to == null) return;
        if (s.life() == Survivor.Life.DOWNED || !r.playing()) {
            var from = e.getFrom();
            from.setYaw(to.getYaw());
            from.setPitch(to.getPitch());
            e.setTo(from);
            return;
        }
        var b = r.map.bounds();
        if (!b.contains(
                new net.fries.zombies.common.content.MapTemplate.Point(
                        to.getX(), to.getY(), to.getZ())))
            e.setTo(MapWorlds.location(r.world, r.map.playerSpawn()));
    }

    @EventHandler
    public void explode(EntityExplodeEvent e) {
        if (owned(e.getLocation().getWorld())) {
            e.blockList().clear();
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void blockExplode(BlockExplodeEvent e) {
        if (owned(e.getBlock().getWorld())) e.setCancelled(true);
    }

    @EventHandler
    public void change(EntityChangeBlockEvent e) {
        if (owned(e.getBlock().getWorld())) e.setCancelled(true);
    }

    @EventHandler
    public void transform(EntityTransformEvent e) {
        var run = matches.current();
        if (run != null && run.waves != null && run.waves.contains(e.getEntity().getUniqueId()))
            e.setCancelled(true);
    }

    @EventHandler
    public void burn(BlockBurnEvent e) {
        if (owned(e.getBlock().getWorld())) e.setCancelled(true);
    }

    @EventHandler
    public void spawn(CreatureSpawnEvent e) {
        if (owned(e.getLocation().getWorld())
                && e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CUSTOM)
            e.setCancelled(true);
    }
}
