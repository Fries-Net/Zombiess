package net.fries.zombies.common.gameplay;

/** Immutable difficulty-specific gold policy. Permanent rewards never enter this policy. */
public record PointsPolicy(
        boolean fixedRewards,
        int meleeGold,
        int explosiveGold,
        int damageOverTimeGold,
        int lethalBonus,
        int assistGold,
        int assistWindowTicks,
        int objectiveGold,
        int targetGoldCap,
        int doubleGoldTicks,
        double doubleGoldDropChance) {
    public PointsPolicy {
        for (int value :
                new int[] {
                    meleeGold,
                    explosiveGold,
                    damageOverTimeGold,
                    lethalBonus,
                    assistGold,
                    objectiveGold
                })
            if (value < 0 || value > 10000)
                throw new IllegalArgumentException("Invalid gold reward");
        if (assistWindowTicks < 1
                || targetGoldCap < 1
                || targetGoldCap > 100000
                || doubleGoldTicks < 1
                || doubleGoldTicks > 72000
                || !Double.isFinite(doubleGoldDropChance)
                || doubleGoldDropChance < 0
                || doubleGoldDropChance > 1)
            throw new IllegalArgumentException("Invalid gold policy bounds");
    }

    public static PointsPolicy legacy() {
        return new PointsPolicy(true, 0, 0, 0, 0, 0, 600, 0, 300, 600, 0);
    }
}
