package net.fries.zombies.game.world;

import net.fries.zombies.common.content.MapTemplate;
import net.fries.zombies.common.gameplay.WeaponState;
import net.fries.zombies.game.match.MatchRun;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;

import java.util.*;

/** Server-thread purchases and interruptible repairs; ownership and gold are checked together. */
public final class InteractionService {
    private record Repair(String id, long started) {}

    private final MatchRun run;
    private final Set<String> opened = new HashSet<>();
    private final Map<UUID, Repair> repairs = new HashMap<>();
    private final Map<String, Integer> repairRewards = new HashMap<>();
    private final List<Entity> displays = new ArrayList<>();

    public InteractionService(MatchRun run) {
        this.run = run;
    }

    public void labels() {
        for (var g : run.map.gates()) label(g.point(), g.name() + " • " + g.price() + " gold");
        for (var s : run.map.shops()) {
            var w = run.content.weapons().get(s.weapon());
            label(s.point(), w.name() + " • " + w.price() + " | Ammo " + w.ammoPrice());
        }
    }

    private void label(MapTemplate.Point p, String text) {
        displays.add(
                run.world.spawn(
                        MapWorlds.location(run.world, p).add(0, 1.5, 0),
                        TextDisplay.class,
                        d -> {
                            d.text(Component.text(text, NamedTextColor.GOLD));
                            d.setBillboard(Display.Billboard.CENTER);
                            d.setPersistent(false);
                            d.setSeeThrough(false);
                            d.setViewRange(.5f);
                        }));
    }

    private boolean near(Player p, MapTemplate.Point point, double radius) {
        var at = MapWorlds.location(run.world, point);
        if (p.getWorld() != run.world || p.getEyeLocation().distanceSquared(at) > radius * radius)
            return false;
        var direction = at.toVector().subtract(p.getEyeLocation().toVector());
        double distance = direction.length();
        if (distance < .01) return true;
        var hit =
                run.world.rayTraceBlocks(
                        p.getEyeLocation(),
                        direction.normalize(),
                        distance,
                        FluidCollisionMode.NEVER,
                        true);
        // The interaction's own block may occlude its center; other walls must not allow remote
        // purchases.
        return hit == null || hit.getHitPosition().distance(at.toVector()) < 1.6;
    }

