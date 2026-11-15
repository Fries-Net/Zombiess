package net.fries.zombies.common.content;

/** Presentation-only timing; reward choice and collection deadlines stay in MysteryRoll. */
public record MysteryPresentation(
        int activateTicks,
        int closeTicks,
        int fastestCycle,
        int slowestCycle,
        double height,
        double scale,
        boolean rareParticles,
        boolean itemModels) {
    public MysteryPresentation {
        if (activateTicks < 1
                || activateTicks > 100
                || closeTicks < 1
                || closeTicks > 100
                || fastestCycle < 1
                || slowestCycle < fastestCycle
                || slowestCycle > 40
                || !Double.isFinite(height + scale)
                || height < .5
                || height > 3
                || scale < .1
                || scale > 1) throw new IllegalArgumentException("Invalid mystery presentation");
    }

    public static MysteryPresentation defaults() {
        return new MysteryPresentation(8, 10, 2, 12, 1.25, .5, true, false);
    }

    public int activation(int openTicks) {
        return Math.min(activateTicks, Math.max(1, openTicks / 4));
    }

    public int interval(long elapsed, int openTicks) {
        if (elapsed >= openTicks) return slowestCycle;
        double progress =
                Math.clamp(
                        (elapsed - activation(openTicks))
                                / (double) Math.max(1, openTicks - activation(openTicks)),
                        0,
                        1);
        return fastestCycle + (int) Math.round((slowestCycle - fastestCycle) * progress * progress);
    }

    public double rise(long elapsed, int openTicks) {
        double progress = Math.clamp(elapsed / (double) activation(openTicks), 0, 1);
        return 1 - Math.pow(1 - progress, 3);
    }
}
