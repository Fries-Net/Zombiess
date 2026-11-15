package net.fries.zombies.common.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import net.fries.zombies.common.content.*;

import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

class ClassicPowerUpsTest {
    @Test
    void powerupEliminationsNeverAddLegacyFixedKillGold(
            @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        var path = directory.resolve("gameplay.yml");
        Files.writeString(
                path,
                Files.readString(Path.of("../zombies-game/src/main/resources/gameplay.yml"))
                        .replace("fixed-rewards-enabled: false", "fixed-rewards-enabled: true"));
        var c = Gameplay.load(path);
        var s = new Survivor(UUID.randomUUID(), c);
        int before = s.points();
        s.kill(false, c, false);
        assertEquals(1, s.kills());
        assertEquals(before, s.points());
        s.kill(false, c);
        assertEquals(before + c.killPoints(), s.points());
    }

    @Test
    void stackingCapsAndRefreshUseMatchTicks() throws Exception {
        var rules = PowerUps.load(Path.of("../zombies-game/src/main/resources/power-ups.yml"));
        var d = rules.definitions().get(PowerUps.Type.INSTA_KILL);
        assertEquals(400, d.expiry(0, 0));
        assertEquals(500, d.expiry(400, 100));
        var extend =
                new PowerUps.Definition(
                        d.type(),
                        d.name(),
                        d.material(),
                        true,
                        1,
                        1,
                        2,
                        400,
                        800,
                        600,
                        PowerUps.Stacking.EXTEND);
        assertEquals(900, extend.expiry(800, 100));
        var ignore =
                new PowerUps.Definition(
                        d.type(),
                        d.name(),
                        d.material(),
                        true,
                        1,
                        1,
                        2,
                        400,
                        800,
                        600,
                        PowerUps.Stacking.IGNORE);
        assertEquals(400, ignore.expiry(400, 100));
    }

    @Test
    void maxAmmoPreservesIdentityAndPendingShotRecovery() throws Exception {
        var c = Gameplay.load(Path.of("../zombies-game/src/main/resources/gameplay.yml"));
        var s = new Survivor(UUID.randomUUID(), c);
        var w = s.weapons().get(c.starter());
        var id = w.instanceId();
        w.shoot(0);
        long deadline = w.readyAt();
        w.reload(1);
        assertTrue(w.maxAmmo(true));
        assertEquals(id, w.instanceId());
        assertEquals(deadline, w.readyAt());
        assertFalse(w.reloading());
        assertEquals(w.definition().magazine(), w.magazine());
        assertFalse(w.maxAmmo(true));
    }

    @Test
    void nukeDamageHasNoHitLethalOrAssistBonusAndRewardsDeduplicate() throws Exception {
        var c = Gameplay.load(Path.of("../zombies-game/src/main/resources/gameplay.yml"));
        var a = new Survivor(UUID.randomUUID(), c);
        var b = new Survivor(UUID.randomUUID(), c);
        var policy = new PointsPolicy(false, 10, 5, 0, 3, 4, 600, 100, 300, 600, 0);
        var ledger = new PointsLedger(policy, Map.of(a.id(), a, b.id(), b));
        var enemy = UUID.randomUUID();
        ledger.register(enemy);
        ledger.damage(
                new PointsLedger.Damage(
                        UUID.randomUUID(),
                        enemy,
                        a.id(),
                        1,
                        PointsLedger.Kind.BULLET,
                        false,
                        30,
                        24),
                c.weapons().get(c.starter()));
        int old = a.points();
        assertEquals(
                0,
                ledger.damage(
                        new PointsLedger.Damage(
                                UUID.randomUUID(),
                                enemy,
                                b.id(),
                                2,
                                PointsLedger.Kind.POWER_UP,
                                false,
                                24,
                                0),
                        c.weapons().get(c.starter())));
        assertEquals(old, a.points());
        assertEquals(c.startingPoints(), b.points());
        assertEquals(400, ledger.powerup(a.id(), "pickup", 400, 3));
        assertEquals(0, ledger.powerup(a.id(), "pickup", 400, 4));
    }
}
