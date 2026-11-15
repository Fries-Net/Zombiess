package net.fries.zombies.playtest;

import net.fries.zombies.common.content.*;
import net.fries.zombies.game.match.MatchRun;
import net.fries.zombies.game.world.InteractionTargeting;

import org.bukkit.*;
import org.bukkit.entity.Player;

import java.util.*;

final class PaperTargetChecks {
    private static int oneGold, twoGold;

    static void author(net.fries.zombies.game.ZombiesPlugin plugin, Player p) {
        var editor = plugin.matches().editor();
        var s = editor.require(p);
        for (var entry :
                List.of(
                        new String[] {"target_left", "-9.5", "friesnet:perk/extra_health", "17"},
                        new String[] {"target_right", "-7.5", "friesnet:perk/speed", "19"})) {
            editor.execute(p, new String[] {"add", "perk", entry[0]});
            editor.execute(p, new String[] {"set", "perk", entry[2]});
            editor.execute(p, new String[] {"set", "price", entry[3]});
            p.teleport(new Location(s.world, Double.parseDouble(entry[1]), 64, 6.5));
            editor.execute(p, new String[] {"point"});
            s.world.getBlockAt(p.getLocation()).setType(Material.GREEN_CONCRETE);
        }
        editor.execute(p, "add mystery_box door_overlap".split(" "));
        p.teleport(new Location(s.world, 7.4, 65, .5));
        editor.execute(p, new String[] {"point"});
        p.closeInventory();
    }

    static boolean handle(String step, net.fries.zombies.game.ZombiesPlugin plugin) {
        if (!step.startsWith("target-")) return false;
        var r = plugin.matches().current();
        var one = Bukkit.getPlayerExact("SurvivorOne");
        var two = Bukkit.getPlayerExact("SurvivorTwo");
        if (step.equals("target-start")) {
            one.teleport(new Location(r.world, 4.5, 64, .5));
            look(one, new Location(r.world, 6.5, 65.5, .5));
            int before = r.survivor(one.getUniqueId()).points();
            check(
                    r.targets.matches(one, "gate:workshop") && !r.mysteryBoxes.interact(one),
                    "overlapping box behind door cannot intercept door crosshair");
            check(
                    r.survivor(one.getUniqueId()).points() == before,
                    "target selection never charges");
            two.teleport(new Location(r.world, 8.8, 64, .5));
            look(two, new Location(r.world, 7.4, 65.8, .5));
            check(
                    r.targets.matches(two, "object:door_overlap"),
                    "reverse view chooses exposed box surface in overlapping volume");
            look(one, new Location(r.world, -10, 65, .5));
            check(
                    !r.interactions.interact(one)
                            && r.survivor(one.getUniqueId()).points() == before,
                    "looking away cannot buy a nearby door");
            one.teleport(new Location(r.world, -9.5, 64, 4.5));
            look(one, new Location(r.world, -9.5, 64.8, 6.5));
            var block = r.world.getBlockAt(-10, 65, 5);
            var data = block.getBlockData();
            block.setType(Material.STONE);
            r.targets.invalidate();
            check(r.targets.target(one) == null, "solid block occludes targeted station");
            block.setBlockData(data);
            r.targets.invalidate();
            one.teleport(new Location(r.world, -9.5, 64, 1.5));
            look(one, new Location(r.world, -9.5, 64.8, 6.5));
            check(r.targets.target(one) == null, "out-of-range target cannot purchase");
            for (var p : List.of(one, two)) {
                r.survivor(p.getUniqueId()).credit(1000);
                p.teleport(new Location(r.world, -8.5, 64, 4.5));
                p.getInventory().setHeldItemSlot(7);
            }
            oneGold = r.survivor(one.getUniqueId()).points();
            twoGold = r.survivor(two.getUniqueId()).points();
        } else if (step.equals("target-purchased")) {
            var a = r.survivor(one.getUniqueId());
            var b = r.survivor(two.getUniqueId());
            check(
                    a.points() == oneGold - 17 && a.perks().get(Perks.Type.JUGGERNOG) != null,
                    "first client buys only left crosshair station: points="
                            + a.points()
                            + " expected="
                            + (oneGold - 17)
                            + " round="
                            + r.waves.round()
                            + " target="
                            + r.targets.target(one));
            check(
                    b.points() == twoGold - 19 && b.perks().get(Perks.Type.SPEED) != null,
                    "second client buys only adjacent right station");
            check(
                    a.perks().owned().size() == 1 && b.perks().owned().size() == 1,
                    "adjacent native purchases do not stack other station effects");
            for (var p : List.of(one, two)) {
                r.survivor(p.getUniqueId()).perks().clear();
                r.perks.apply(p, false);
            }
        } else throw new IllegalArgumentException("Unknown target stage");
        return true;
    }

    private static void check(boolean condition, String text) {
        if (!condition) throw new AssertionError(text);
    }

    static void aim(MatchRun r, Player p, String id) {
        var target =
                InteractionTargeting.definitions(r.map).stream()
                        .filter(t -> t.id().equals(id))
                        .findFirst()
                        .orElseThrow();
        var b = target.box();
        var centre =
                new Location(
                        r.world,
                        (b.x1() + b.x2()) / 2,
                        (b.y1() + b.y2()) / 2,
                        (b.z1() + b.z2()) / 2);
        var eye = p.getEyeLocation();
        if (eye.getX() >= b.x1()
                        && eye.getX() <= b.x2()
                        && eye.getZ() >= b.z1()
                        && eye.getZ() <= b.z2()
                || eye.distanceSquared(centre) > 10) {
            double sign = centre.getZ() < 0 ? 1 : -1;
            p.teleport(new Location(r.world, centre.getX(), 64, centre.getZ() + sign * 2));
        }
        var at = p.getLocation();
        at.setDirection(centre.toVector().subtract(p.getEyeLocation().toVector()));
        p.teleport(at);
        r.targets.invalidate();
    }

    static boolean interact(MatchRun r, Player p) {
        var target =
                InteractionTargeting.definitions(r.map).stream()
                        .min(
                                Comparator.comparingDouble(
                                        t -> {
                                            var b = t.box();
                                            return p.getLocation()
                                                    .distanceSquared(
                                                            new Location(
                                                                    r.world,
                                                                    (b.x1() + b.x2()) / 2,
                                                                    64,
                                                                    (b.z1() + b.z2()) / 2));
                                        }))
                        .orElseThrow();
        aim(r, p, target.id());
        return r.interactions.interact(p);
    }

    static boolean mystery(MatchRun r, Player p) {
        var station =
                r.map.objects().stream()
                        .filter(o -> o.kind() == MapObject.Kind.MYSTERY_BOX)
                        .min(
                                Comparator.comparingDouble(
                                        o ->
                                                p.getLocation()
                                                        .distanceSquared(
                                                                new Location(
                                                                        r.world,
                                                                        o.point().x(),
                                                                        64,
                                                                        o.point().z()))))
                        .orElseThrow();
        aim(r, p, "object:" + station.id());
        return r.mysteryBoxes.interact(p);
    }

    static void look(Player p, Location target) {
        var at = p.getLocation();
        at.setDirection(target.toVector().subtract(p.getEyeLocation().toVector()));
        p.teleport(at);
    }
}
