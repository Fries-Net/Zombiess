package net.fries.zombies.playtest;

import net.fries.zombies.common.content.*;
import net.fries.zombies.common.content.Perks.*;
import net.fries.zombies.common.gameplay.*;
import net.fries.zombies.game.ZombiesPlugin;
import net.fries.zombies.game.match.MatchRun;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

/** Published editor stations and two real clients exercise the same production services. */
final class PaperPerkChecks {
    private static int gold;
    private static long reloadAt;
    private static UUID slowed;
    private static final double[][] POSITIONS = {
        {-6.5, -4.5},
        {-6.5, 4.5},
        {-1.5, -7.5},
        {-1.5, 1.5},
        {3.5, -7.5},
        {10.5, -4.5},
        {10.5, 4.5},
        {-6.5, .5}
    };

    static void author(ZombiesPlugin plugin, Player p) {
        var editor = plugin.matches().editor();
        var s = editor.require(p);
        int i = 0;
        for (var d : plugin.matches().catalog().perks().definitions().values()) {
            if (i == 0) editor.execute(p, "select objects test_perk".split(" "));
            else
                editor.execute(
                        p,
                        new String[] {
                            "add", "perk", "perk_" + d.type().name().toLowerCase(Locale.ROOT)
                        });
            editor.execute(p, new String[] {"set", "perk", d.id().value()});
            editor.execute(p, "set price 17".split(" "));
            p.teleport(new Location(s.world, POSITIONS[i][0], 64, POSITIONS[i][1]));
            editor.execute(p, new String[] {"point"});
            i++;
        }
        p.closeInventory();
    }

    private static MapObject station(MatchRun r, Type type) {
        return r.map.objects().stream()
                .filter(
                        o ->
                                o.kind() == MapObject.Kind.PERK
                                        && r.perkRules
                                                        .require(
                                                                o.property(
                                                                        "perk",
                                                                        "friesnet:perk/extra_health"))
                                                        .type()
                                                == type)
                .findFirst()
                .orElseThrow();
    }

    private static void buy(MatchRun r, Player p, Type type) {
        var at = station(r, type).point();
        p.teleport(new Location(r.world, at.x(), at.y(), at.z()));
        r.perks.interact(p);
    }

    static void seedCrash(MatchRun r, Player p) {
        var s = r.survivor(p.getUniqueId());
        var d = r.perkRules.require("friesnet:perk/extra_health");
        check(
                s.perks().purchase(s, r.perkRules, d, d, 0, 1, false, r.tick),
                "crash perk ownership");
        r.perks.apply(p, true);
        p.setWalkSpeed(.3f);
        p.saveData();
        check(
                p.getAttribute(Attribute.MAX_HEALTH).getValue() >= 40,
                "perk health persisted for crash fixture");
    }

