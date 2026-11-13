package org.johnfries.jZombies.model;

public record VendingUpgrade(
        String id,
        String displayName,
        int price,
        VendingUpgradeType type,
        double value
) {
}
