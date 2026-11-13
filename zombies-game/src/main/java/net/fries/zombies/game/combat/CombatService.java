package net.fries.zombies.game.combat;

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

import java.util.List;

/** Hitscan against tracked zombies with block occlusion and server-owned weapon identities. */
public final class CombatService {
    private final MatchRun run;
    private final NamespacedKey instanceKey;
    private java.util.UUID damageTarget;

    public CombatService(JavaPlugin plugin, MatchRun run) {
        this.run = run;
        instanceKey = new NamespacedKey(plugin, "weapon_instance");
    }

    public void equip(Player player) {
        var s = run.survivor(player.getUniqueId());
        if (s == null) return;
        player.getInventory().clear();
        int slot = 0;
        for (var weapon : s.weapons().values()) player.getInventory().setItem(slot++, item(weapon));
        player.getInventory().setHeldItemSlot(0);
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
        var shot = state.shoot(run.tick);
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
        var hit =
                run.world.rayTraceEntities(
                        eye,
                        direction,
                        range,
                        0.0,
                        e -> e instanceof Zombie && run.waves.contains(e.getUniqueId()));
        var end =
                hit == null
                        ? eye.toVector().add(direction.clone().multiply(range))
                        : hit.getHitPosition();
        double distance = eye.toVector().distance(end);
        for (double d = .5; d < Math.min(distance, 40); d += 1.25)
            run.world.spawnParticle(
                    Particle.CRIT, eye.clone().add(direction.clone().multiply(d)), 1, 0, 0, 0, 0);
        run.world.playSound(
                player.getLocation(),
                Sound.ENTITY_FIREWORK_ROCKET_BLAST,
                .6f,
                state.definition().magazine() > 20 ? 1.4f : 1.8f);
        if (hit == null || !(hit.getHitEntity() instanceof Zombie zombie)) return;
        boolean headshot = hit.getHitPosition().getY() >= zombie.getEyeLocation().getY() - .25;
        double damage = w.damage() * (headshot ? w.headshotMultiplier() : 1);
        // Clear vanilla invulnerability: the configured firearm cooldown is authoritative.
        zombie.setNoDamageTicks(0);
        boolean lethal = zombie.getHealth() <= damage;
        run.waves.attribute(zombie.getUniqueId(), player.getUniqueId(), headshot);
        damageTarget = zombie.getUniqueId();
        try {
            zombie.damage(damage, player);
        } finally {
            damageTarget = null;
        }
        if (!lethal) run.waves.forgetAttribution(zombie.getUniqueId());
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
        if (w != null && w.reload(run.tick))
            player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_IRON, 1, 1);
    }

    public void switched(Player player) {
        var w = held(player);
        if (w != null) w.cancelReload();
    }

    public boolean authorizes(java.util.UUID target) {
        return target.equals(damageTarget);
    }

    public void tick() {
        for (var p : run.players.values()) p.weapons().values().forEach(w -> w.tick(run.tick));
    }
}
