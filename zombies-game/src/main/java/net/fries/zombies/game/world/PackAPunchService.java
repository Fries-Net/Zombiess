package net.fries.zombies.game.world;

import net.fries.zombies.common.content.*;
import net.fries.zombies.common.gameplay.WeaponState;
import net.fries.zombies.game.match.MatchRun;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.*;
import org.bukkit.entity.*;

import java.util.*;

/** Immediate in-place upgrades: the weapon never leaves its authoritative owner. */
public final class PackAPunchService {
    private final MatchRun run;
    private final List<MapObject> stations;
    private final List<TextDisplay> labels = new ArrayList<>();

    public PackAPunchService(MatchRun run) {
        this.run = run;
        stations =
                run.map.objects().stream()
                        .filter(o -> o.kind() == MapObject.Kind.PACK_A_PUNCH)
                        .toList();
        for (var s : stations)
            labels.add(
                    run.world.spawn(
                            MapWorlds.location(run.world, s.point()).add(0, 1.5, 0),
                            TextDisplay.class,
                            d -> {
                                d.setPersistent(false);
                                d.setBillboard(Display.Billboard.CENTER);
                                d.text(
                                        Component.text(
                                                "Pack-a-Punch • hold a weapon",
                                                NamedTextColor.LIGHT_PURPLE));
                            }));
    }

    private MapObject nearest(Player p) {
        return stations.stream()
                .filter(s -> run.interactions.near(p, s.point(), 3.5))
                .min(
                        Comparator.comparingDouble(
                                s ->
                                        p.getEyeLocation()
                                                .distanceSquared(
                                                        MapWorlds.location(run.world, s.point()))))
                .orElse(null);
    }

    private boolean allows(MapObject station, WeaponState weapon) {
        String allowed = station.property("weapons", "*");
        return allowed.equals("*")
                || Arrays.stream(allowed.split(","))
                        .map(String::trim)
                        .anyMatch(id -> id.equals(weapon.definition().id().value()));
    }

    private int price(MapObject station, WeaponUpgrades.Tier tier) {
        int price = station.integer("price", -1, -1, 100000);
        return price < 0 ? tier.cost() : price;
    }

    private String denial(Player p, MapObject station, WeaponState weapon) {
        if (!station.rooms().stream().allMatch(run.interactions::roomUnlocked))
            return "Unlock this upgrade station's room first";
        if (weapon == null) return "Hold an owned firearm to upgrade";
        if (!allows(station, weapon)) return "This station does not upgrade that weapon";
        if (run.upgradeRules.next(weapon.definition().id(), weapon.upgradeLevel()) == null)
            return "No further upgrades available";
        if (weapon.reloading()) return "Finish or cancel reloading before upgrading";
        return "";
    }

    public boolean interact(Player p) {
        if (!run.alive(p.getUniqueId())) return false;
        var station = nearest(p);
        if (station == null) return false;
        var weapon = run.combat.held(p);
        String denied = denial(p, station, weapon);
        if (!denied.isEmpty()) {
            message(p, denied);
            return true;
        }
        var tier = run.upgradeRules.next(weapon.definition().id(), weapon.upgradeLevel());
        // Render before the charge; cosmetic construction cannot lose gold or create an unowned
        // gun.
        var item = run.combat.upgradeItem(weapon, tier);
        int level = weapon.upgradeLevel(), cost = price(station, tier);
        if (!weapon.upgrade(
                run.survivor(p.getUniqueId()), run.upgradeRules, level, cost, run.tick)) {
            message(p, "Need " + cost + " gold; wait briefly between upgrades");
            return true;
        }
        run.combat.switched(p);
        p.getInventory().setItemInMainHand(item);
        p.playSound(p.getLocation(), tier.upgradeSound().value(), 1, 1);
        run.world.spawnParticle(Particle.ENCHANT, p.getLocation().add(0, 1, 0), 30, .5, .6, .5, .1);
        message(
                p,
                weapon.definition().name()
                        + " • level "
                        + weapon.upgradeLevel()
                        + " • "
                        + cost
                        + " gold");
        return true;
    }

    public String context(Player p) {
        var station = nearest(p);
        if (station == null) return "";
        var weapon = run.combat.held(p);
        var denied = denial(p, station, weapon);
        if (!denied.isEmpty()) return "Pack-a-Punch • " + denied;
        var tier = run.upgradeRules.next(weapon.definition().id(), weapon.upgradeLevel());
        return "Right-click • " + tier.name() + " • " + price(station, tier) + " gold";
    }

    public void clear() {
        labels.forEach(Entity::remove);
        labels.clear();
    }

    private static void message(Player p, String text) {
        p.sendMessage(Component.text(text, NamedTextColor.LIGHT_PURPLE));
        p.sendActionBar(Component.text(text, NamedTextColor.LIGHT_PURPLE));
    }
}
