package net.fries.zombies.playtest;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.SwingAnimation;
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation;

import net.fries.zombies.common.NamespacedId;
import net.fries.zombies.common.gameplay.WeaponState;
import net.fries.zombies.game.ZombiesPlugin;

import org.bukkit.*;
import org.bukkit.entity.Player;

final class PaperGunChecks {
    private static double baseline;
    private static long beforeShots;

    private static void prepare(ZombiesPlugin plugin, Player p) {
        var r = plugin.matches().current();
        var s = r.survivor(p.getUniqueId());
        var speed = r.perkRules.require("friesnet:perk/speed");
        if (s.perks().get(net.fries.zombies.common.content.Perks.Type.SPEED) == null)
            check(
                    s.perks().purchase(s, r.perkRules, speed, speed, 0, 1, false, r.tick),
                    "movement perk fixture");
        var ak = new NamespacedId("friesnet:weapon/ak47");
        var w = new WeaponState(r.content.weapons().get(ak));
        s.weapons().put(ak, w);
        r.combat.equip(p);
        p.getInventory().setHeldItemSlot(1);
        r.combat.switched(p);
        r.perks.apply(p, false);
        p.teleport(new Location(r.world, -10.5, 64, -6.5));
        beforeShots = w.shotsFired();
        var item = p.getInventory().getItemInMainHand();
        var effects = item.getData(DataComponentTypes.USE_EFFECTS);
        check(
                effects != null
                        && effects.canSprint()
                        && effects.speedMultiplier() == 1
                        && !effects.interactVibrations(),
                "native no-slow/sprint component");
        check(
                item.getData(DataComponentTypes.CONSUMABLE).animation() == ItemUseAnimation.NONE
                        && item.getData(DataComponentTypes.SWING_ANIMATION).type()
                                == SwingAnimation.Animation.NONE,
                "no eating, drinking or swing animation");
        check(Math.abs(p.getWalkSpeed() - .26) < .001, "Speed perk remains effective");
    }

    static boolean handle(String step, ZombiesPlugin plugin) {
        if (!step.startsWith("gun-")) return false;
        var r = plugin.matches().current();
        var p = Bukkit.getPlayerExact("SurvivorOne");
        switch (step) {
            case "gun-move-start", "gun-move-hold-start" -> prepare(plugin, p);
            case "gun-move-baseline" -> {
                baseline = p.getZ() + 6.5;
                check(
                        p.isSprinting() && baseline > 3,
                        "native sprint movement baseline: " + baseline);
            }
            case "gun-move-held" -> {
                double distance = p.getZ() + 6.5;
                check(
                        p.isSprinting() && p.hasActiveItem(),
                        "simultaneous native sprint and held use");
                check(
                        distance >= baseline * .85,
                        "firing movement retains baseline speed: " + baseline + " vs " + distance);
                check(
                        r.combat.held(p).shotsFired() - beforeShots >= 4,
                        "sustained automatic fire during movement");
            }
            case "gun-move-finish" -> {
                r.combat.release(p);
                r.survivor(p.getUniqueId()).perks().clear();
                r.perks.apply(p, false);
                r.survivor(p.getUniqueId())
                        .weapons()
                        .remove(new NamespacedId("friesnet:weapon/ak47"));
                r.combat.equip(p);
                p.teleport(new Location(r.world, -4.5, 64, .5));
            }
            case "gun-hud-start" -> {
                r.hud.hit(p, true);
                r.hud.hit(p, true);
                r.hud.hit(p, false);
                r.combat.held(p).shoot(r.tick);
                r.combat.reload(p);
            }
            case "gun-hud-visible" -> {
                var line = r.hud.lastLine(p.getUniqueId());
                check(
                        line.contains("HEADSHOT x2") && line.contains("Reloading"),
                        "headshots remain visible alongside reload: " + line);
            }
            case "gun-hud-expired" ->
                    check(
                            !r.hud.lastLine(p.getUniqueId()).contains("HEADSHOT"),
                            "notice expires on match ticks");
            default -> throw new IllegalArgumentException("Unknown gun stage");
        }
        return true;
    }

    private static void check(boolean condition, String text) {
        if (!condition) throw new AssertionError(text);
    }
}
