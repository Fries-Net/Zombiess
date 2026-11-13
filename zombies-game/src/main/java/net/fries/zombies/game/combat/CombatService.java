package net.fries.zombies.game.combat;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Consumable;
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation;

import net.fries.zombies.common.gameplay.PointsLedger;
import net.fries.zombies.common.gameplay.WeaponState;
import net.fries.zombies.game.match.MatchRun;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.List;

/** Hitscan against tracked zombies with block occlusion and server-owned weapon identities. */
public final class CombatService {
    private final MatchRun run;
    private final NamespacedKey instanceKey;
    private final MatchDamageService damageService;

    public CombatService(JavaPlugin plugin, MatchRun run) {
        this.run = run;
        damageService = new MatchDamageService(run);
        instanceKey = new NamespacedKey(plugin, "weapon_instance");
    }

    public void equip(Player player) {
        var s = run.survivor(player.getUniqueId());
        if (s == null) return;
        player.getInventory().clear();
        int slot = 0;
        for (var weapon : s.weapons().values()) player.getInventory().setItem(slot++, item(weapon));
        player.getInventory().setHeldItemSlot(0);
        var knife = new ItemStack(Material.IRON_SWORD);
        knife.editMeta(
                m -> {
                    m.displayName(Component.text("Knife", NamedTextColor.WHITE));
                    m.setUnbreakable(true);
                    m.getPersistentDataContainer()
                            .set(
                                    new NamespacedKey(instanceKey.getNamespace(), "knife"),
                                    PersistentDataType.BYTE,
                                    (byte) 1);
                });
        player.getInventory().setItem(8, knife);
    }

    public ItemStack item(WeaponState state) {
        var item = new ItemStack(Material.valueOf(state.definition().material()));
        var meta = item.getItemMeta();
        meta.displayName(Component.text(state.definition().name(), NamedTextColor.GOLD));
        meta.lore(
                List.of(
                        Component.text("Right-click: fire | F: reload", NamedTextColor.GRAY),
                        Component.text(
                                "Ammo is shown in the action bar", NamedTextColor.DARK_GRAY)));
        meta.setUnbreakable(true);
        meta.getPersistentDataContainer()
                .set(instanceKey, PersistentDataType.STRING, state.instanceId().toString());
        item.setItemMeta(meta);
        item.editMeta(
                m ->
                        m.getPersistentDataContainer()
                                .set(
                                        new NamespacedKey(instanceKey.getNamespace(), "asset"),
                                        PersistentDataType.STRING,
                                        state.definition().behaviour().asset().value()));
        // Native start/stop-use provides real hold/release semantics instead of a guessed click
        // lease.
        item.setData(
                DataComponentTypes.CONSUMABLE,
                Consumable.consumable()
                        .consumeSeconds(3600)
                        .animation(ItemUseAnimation.NONE)
                        .sound(net.kyori.adventure.key.Key.key("minecraft:intentionally_empty"))
                        .hasConsumeParticles(false)
                        .build());
        return item;
    }

    public WeaponState held(Player player) {
        var s = run.survivor(player.getUniqueId());
        if (s == null) return null;
        var item = player.getInventory().getItemInMainHand();
        if (!item.hasItemMeta()) return null;
        String id =
                item.getItemMeta()
                        .getPersistentDataContainer()
                        .get(instanceKey, PersistentDataType.STRING);
        return s.weapons().values().stream()
                .filter(w -> w.instanceId().toString().equals(id))
                .findFirst()
                .orElse(null);
    }

    public void shoot(Player player) {
        if (!run.alive(player.getUniqueId()) || player.getWorld() != run.world) return;
        var state = held(player);
        if (state == null) return;
        var s = run.survivor(player.getUniqueId());
        accepted(player, state, s.fireControl().attempt(state, run.tick, s.fireRate()));
    }

    public void press(Player player) {
        if (!run.alive(player.getUniqueId()) || player.getWorld() != run.world) return;
        var state = held(player);
        if (state == null) return;
        var s = run.survivor(player.getUniqueId());
        accepted(player, state, s.fireControl().press(state, run.tick, s.fireRate()));
    }

    public void release(Player player) {
        var s = run.survivor(player.getUniqueId());
        if (s != null) s.fireControl().release();
    }