    static boolean handle(String step, ZombiesPlugin plugin) {
        if (!step.startsWith("perk-")) return false;
        var r = plugin.matches().current();
        var p = Bukkit.getPlayerExact("SurvivorOne");
        var other = Bukkit.getPlayerExact("SurvivorTwo");
        var s = r.survivor(p.getUniqueId());
        switch (step) {
            case "perk-start" -> {
                check(
                        r.map.objects().stream()
                                        .filter(o -> o.kind() == MapObject.Kind.PERK)
                                        .count()
                                == 8,
                        "all eight station types published");
                s.credit(10000);
                gold = s.points();
                p.teleport(new Location(r.world, -6.5, 64, -4.5));
                p.getInventory().setHeldItemSlot(7);
            }
            case "perk-health" -> {
                check(
                        s.perks().get(Type.JUGGERNOG) != null && s.points() == gold - 17,
                        "native client vending purchase charges exactly once");
                check(
                        p.getAttribute(Attribute.MAX_HEALTH).getValue() == 40
                                && p.getHealth() == 40,
                        "Juggernog raises max and current health");
                r.perks.interact(p);
                check(s.points() == gold - 17, "duplicate purchase cannot charge or stack");
                buy(r, p, Type.SPEED);
            }
            case "perk-speed" -> {
                buy(r, p, Type.SPEED);
                check(Math.abs(p.getWalkSpeed() - .26) < .001, "Speed native movement");
            }
            case "perk-cola" -> {
                buy(r, p, Type.SPEED_COLA);
                var w = s.weapons().values().iterator().next();
                w.shoot(r.tick);
                w.reload(r.tick, s.reloadSpeed());
                reloadAt = r.tick;
                check(
                        w.reloadRemaining(reloadAt)
                                == WeaponTiming.duration(w.definition().reloadTicks(), 2),
                        "Speed Cola reload deadline");
                w.tick(reloadAt + 1000);
            }
            case "perk-tap" -> {
                buy(r, p, Type.DOUBLE_TAP);
                check(
                        s.fireRate() == 1.25 && s.damageMultiplier() == 1.2,
                        "Double Tap configured rate and damage");
                var w = new WeaponState(s.weapons().values().iterator().next().definition());
                check(
                        w.shoot(r.tick, s.fireRate(), w.definition().cooldownTicks())
                                == WeaponState.Shot.FIRED,
                        "modified shot accepted");
                check(
                        w.readyAt() - r.tick
                                == WeaponTiming.duration(w.definition().cooldownTicks(), 1.25),
                        "Double Tap actual weapon cooldown");
            }
            case "perk-cap" -> {
                int before = s.points();
                buy(r, p, Type.MULE_KICK);
                check(
                        s.points() == before && s.perks().owned().size() == 4,
                        "fifth perk rejected without charge");
                s.perks().clear();
                r.perks.apply(p, false);
            }
            case "perk-mule" -> {
                buy(r, p, Type.MULE_KICK);
                check(s.perks().slots(r.perkRules) == 4, "Mule Kick expands slots");
                for (var w : r.content.weapons().values()) {
                    if (s.weapons().size() == 4) break;
                    s.weapons().putIfAbsent(w.id(), new WeaponState(w));
                }
                r.combat.equip(p);
                check(
                        r.perks.freeWeaponSlot(p) == -1,
                        "logical capacity prevents overflow despite empty hotbar");
            }
            case "perk-frozen" -> {
                buy(r, p, Type.FROZEN_BULLETS);
                var z =
                        r.world.getEntitiesByClass(Zombie.class).stream()
                                .filter(v -> r.waves.contains(v.getUniqueId()))
                                .findFirst()
                                .orElseThrow();
                z.teleport(new Location(r.world, -4.5, 64, -6.5));
                z.setAI(false);
                z.setInvulnerable(false);
                z.setNoDamageTicks(0);
                z.getAttribute(Attribute.MAX_HEALTH).setBaseValue(500);
                z.setHealth(500);
                slowed = z.getUniqueId();
                p.teleport(new Location(r.world, -4.5, 64, -2.5));
                var direction =
                        z.getLocation()
                                .add(0, 1, 0)
                                .toVector()
                                .subtract(p.getEyeLocation().toVector());
                var at = p.getLocation();
                at.setDirection(direction);
                p.teleport(at);
                p.getInventory().setHeldItemSlot(0);
                r.combat.shoot(p);
                check(
                        z.hasPotionEffect(PotionEffectType.SLOWNESS),
                        "accepted firearm damage applies configured Frozen Bullets");
                check(
                        Math.abs(
                                        z.getHealth()
                                                - (500
                                                        - r.combat.held(p).definition().damage()
                                                                * 1.2))
                                < .01,
                        "Double Tap modifies actual valid damage");
                z.removePotionEffect(PotionEffectType.SLOWNESS);
                z.setInvulnerable(true);
                r.combat.shoot(p);
                check(
                        !z.hasPotionEffect(PotionEffectType.SLOWNESS),
                        "invalid repeated hit cannot freeze");
                z.setInvulnerable(false);
            }
            case "perk-down" -> {
                p.setNoDamageTicks(0);
                p.damage(1000);
                check(
                        s.life() == Survivor.Life.DOWNED && s.perks().owned().isEmpty(),
                        "downing clears perks");
                check(
                        p.getAttribute(Attribute.MAX_HEALTH).getValue() == 20
                                && p.getWalkSpeed() == 0,
                        "health and speed removed on down");
                check(
                        s.weapons().size() == 3 && p.getInventory().getItem(3) == null,
                        "Mule Kick excess instance removed without drops");
                check(!s.perks().awaitingSoloRevive(), "team down never grants solo rescue");
                other.teleport(new Location(r.world, -4.5, 64, -1));
                buy(r, other, Type.QUICK_REVIVE);
                other.teleport(p.getLocation().clone().add(1, 0, 0));
                other.setSneaking(true);
            }
            case "perk-revived" -> {
                check(
                        s.life() == Survivor.Life.ALIVE,
                        "Quick Revive finishes before standard revive deadline");
                check(
                        Math.abs(p.getWalkSpeed() - .2) < .001 && s.perks().owned().isEmpty(),
                        "revive restores unperked movement");
                other.setSneaking(false);
                // Verify last living teammate is not reclassified as a solo match.
                check(!r.perks.solo(), "team roster defines solo policy");
                var z = (Zombie) Bukkit.getEntity(slowed);
                if (z != null) z.remove();
            }
            case "perk-wunder" -> {
                buy(r, p, Type.WUNDERFIZZ);
                check(
                        s.perks().owned().size() == 1 && s.perks().get(Type.WUNDERFIZZ) == null,
                        "Wunderfizz grants an eligible effect, never itself");
                int before = s.points();
                buy(r, p, Type.WUNDERFIZZ);
                check(s.points() == before, "same-tick random station spam cannot double charge");
            }
            case "perk-combat-tap" -> buy(r, p, Type.DOUBLE_TAP);
            case "perk-solo-start" -> {
                check(r.perks.solo(), "one-player match uses explicit solo policy");
                s.credit(10000);
                buy(r, p, Type.QUICK_REVIVE);
                check(
                        s.perks().get(Type.QUICK_REVIVE) != null,
                        "solo station purchase after first round starts");
                p.setNoDamageTicks(0);
                p.damage(1000);
                check(
                        s.life() == Survivor.Life.DOWNED && s.perks().awaitingSoloRevive(),
                        "solo down consumes Quick Revive and schedules rescue");
            }
            case "perk-solo-revived" -> {
                check(
                        r.playing()
                                && s.life() == Survivor.Life.ALIVE
                                && s.perks().owned().isEmpty(),
                        "solo rescue completes without ending the match or retaining perks");
                check(
                        p.getHealth() == 10 && p.getWalkSpeed() == .2f,
                        "solo revive health and movement");
            }
            default -> throw new IllegalArgumentException("Unknown perk stage " + step);
        }
        return true;
    }

    private static void check(boolean condition, String text) {
        if (!condition) throw new AssertionError(text);
    }
}
