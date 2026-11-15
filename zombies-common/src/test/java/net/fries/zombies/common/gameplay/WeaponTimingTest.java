package net.fries.zombies.common.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import net.fries.zombies.common.NamespacedId;
import net.fries.zombies.common.content.*;

import org.junit.jupiter.api.Test;

import java.nio.file.*;

class WeaponTimingTest {
    private Gameplay content() throws Exception {
        return Gameplay.load(Path.of("../zombies-game/src/main/resources/gameplay.yml"));
    }

    private WeaponState weapon(String name) throws Exception {
        return new WeaponState(
                content().weapons().get(new NamespacedId("friesnet:weapon/" + name)));
    }

    @Test
    void fasterWeaponsDoNotPayMoreForTheSameEnemyHealth() throws Exception {
        var content = content();
        int baseline = goldForEnemy(content, "glock18", 180);
        assertTrue(goldForEnemy(content, "ak47", 180) <= baseline);
        assertTrue(goldForEnemy(content, "mac10", 180) <= baseline);
        for (String name : java.util.List.of("glock18", "ak47", "mac10", "revolver", "awp"))
            assertTrue(goldForEnemy(content, name, 18) <= 15, "bounded normal-enemy gold: " + name);
    }

    private int goldForEnemy(Gameplay content, String name, double health) {
        var survivor = new Survivor(java.util.UUID.randomUUID(), content);
        var ledger = new PointsLedger(content.scoring(), java.util.Map.of(survivor.id(), survivor));
        var enemy = java.util.UUID.randomUUID();
        ledger.register(enemy);
        var w = content.weapons().get(new NamespacedId("friesnet:weapon/" + name));
        long tick = 0;
        while (health > 0) {
            double after = Math.max(0, health - w.damage());
            ledger.damage(
                    new PointsLedger.Damage(
                            java.util.UUID.randomUUID(),
                            enemy,
                            survivor.id(),
                            tick,
                            PointsLedger.Kind.BULLET,
                            false,
                            health,
                            after),
                    w);
            health = after;
            tick += w.cooldownTicks();
        }
        return survivor.points() - content.startingPoints();
    }

    @Test
    void distinctCadencesConsumeExactAmmoUnderEveryTickSpam() throws Exception {
        for (var entry :
                java.util.Map.of("glock18", 8, "ak47", 4, "mac10", 2, "revolver", 15, "awp", 30)
                        .entrySet()) {
            var weapon = weapon(entry.getKey());
            var control = new FireControl();
            int fired = 0;
            for (long tick = 0; tick < 60; tick++)
                if (control.attempt(weapon, tick, 1) == WeaponState.Shot.FIRED) fired++;
            assertEquals(
                    Math.min(weapon.definition().magazine(), (59 / entry.getValue()) + 1), fired);
            assertEquals(weapon.definition().magazine() - fired, weapon.magazine());
        }
    }

    @Test
    void switchingAndReloadingNeverClearRecovery() throws Exception {
        var glock = weapon("glock18");
        var ak = weapon("ak47");
        var c = new FireControl();
        assertEquals(WeaponState.Shot.FIRED, c.attempt(glock, 0, 1));
        c.cancel();
        assertEquals(WeaponState.Shot.COOLDOWN, c.attempt(ak, 1, 1));
        assertEquals(WeaponState.Shot.COOLDOWN, c.attempt(ak, 7, 1));
        assertEquals(WeaponState.Shot.FIRED, c.attempt(ak, 8, 1));
        c.cancel();
        assertEquals(WeaponState.Shot.COOLDOWN, c.attempt(glock, 11, 1));
        assertEquals(WeaponState.Shot.FIRED, c.attempt(glock, 12, 1));
        assertTrue(glock.reload(12, 10));
        glock.tick(16);
        assertEquals(WeaponState.Shot.COOLDOWN, c.attempt(glock, 16, 1));
        assertEquals(WeaponState.Shot.FIRED, c.attempt(glock, 20, 1));
    }

    @Test
    void automaticHoldAndSemiAutomaticPressHaveIndependentRhythms() throws Exception {
        var glock = weapon("glock18");
        var c = new FireControl();
        assertEquals(WeaponState.Shot.FIRED, c.press(glock, 0, 1));
        for (int t = 1; t < 100; t++)
            assertNotEquals(WeaponState.Shot.FIRED, c.poll(glock, t, 1, true));
        c.release();
        assertEquals(WeaponState.Shot.FIRED, c.press(glock, 100, 1));
        var ak = weapon("ak47");
        c = new FireControl();
        assertEquals(WeaponState.Shot.FIRED, c.press(ak, 0, 1));
        int shots = 1;
        for (int t = 1; t < 61; t++) if (c.poll(ak, t, 1, true) == WeaponState.Shot.FIRED) shots++;
        assertEquals(16, shots);
        c.release();
        assertNotEquals(WeaponState.Shot.FIRED, c.poll(ak, 80, 1, false));
    }