    private void accepted(Player player, WeaponState state, WeaponState.Shot shot) {
        if (shot == WeaponState.Shot.EMPTY) {
            reload(player);
            return;
        }
        if (shot != WeaponState.Shot.FIRED) return;
        var w = state.definition();
        var eye = player.getEyeLocation();
        var direction = eye.getDirection();
        var block =
                run.world.rayTraceBlocks(eye, direction, w.range(), FluidCollisionMode.NEVER, true);
        double range = block == null ? w.range() : eye.toVector().distance(block.getHitPosition());
        var hits = new ArrayList<org.bukkit.util.RayTraceResult>();
        for (var z : run.world.getEntitiesByClass(Zombie.class))
            if (run.waves.contains(z.getUniqueId()) && !z.isDead()) {
                var hit = z.getBoundingBox().rayTrace(eye.toVector(), direction, range);
                if (hit != null)
                    hits.add(new org.bukkit.util.RayTraceResult(hit.getHitPosition(), z));
            }
        hits.sort(
                Comparator.comparingDouble(
                        h -> h.getHitPosition().distanceSquared(eye.toVector())));
        var targets = hits.stream().limit(w.behaviour().pierceTargets()).toList();
        var hit = targets.isEmpty() ? null : targets.getLast();
        var end =
                hit == null
                        ? eye.toVector().add(direction.clone().multiply(range))
                        : hit.getHitPosition();
        double distance = eye.toVector().distance(end);
        for (double d = .5; d < Math.min(distance, 40); d += 1.25)
            run.world.spawnParticle(
                    Particle.valueOf(w.behaviour().particle()),
                    eye.clone().add(direction.clone().multiply(d)),
                    1,
                    0,
                    0,
                    0,
                    0);
        run.world.playSound(
                player.getLocation(), w.behaviour().sound(), .6f, w.behaviour().pitch());
        UUID bullet = UUID.randomUUID();
        for (var target : targets)
            damage(player, state, (Zombie) target.getHitEntity(), target.getHitPosition(), bullet);
    }

    private void damage(
            Player player,
            WeaponState state,
            Zombie zombie,
            org.bukkit.util.Vector hit,
            UUID bullet) {
        if (zombie == null
                || zombie.isDead()
                || zombie.isInvulnerable()
                || !run.waves.contains(zombie.getUniqueId())) return;
        var w = state.definition();
        boolean headshot = hit.getY() >= zombie.getEyeLocation().getY() - .25;
        double damage =
                w.damage()
                        * (headshot ? w.headshotMultiplier() : 1)
                        * run.survivor(player.getUniqueId()).damageMultiplier();
        if (damageService.apply(
                        bullet, player, zombie, damage, headshot, PointsLedger.Kind.BULLET, w)
                <= 0) return;
        player.playSound(
                player.getLocation(),
                headshot ? Sound.BLOCK_NOTE_BLOCK_PLING : Sound.ENTITY_ARROW_HIT_PLAYER,
                .5f,
                headshot ? 1.8f : 1.2f);
        player.sendActionBar(
                Component.text(
                        headshot ? "HEADSHOT" : "HIT",
                        headshot ? NamedTextColor.GOLD : NamedTextColor.WHITE));
    }

    public void reload(Player player) {
        if (!run.alive(player.getUniqueId())) return;
        var w = held(player);
        var s = run.survivor(player.getUniqueId());
        s.fireControl().cancel();
        player.clearActiveItem();
        if (w != null && w.reload(run.tick, s.reloadSpeed()))
            player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_IRON, 1, 1);
    }

    public boolean knife(Player player) {
        var item = player.getInventory().getItemInMainHand();
        return item.hasItemMeta()
                && item.getItemMeta()
                        .getPersistentDataContainer()
                        .has(
                                new NamespacedKey(instanceKey.getNamespace(), "knife"),
                                PersistentDataType.BYTE);
    }

    public void melee(Player player, Zombie enemy) {
        if (!run.alive(player.getUniqueId())
                || !knife(player)
                || !enemy.isValid()
                || enemy.isDead()
                || enemy.isInvulnerable()
                || !run.waves.contains(enemy.getUniqueId())
                || player.getWorld() != enemy.getWorld()
                || player.getEyeLocation().distanceSquared(enemy.getEyeLocation()) > 20.25
                || !player.hasLineOfSight(enemy)) return;
        if (!run.survivor(player.getUniqueId()).fireControl().melee(run.tick, 10)) return;
        damageService.apply(
                UUID.randomUUID(),
                player,
                enemy,
                6,
                false,
                PointsLedger.Kind.MELEE,
                run.content.weapons().get(run.content.starter()));
    }

    public void switched(Player player) {
        var w = held(player);
        if (w != null) w.cancelReload();
        var s = run.survivor(player.getUniqueId());
        if (s != null) s.fireControl().cancel();
        player.clearActiveItem();
    }

    public boolean authorizes(java.util.UUID target) {
        return damageService.authorizes(target);
    }

    public MatchDamageService damageService() {
        return damageService;
    }

    public void tick() {
        for (var p : run.players.values()) p.weapons().values().forEach(w -> w.tick(run.tick));
        for (var s : run.players.values()) {
            var p = Bukkit.getPlayer(s.id());
            if (p == null || !run.alive(s.id())) {
                s.fireControl().cancel();
                continue;
            }
            var w = held(p);
            if (w == null) {
                s.fireControl().cancel();
                continue;
            }
            accepted(p, w, s.fireControl().poll(w, run.tick, s.fireRate(), p.hasActiveItem()));
        }
    }
}
