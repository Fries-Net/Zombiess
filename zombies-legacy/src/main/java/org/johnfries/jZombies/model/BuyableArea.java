package org.johnfries.jZombies.model;

import org.bukkit.Location;

import java.util.List;

public record BuyableArea(
        String id,
        String displayName,
        int price,
        Location interactBlock,
        List<Location> openBlocks
) {
}