    @Test
    void burstContinuesAfterReleaseButCannotBypassSwitchRecovery() throws Exception {
        var base = weapon("ak47").definition();
        var b = base.behaviour();
        var burst =
                new WeaponState(
                        new Gameplay.Weapon(
                                base.id(),
                                base.name(),
                                base.material(),
                                base.magazine(),
                                base.reserve(),
                                base.damage(),
                                base.headshotMultiplier(),
                                base.range(),
                                30,
                                base.reloadTicks(),
                                base.price(),
                                base.ammoPrice(),
                                new Gameplay.Behaviour(
                                        Gameplay.FireMode.BURST,
                                        3,
                                        2,
                                        b.sound(),
                                        b.pitch(),
                                        b.particle(),
                                        b.asset(),
                                        1,
                                        b.bodyGold(),
                                        b.headGold())));
        var c = new FireControl();
        assertEquals(WeaponState.Shot.FIRED, c.press(burst, 0, 1));
        c.release();
        assertEquals(WeaponState.Shot.FIRED, c.poll(burst, 2, 1, false));
        assertEquals(WeaponState.Shot.FIRED, c.poll(burst, 4, 1, false));
        assertNotEquals(WeaponState.Shot.FIRED, c.poll(burst, 6, 1, false));
        c.cancel();
        assertEquals(WeaponState.Shot.COOLDOWN, c.attempt(weapon("glock18"), 33, 1));
        assertEquals(WeaponState.Shot.FIRED, c.attempt(weapon("glock18"), 34, 1));
    }

    @Test
    void modifiersChangeFutureIntervalsWithoutShorteningPendingDeadlines() throws Exception {
        var glock = weapon("glock18");
        var c = new FireControl();
        c.attempt(glock, 0, 1);
        assertEquals(WeaponState.Shot.COOLDOWN, c.attempt(glock, 7, 2));
        assertEquals(WeaponState.Shot.FIRED, c.attempt(glock, 8, 2));
        assertEquals(WeaponState.Shot.FIRED, c.attempt(glock, 12, 2));
        assertEquals(1, WeaponTiming.duration(1, 10));
        assertThrows(IllegalArgumentException.class, () -> WeaponTiming.duration(10, 0));
    }

    @Test
    void migrationIsIdempotentPreservesCustomizedBalanceAndAddsContent() throws Exception {
        byte[] original = Files.readAllBytes(Path.of("src/test/resources/phase2-gameplay.yml")),
                defaults =
                        Files.readAllBytes(
                                Path.of("../zombies-game/src/main/resources/gameplay.yml"));
        byte[] migrated = GameplayMigration.upgrade(original, defaults);
        var yaml = ContentYaml.parse(migrated);
        assertEquals(3, yaml.integer("schema-version", 3, 3));
        assertEquals(5, yaml.nodes("weapons").size());
        assertArrayEquals(migrated, GameplayMigration.upgrade(migrated, defaults));
        var custom =
                new String(original, java.nio.charset.StandardCharsets.UTF_8)
                        .replace("cooldown-ticks: 4", "cooldown-ticks: 45");
        assertEquals(
                45,
                ContentYaml.parse(
                                GameplayMigration.upgrade(
                                        custom.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                        defaults))
                        .nodes("weapons")
                        .getFirst()
                        .integer("cooldown-ticks", 1, 400));
    }

    @Test
    void specialWeaponsHaveDistinctPresentationAndSingleShotReload() throws Exception {
        var all = content().weapons().values();
        assertEquals(5, all.stream().map(Gameplay.Weapon::material).distinct().count());
        assertEquals(5, all.stream().map(w -> w.behaviour().sound()).distinct().count());
        var revolver = weapon("revolver");
        assertEquals(6, revolver.magazine());
        assertTrue(
                revolver.definition().damage() * revolver.definition().headshotMultiplier() >= 36);
        var awp = weapon("awp");
        assertEquals(3, awp.magazine());
        assertEquals(4, awp.definition().behaviour().pierceTargets());
        assertEquals(WeaponState.Shot.FIRED, awp.shoot(0));
        assertEquals(WeaponState.Shot.FIRED, awp.shoot(30));
        assertEquals(WeaponState.Shot.FIRED, awp.shoot(60));
        assertEquals(WeaponState.Shot.EMPTY, awp.shoot(90));
        assertTrue(awp.reload(90));
        assertEquals(WeaponState.Shot.RELOADING, awp.shoot(159));
        assertEquals(WeaponState.Shot.FIRED, awp.shoot(160));
        assertEquals(45, awp.reserve());
    }
}
