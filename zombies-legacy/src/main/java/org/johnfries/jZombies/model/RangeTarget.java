package org.johnfries.jZombies.model;

import org.bukkit.Location;

public record RangeTarget(
        String id,
        Location headLocation,
        double radius,
        int points
) {
}