    public boolean interact(Player p) {
        if (!run.alive(p.getUniqueId())) return false;
        var survivor = run.survivor(p.getUniqueId());
        if (p.isSneaking())
            for (var b : run.map.barricades())
                if (near(p, b.point(), 3.5) && missing(b) != null) {
                    repairs.put(p.getUniqueId(), new Repair(b.id(), run.tick));
                    return true;
                }
        for (var g : run.map.gates())
            if (!opened.contains(g.id()) && near(p, g.point(), 3.5)) {
                if (!survivor.spend(g.price())) {
                    message(p, "Need " + g.price() + " gold");
                    return true;
                }
                MapWorlds.each(run.world, g.box(), b -> b.setType(Material.AIR, false));
                opened.add(g.id());
                run.world.playSound(p.getLocation(), Sound.BLOCK_IRON_DOOR_OPEN, 1, .7f);
                run.world
                        .getPlayers()
                        .forEach(t -> message(t, p.getName() + " opened " + g.name()));
                return true;
            }
        for (var shop : run.map.shops())
            if (near(p, shop.point(), 3.5)) {
                var w = run.content.weapons().get(shop.weapon());
                var owned = survivor.weapons().get(shop.weapon());
                if (owned != null) {
                    if (owned.reserve() == w.reserve()) {
                        message(p, "Reserve ammo is full");
                        return true;
                    }
                    if (!survivor.spend(w.ammoPrice())) {
                        message(p, "Need " + w.ammoPrice() + " gold for ammo");
                        return true;
                    }
                    owned.refill();
                    message(p, w.name() + " reserve refilled");
                } else {
                    if (!survivor.spend(w.price())) {
                        message(p, "Need " + w.price() + " gold");
                        return true;
                    }
                    var state = new WeaponState(w);
                    int slot = survivor.weapons().size();
                    survivor.weapons().put(w.id(), state);
                    p.getInventory().setItem(slot, run.combat.item(state));
                    message(p, "Purchased " + w.name() + " • slot " + (slot + 1));
                }
                p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1, 1);
                return true;
            }
        return false;
    }

    public void tick() {
        for (var id : List.copyOf(repairs.keySet())) {
            var channel = repairs.get(id);
            var p = Bukkit.getPlayer(id);
            var b =
                    run.map.barricades().stream()
                            .filter(v -> v.id().equals(channel.id))
                            .findFirst()
                            .orElseThrow();
            if (p == null
                    || !p.isSneaking()
                    || !run.alive(id)
                    || run.lives.reviving(id)
                    || !near(p, b.point(), 3.5)) {
                repairs.remove(id);
                continue;
            }
            var block = missing(b);
            if (block == null) {
                repairs.remove(id);
                continue;
            }
            if (run.tick - channel.started >= run.content.repairTicks()) {
                block.setType(MapWorlds.material(b.material()), false);
                run.points.repair(id,b.id(),block.getX()+":"+block.getY()+":"+block.getZ(),run.waves.round(),true,run.content.repairPoints(),(int)b.box().volume()*run.content.repairPoints(),run.tick);
                p.playSound(p.getLocation(), Sound.BLOCK_WOOD_PLACE, .5f, 1);
                repairs.put(id, new Repair(b.id(), run.tick));
            }
        }
    }

    public void zombieDamage(Collection<Zombie> zombies) {
        for (var b : run.map.barricades()) {
            var at = MapWorlds.location(run.world, b.point());
            if (zombies.stream()
                    .noneMatch(z -> z.isValid() && z.getLocation().distanceSquared(at) < 12))
                continue;
            final Block[] chosen = {null};
            MapWorlds.each(
                    run.world,
                    b.box(),
                    block -> {
                        if (!block.getType().isAir()) chosen[0] = block;
                    });
            if (chosen[0] != null) {
                chosen[0].setType(Material.AIR, false);
                run.world.playSound(at, Sound.BLOCK_WOOD_BREAK, .6f, .7f);
            }
        }
    }

    private Block missing(MapTemplate.Barricade b) {
        final Block[] missing = {null};
        MapWorlds.each(
                run.world,
                b.box(),
                block -> {
                    if (block.getType().isAir() && missing[0] == null) missing[0] = block;
                });
        return missing[0];
    }

    public String context(Player p) {
        var repair = repairs.get(p.getUniqueId());
        if (repair != null)
            return "Repairing "
                    + Math.min(100, (run.tick - repair.started) * 100 / run.content.repairTicks())
                    + "%";
        for (var b : run.map.barricades())
            if (near(p, b.point(), 3.5) && missing(b) != null)
                return "Sneak + right-click to repair";
        for (var g : run.map.gates())
            if (!opened.contains(g.id()) && near(p, g.point(), 3.5))
                return "Right-click • " + g.name() + " • " + g.price() + " gold";
        for (var s : run.map.shops())
            if (near(p, s.point(), 3.5)) {
                var w = run.content.weapons().get(s.weapon());
                boolean owned = run.survivor(p.getUniqueId()).weapons().containsKey(s.weapon());
                return "Right-click • "
                        + w.name()
                        + (owned ? " ammo • " + w.ammoPrice() : " • " + w.price())
                        + " gold";
            }
        return "";
    }

    public boolean open(String gate) {
        return opened.contains(gate);
    }

    public void clear() {
        displays.forEach(Entity::remove);
        displays.clear();
        repairs.clear();
    }

    private void message(Player p, String text) {
        p.sendMessage(Component.text(text, NamedTextColor.GOLD));
    }
}
