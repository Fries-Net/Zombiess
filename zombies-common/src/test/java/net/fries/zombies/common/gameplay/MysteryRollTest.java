package net.fries.zombies.common.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import net.fries.zombies.common.NamespacedId;
import net.fries.zombies.common.content.MysteryPools;

import org.junit.jupiter.api.Test;

import java.util.UUID;

class MysteryRollTest {
    private final UUID owner = UUID.randomUUID();

    private MysteryRoll roll() {
        return new MysteryRoll(
                owner,
                new MysteryPools.Reward(new NamespacedId("friesnet:weapon/awp"), 1, true),
                950,
                60,
                360);
    }

    @Test
    void claimsAreOwnedTimedAndIdempotent() {
        var r = roll();
        assertFalse(r.claim(owner, 59));
        assertFalse(r.claim(UUID.randomUUID(), 60));
        assertTrue(r.claim(owner, 60));
        assertFalse(r.claim(owner, 61));
        assertEquals(0, r.refund());
    }

    @Test
    void cancellationAndExpiryCannotDuplicateOrLoseRefund() {
        var r = roll();
        assertTrue(r.expired(360));
        assertFalse(r.claim(owner, 360));
        assertEquals(950, r.refund());
        assertEquals(0, r.refund());
        assertFalse(r.claim(owner, 100));
    }
}
