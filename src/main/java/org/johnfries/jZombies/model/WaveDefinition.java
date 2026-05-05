package org.johnfries.jZombies.model;

import java.util.List;

public record WaveDefinition(
        int number,
        List<MobSpawnDefinition> mobs,
        int skillPointReward
) {
}
