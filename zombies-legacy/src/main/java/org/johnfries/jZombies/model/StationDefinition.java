package org.johnfries.jZombies.model;

import org.bukkit.Location;

public record StationDefinition(
        String id,
        StationType type,
        Location interactBlock,
        int price,
        String referenceId
) {
}
