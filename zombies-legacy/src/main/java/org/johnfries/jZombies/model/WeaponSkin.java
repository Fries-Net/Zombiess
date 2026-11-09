package org.johnfries.jZombies.model;

import java.util.Set;

public record WeaponSkin(
        String id,
        String displayName,
        int customModelData,
        int price,
        Set<String> allowedWeapons
) {

    public boolean canApplyTo(String weaponId) {
        return allowedWeapons.contains(weaponId);
    }
}
