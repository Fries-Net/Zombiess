package net.fries.zombies.common.gameplay;

import java.util.*;

/** Independent per-window state. Concurrent attackers advance at most once per server tick. */
public final class WindowState {
    private final BitSet intact = new BitSet();
    private final int count, breakTicks, repairTicks;
    private int damage, repairBoard = -1, repairProgress;
    private long attackTick = -1, repairTick = -1;
    private UUID repairer;

    public WindowState(int boards, int breakTicks, int repairTicks) {
        if (boards < 1 || boards > 64 || breakTicks < 1 || repairTicks < 1)
            throw new IllegalArgumentException("Invalid window limits");
        count = boards;
        this.breakTicks = breakTicks;
        this.repairTicks = repairTicks;
        intact.set(0, count);
    }

    public int attack(long tick, boolean attackers) {
        if (!attackers || attackTick == tick) return -1;
        attackTick = tick;
        int board = intact.nextSetBit(0);
        if (board < 0) return -1;
        if (++damage < breakTicks) return -1;
        damage = 0;
        intact.clear(board);
        return board;
    }

    public int repair(long tick, UUID player, boolean valid, boolean spaceFree) {
        if (repairTick == tick) return -1;
        repairTick = tick;
        int missing = missing();
        if (!valid || !spaceFree || missing < 0 || player == null) {
            cancelRepair();
            return -1;
        }
        if (!player.equals(repairer) || missing != repairBoard) {
            repairer = player;
            repairBoard = missing;
            repairProgress = 0;
        }
        if (++repairProgress < repairTicks) return -1;
        intact.set(missing);
        cancelRepair();
        return missing;
    }

    public void cancelRepair() {
        repairer = null;
        repairBoard = -1;
        repairProgress = 0;
    }

    public int missing() {
        int board = intact.nextClearBit(0);
        return board < count ? board : -1;
    }

    public boolean intact(int board) {
        if (board < 0 || board >= count) throw new IllegalArgumentException("Unknown board");
        return intact.get(board);
    }

    public int intactCount() {
        return intact.cardinality();
    }

    public int attackingBoard() {
        return intact.nextSetBit(0);
    }

    public float damageProgress() {
        return (float) damage / breakTicks;
    }

    public float repairProgress() {
        return (float) repairProgress / repairTicks;
    }

    public UUID repairer() {
        return repairer;
    }
}
