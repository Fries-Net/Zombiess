package net.fries.zombies.common.content;

import java.io.IOException;
import java.nio.file.Path;

public record HudSettings(
        int headshotTicks,
        int hitTicks,
        int purchaseTicks,
        int powerupTicks,
        int refreshTicks,
        String headshot,
        String hit,
        String separator) {
    public static HudSettings load(Path path) throws IOException {
        var n = ContentYaml.read(path);
        n.keys(
                "headshot-ticks",
                "hit-ticks",
                "purchase-ticks",
                "powerup-ticks",
                "refresh-ticks",
                "headshot-text",
                "hit-text",
                "separator");
        String head = n.text("headshot-text"),
                hit = n.text("hit-text"),
                separator = n.text("separator");
        if (head.length() > 40 || hit.length() > 40 || separator.length() > 8)
            throw new IllegalArgumentException("HUD text too long");
        return new HudSettings(
                n.integer("headshot-ticks", 15, 25),
                n.integer("hit-ticks", 1, 25),
                n.integer("purchase-ticks", 10, 100),
                n.integer("powerup-ticks", 10, 100),
                n.integer("refresh-ticks", 1, 5),
                head,
                hit,
                separator);
    }
}
