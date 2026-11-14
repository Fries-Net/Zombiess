package net.fries.zombies.game.ui;

import net.fries.zombies.common.content.MapObject;
import net.fries.zombies.game.match.MatchRun;
import net.fries.zombies.game.world.MapWorlds;
import net.kyori.adventure.text.Component;

import org.bukkit.entity.*;

import java.util.*;

/** Owned native text displays driven by immutable editor definitions. */
public final class DynamicHolograms {
    private final MatchRun run;
    private final Map<MapObject, TextDisplay> displays = new LinkedHashMap<>();

    public DynamicHolograms(MatchRun run) {
        this.run = run;
        for (var object : run.map.objects())
            if (object.kind() == MapObject.Kind.HOLOGRAM) {
                displays.put(
                        object,
                        run.world.spawn(
                                MapWorlds.location(run.world, object.point()),
                                TextDisplay.class,
                                d -> {
                                    d.setBillboard(Display.Billboard.CENTER);
                                    d.setPersistent(false);
                                    d.setSeeThrough(false);
                                    d.setViewRange(.6f);
                                }));
            }
    }

    public void tick() {
        if (run.tick % 10 != 0) return;
        for (var entry : displays.entrySet()) {
            var object = entry.getKey();
            boolean active = object.rooms().stream().allMatch(run.interactions::roomUnlocked);
            String text = active ? object.property("text", "") : "";
            text =
                    text.replace("{round}", Integer.toString(run.waves.round()))
                            .replace("{remaining}", Integer.toString(run.waves.remaining()))
                            .replace(
                                    "{gold}",
                                    Long.toString(
                                            run.players.values().stream()
                                                    .mapToLong(s -> s.points())
                                                    .sum()));
            entry.getValue().text(Component.text(text));
        }
    }

    public void clear() {
        displays.values().forEach(Entity::remove);
        displays.clear();
    }
}
