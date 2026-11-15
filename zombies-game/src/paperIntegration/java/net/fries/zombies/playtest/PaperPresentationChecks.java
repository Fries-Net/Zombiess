package net.fries.zombies.playtest;

import net.fries.zombies.common.content.*;
import net.fries.zombies.game.ZombiesPlugin;
import net.fries.zombies.game.world.*;

import org.bukkit.*;
import org.bukkit.entity.*;

final class PaperPresentationChecks {
    static boolean handle(String step, ZombiesPlugin plugin) {
        var p = Bukkit.getPlayerExact("SurvivorOne");
        var editor = plugin.matches().editor();
        var r = plugin.matches().current();
        switch (step) {
            case "editor-box-preview" -> {
                var s = editor.require(p);
                String previousList=s.selectedList,previousId=s.selectedId;
                s.selectedList = "objects";
                s.selectedId = "training_box";
                editor.execute(p, new String[] {"boxpreview"});
                check(
                        s.world.getEntitiesByClass(ItemDisplay.class).size() == 1,
                        "unpaid editor renderer");
                s.selectedList=previousList;s.selectedId=previousId;
            }
            case "mystery-visual-cycle", "mystery-visual-ready" -> {
                var previews = r.world.getEntitiesByClass(ItemDisplay.class);
                check(previews.size() == 1, "one compact preview");
                var display = previews.iterator().next();
                check(
                        display.getItemDisplayTransform() == ItemDisplay.ItemDisplayTransform.FIXED
                                && display.getBillboard() == Display.Billboard.FIXED,
                        "fixed item transforms avoid billboard spinning");
                check(
                        display.getTransformation().getScale().x <= .501
                                && display.getTransformation().getScale().x > .05,
                        "bounded native item scale");
                if (step.endsWith("ready"))
                    check(
                            display.isGlowing()
                                    && display.getItemStack().getType() == Material.AMETHYST_SHARD,
                            "authoritative rare AWP revealed");
            }
            case "lab-stations" -> {
                check(
                        r.map.id().value().equals("friesnet:map/foundry_lab"),
                        "separate demonstration loaded");
                MapRoutes.validate(r.world, r.map);
                var s = r.survivor(p.getUniqueId());
                s.credit(100000);
                var jug =
                        r.map.objects().stream()
                                .filter(o -> o.id().equals("lab_juggernog"))
                                .findFirst()
                                .orElseThrow();
                aim(p, MapWorlds.location(r.world, jug.point()));
                r.perks.interact(p);
                check(
                        p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue() == 40,
                        "bundled Juggernog works");
                var gate = r.map.gates().getFirst();
                aim(p, MapWorlds.location(r.world, gate.point()).subtract(2, 1, 0));
                var view = new Location(r.world, 4.5, 64, .5);
                view.setDirection(
                        MapWorlds.location(r.world, gate.point())
                                .toVector()
                                .subtract(view.clone().add(0, p.getEyeHeight(), 0).toVector()));
                p.teleport(view);
                r.interactions.interact(p);
                check(
                        r.interactions.roomUnlocked("workshop"),
                        "demonstration door unlocks workshop");
                var pap =
                        r.map.objects().stream()
                                .filter(o -> o.kind() == MapObject.Kind.PACK_A_PUNCH)
                                .findFirst()
                                .orElseThrow();
                aim(p, MapWorlds.location(r.world, pap.point()));
                p.getInventory().setHeldItemSlot(0);
                r.packAPunch.interact(p);
                check(
                        r.combat.held(p).upgradeLevel() == 1,
                        "bundled Pack-a-Punch upgrades owned instance");
                var box =
                        r.map.objects().stream()
                                .filter(o -> o.kind() == MapObject.Kind.MYSTERY_BOX)
                                .findFirst()
                                .orElseThrow();
                aim(p, MapWorlds.location(r.world, box.point()));
                check(r.mysteryBoxes.interact(p), "bundled mystery station activates");
                check(
                        !r.world.getEntitiesByClass(ItemDisplay.class).isEmpty(),
                        "bundled box renders preview");
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private static void aim(Player p, Location station) {
        var view = station.clone().add(0, 0, -2);
        view.setY(64);
        view.setDirection(
                station.clone()
                        .add(0, 1, 0)
                        .toVector()
                        .subtract(view.clone().add(0, p.getEyeHeight(), 0).toVector()));
        p.teleport(view);
    }

    private static void check(boolean value, String text) {
        if (!value) throw new AssertionError(text);
    }
}
