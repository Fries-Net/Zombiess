package net.fries.zombies.common.gameplay;

/** Server-tick deadlines. No wall clocks, delayed closures or client cooldown packets. */
public final class WeaponTiming {
    private long readyAt;

    public boolean ready(long tick) {
        return tick >= readyAt;
    }

    public long remaining(long tick) {
        return Math.max(0, readyAt - tick);
    }

    public long readyAt() {
        return readyAt;
    }

    public void fired(long tick, int baseTicks, double rate) {
        readyAt = Math.addExact(tick, duration(baseTicks, rate));
    }

    public void retainUntil(long deadline) {
        readyAt = Math.max(readyAt, deadline);
    }

    public static int duration(int baseTicks, double multiplier) {
        if (baseTicks < 1 || !Double.isFinite(multiplier) || multiplier <= 0 || multiplier > 10)
            throw new IllegalArgumentException("Invalid weapon timing modifier");
        return Math.max(1, (int) Math.ceil(baseTicks / multiplier));
    }
}
