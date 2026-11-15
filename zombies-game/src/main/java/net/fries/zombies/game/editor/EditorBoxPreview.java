package net.fries.zombies.game.editor;

import net.fries.zombies.common.content.*;
import net.fries.zombies.game.world.*;

import org.bukkit.Location;

/** One bounded, unpaid visual demo in the author's isolated world. */
final class EditorBoxPreview {
    private final MysteryVisuals visual;
    private final ContentCatalog catalog;
    private final MysteryPools.Pool pool;
    private final long started;
    private long next;
    private int index;
    private boolean revealed;

    EditorBoxPreview(EditorSession session, ContentCatalog catalog, long tick) {
        var map = session.draft.validate(catalog.gameplay());
        var object =
                map.objects().stream()
                        .filter(
                                o ->
                                        o.id().equals(session.selectedId)
                                                && o.kind() == MapObject.Kind.MYSTERY_BOX)
                        .findFirst()
                        .orElseThrow(
                                () -> new IllegalArgumentException("Select a mystery box first"));
        this.catalog = catalog;
        pool = catalog.mysteryPools().require(object.property("box", "friesnet:box/default"));
        started = tick;
        next = tick;
        visual =
                new MysteryVisuals(
                        MapWorlds.location(session.world, object.point()),
                        new Location(
                                session.world,
                                object.region().x1(),
                                object.region().y1(),
                                object.region().z1()),
                        catalog.mysteryPools().presentation(),
                        object.integer("preview-yaw", 0, -180, 180),
                        tick);
        visual.activate();
    }

    boolean tick(long tick) {
        long elapsed = tick - started;
        var p = catalog.mysteryPools().presentation();
        if (elapsed >= pool.openTicks()) {
            if (!revealed) {
                var reward = pool.rewards().getLast();
                visual.item(
                        MysteryVisuals.preview(
                                catalog.gameplay().weapons().get(reward.weapon()), p.itemModels()));
                visual.reveal(reward.rare());
                revealed = true;
            }
            if (elapsed >= pool.openTicks() + 60) visual.close(tick);
        } else if (elapsed >= p.activation(pool.openTicks()) && tick >= next) {
            var reward = pool.rewards().get(index++ % pool.rewards().size());
            visual.item(
                    MysteryVisuals.preview(
                            catalog.gameplay().weapons().get(reward.weapon()), p.itemModels()));
            visual.cycle(elapsed / (double) pool.openTicks());
            next = tick + p.interval(elapsed, pool.openTicks());
        }
        return visual.tick(tick, pool.openTicks());
    }

    void remove() {
        visual.remove();
    }
}
