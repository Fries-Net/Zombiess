package net.fries.zombies.common.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import net.fries.zombies.common.content.MapContentMigration;

import org.junit.jupiter.api.Test;

import java.util.*;

class WindowStateTest {
    @Test
    void simultaneousAttackersAdvanceOncePerTickAndRemoveOneBoard() {
        var s = new WindowState(6, 3, 3);
        for (int tick = 0; tick < 2; tick++)
            for (int mob = 0; mob < 20; mob++) assertEquals(-1, s.attack(tick, true));
        assertEquals(0, s.attack(2, true));
        for (int mob = 0; mob < 20; mob++) assertEquals(-1, s.attack(2, true));
        assertEquals(5, s.intactCount());
        assertFalse(s.intact(0));
        assertEquals(-1, s.attack(3, true));
        assertEquals(-1, s.attack(4, true));
        assertEquals(1, s.attack(5, true));
        assertEquals(4, s.intactCount());
    }

    @Test
    void damageStagesAreProgressiveAndNoAttackMeansNoDamage() {
        var s = new WindowState(2, 10, 3);
        s.attack(0, true);
        float first = s.damageProgress();
        s.attack(1, false);
        assertEquals(first, s.damageProgress());
        s.attack(2, true);
        assertTrue(s.damageProgress() > first);
        assertEquals(0, s.attackingBoard());
    }

    @Test
    void rebuildingRestoresOnlyMissingBoardAndCannotDuplicateTransitions() {
        var s = new WindowState(2, 1, 3);
        s.attack(0, true);
        var p = UUID.randomUUID();
        assertEquals(-1, s.repair(1, p, true, true));
        assertEquals(-1, s.repair(2, p, true, true));
        assertEquals(0, s.repair(3, p, true, true));
        assertEquals(-1, s.repair(3, UUID.randomUUID(), true, true));
        assertEquals(-1, s.repair(4, p, true, true));
        assertEquals(2, s.intactCount());
    }

    @Test
    void invalidSpaceMovingDowningAndChangingRepairerResetProgress() {
        var s = new WindowState(2, 1, 3);
        s.attack(0, true);
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        s.repair(1, a, true, true);
        s.repair(2, a, true, false);
        assertEquals(0, s.repairProgress());
        s.repair(3, a, true, true);
        s.repair(4, a, false, true);
        assertEquals(0, s.repairProgress());
        s.repair(5, a, true, true);
        s.repair(6, b, true, true);
        assertEquals(1f / 3, s.repairProgress());
        assertEquals(-1, s.repair(7, b, true, true));
        assertEquals(0, s.repair(8, b, true, true));
    }

    @Test
    void allBoardsCanBeBrokenAndRebuiltAcrossRepeatedCycles() {
        var s = new WindowState(3, 1, 1);
        var p = UUID.randomUUID();
        for (int round = 0; round < 20; round++) {
            for (int n = 0; n < 3; n++) assertEquals(n, s.attack(round * 10 + n, true));
            assertEquals(0, s.intactCount());
            assertEquals(-1, s.attack(round * 10 + 3, true));
            for (int n = 0; n < 3; n++)
                assertEquals(n, s.repair(round * 10 + 4 + n, p, true, true));
            assertEquals(3, s.intactCount());
        }
    }

    @Test
    void defaultMigrationNeverOverwritesCustomGeometry() {
        byte[] old = "schema-version: 1\nprice: 750\n".getBytes(),
                replacement = "schema-version: 2\nprice: 750\n".getBytes();
        assertArrayEquals(replacement, MapContentMigration.upgradeDefault(old, old, replacement));
        byte[] custom = "schema-version: 1\nprice: 800\n".getBytes();
        assertArrayEquals(custom, MapContentMigration.upgradeDefault(custom, old, replacement));
        assertArrayEquals(
                replacement, MapContentMigration.upgradeDefault(replacement, old, replacement));
    }
}
