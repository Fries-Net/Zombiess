package net.fries.zombies.playtest;

import net.fries.zombies.common.content.*;
import net.fries.zombies.common.gameplay.*;
import net.fries.zombies.game.ZombiesPlugin;
import net.fries.zombies.game.match.MatchRun;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

final class PaperUpgradeChecks {
    private static UUID instance;
    private static int magazine, reserve;
    private static long deadline;

    static void author(ZombiesPlugin plugin, Player p) {
        var editor = plugin.matches().editor();
        var s = editor.require(p);
        editor.execute(p, "select objects test_pack_a_punch".split(" "));
        editor.execute(p, "set weapons friesnet:weapon/glock18".split(" "));
        editor.execute(p, "set price -1".split(" "));
        p.teleport(new Location(s.world, 3.5, 64, 4.5));
        editor.execute(p, new String[] {"point"});
        p.closeInventory();
    }

    static void seedCrash(MatchRun r, Player p) {
        var s = r.survivor(p.getUniqueId());
        var w = s.weapons().get(r.content.starter());
        check(w.upgrade(s, r.upgradeRules, 0, 0, r.tick), "crash upgrade fixture");
        r.combat.equip(p);
        p.saveData();
    }

    static boolean handle(String step, ZombiesPlugin plugin) {
        if (!step.startsWith("pap-")) return false;
        var r = plugin.matches().current();
        var p = Bukkit.getPlayerExact("SurvivorOne");
        var s = r.survivor(p.getUniqueId());
        var w = s.weapons().get(r.content.starter());
        switch (step) {
            case "pap-start" -> {
                s.perks().clear();
                r.perks.apply(p, false);
                p.teleport(new Location(r.world, 3.5, 64, 4.5));
                int gold = s.points();
                p.getInventory().setHeldItemSlot(1);
                r.packAPunch.interact(p);
                check(s.points() == gold, "station weapon restrictions never charge");
                s.spend(s.points());
                p.getInventory().setHeldItemSlot(0);
                r.packAPunch.interact(p);
                check(
                        w.upgradeLevel() == 0 && s.points() == 0,
                        "insufficient funds never mutate or charge");
                w.shoot(r.tick);
                w.reload(r.tick);
                s.credit(50000);
                r.packAPunch.interact(p);
                check(
                        w.upgradeLevel() == 0 && s.points() == 50000,
                        "reloading cannot bypass upgrade restrictions");
                w.cancelReload();
                instance = w.instanceId();
                magazine = w.magazine();
                reserve = w.reserve();
                deadline = w.readyAt();
            }
            case "pap-upgraded" -> {
                check(
                        w.upgradeLevel() == 1 && s.points() == 45000,
                        "native double input purchases one configured tier");
                check(
                        w.instanceId().equals(instance)
                                && w.magazine() == magazine
                                && w.reserve() == reserve,
                        "upgrade retains owned instance and remaining ammo");
                check(w.readyAt() == deadline, "upgrade cannot reset shot recovery");
                check(
                        w.definition().damage() == 12 && w.definition().magazine() == 15,
                        "configured first-tier stats installed");
                var item = p.getInventory().getItemInMainHand();
                check(
                        Boolean.TRUE.equals(item.getItemMeta().getEnchantmentGlintOverride()),
                        "upgraded item has native visual glint");
                check(
                        "friesnet:weapon/glock18/mk2"
                                .equals(
                                        item.getItemMeta()
                                                .getPersistentDataContainer()
                                                .get(
                                                        new NamespacedKey(
                                                                "friesnetzombies", "asset"),
                                                        PersistentDataType.STRING)),
                        "upgraded asset reference attached");
                check(r.combat.held(p) == w, "client item resolves same authoritative instance");
            }
            case "pap-max" -> {
                if (w.reloading()) {
                    r.packAPunch.interact(p);
                    check(
                            s.points() == 45000 && w.upgradeLevel() == 1,
                            "native swing reload safely blocks a subsequent upgrade");
                    // Native activateBlock swings may invoke the existing left-click reload
                    // binding.
                    // A production weapon switch cancels that reload without completing it.
                    r.combat.switched(p);
                }
                r.packAPunch.interact(p);
                check(
                        w.upgradeLevel() == 2 && s.points() == 35000,
                        "second tier uses weapon-specific cost: level="
                                + w.upgradeLevel()
                                + " points="
                                + s.points()
                                + " tick="
                                + r.tick
                                + " held="
                                + r.combat.held(p)
                                + " at="
                                + p.getLocation());
                r.packAPunch.interact(p);
                check(
                        w.upgradeLevel() == 2 && s.points() == 35000,
                        "maximum upgrade level never charges again");
                check(w.instanceId().equals(instance), "tier progression retains identity");
            }
            case "pap-combat" -> {
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
                p.teleport(new Location(r.world, -4.5, 64, -2.5));
                var at = p.getLocation();
                at.setDirection(
                        z.getLocation()
                                .add(0, 1, 0)
                                .toVector()
                                .subtract(p.getEyeLocation().toVector()));
                p.teleport(at);
                r.combat.shoot(p);
                check(
                        Math.abs(z.getHealth() - 480) < .01,
                        "upgraded firearm delivers configured damage");
                r.combat.reload(p);
                check(w.reloadRemaining(r.tick) == 24, "upgraded reload duration");
                w.tick(r.tick + 1000);
                p.teleport(new Location(r.world, -12.5, 64, .5));
                r.interactions.interact(p);
                check(
                        w.reserve() == 320
                                && w.upgradeLevel() == 2
                                && w.instanceId().equals(instance),
                        "wall ammo refills upgraded capacity without replacing instance");
            }
            case "pap-restored" -> {
                check(
                        w.upgradeLevel() == 0 && !w.instanceId().equals(instance),
                        "future match has fresh unupgraded instances");
                check(
                        !Boolean.TRUE.equals(
                                p.getInventory()
                                        .getItem(0)
                                        .getItemMeta()
                                        .getEnchantmentGlintOverride()),
                        "future match has base visuals");
            }
            case "pap-down" -> {
                p.setNoDamageTicks(0);
                p.damage(1000);
                check(
                        s.life() == Survivor.Life.DOWNED
                                && w.upgradeLevel() == 2
                                && w.instanceId().equals(instance),
                        "downing retains upgrades on retained weapons");
                var other = Bukkit.getPlayerExact("SurvivorTwo");
                other.teleport(p.getLocation().clone().add(1, 0, 0));
                other.setSneaking(true);
            }
            case "pap-revived" -> {
                check(
                        s.life() == Survivor.Life.ALIVE
                                && r.combat.held(p) == w
                                && w.upgradeLevel() == 2,
                        "revive retains upgraded weapon and client identity");
                Bukkit.getPlayerExact("SurvivorTwo").setSneaking(false);
            }
            default -> throw new IllegalArgumentException("Unknown upgrade stage");
        }
        return true;
    }

    private static void check(boolean condition, String text) {
        if (!condition) throw new AssertionError(text);
    }
}
