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
    void distinctCadencesConsumeExactAmmoUnderEveryTickSpam() throws Exception {
        for (var entry : java.util.Map.of("glock18", 30, "ak47", 20, "mac10", 10).entrySet()) {
            var weapon = weapon(entry.getKey());
            var control = new FireControl();
            int fired = 0;
            for (long tick = 0; tick < 60; tick++)
                if (control.attempt(weapon, tick, 1) == WeaponState.Shot.FIRED) fired++;
            assertEquals(60 / entry.getValue(), fired);
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
        assertEquals(WeaponState.Shot.COOLDOWN, c.attempt(ak, 29, 1));
        assertEquals(WeaponState.Shot.FIRED, c.attempt(ak, 30, 1));
        c.cancel();
        assertEquals(WeaponState.Shot.COOLDOWN, c.attempt(glock, 49, 1));
        assertEquals(WeaponState.Shot.FIRED, c.attempt(glock, 50, 1));
        assertTrue(glock.reload(50, 10));
        glock.tick(54);
        assertEquals(WeaponState.Shot.COOLDOWN, c.attempt(glock, 54, 1));
        assertEquals(WeaponState.Shot.FIRED, c.attempt(glock, 80, 1));
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
        assertEquals(4, shots);
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
        assertEquals(WeaponState.Shot.COOLDOWN, c.attempt(glock, 15, 2));
        assertEquals(WeaponState.Shot.FIRED, c.attempt(glock, 30, 2));
        assertEquals(WeaponState.Shot.FIRED, c.attempt(glock, 45, 2));
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
        assertEquals(1, awp.magazine());
        assertEquals(4, awp.definition().behaviour().pierceTargets());
        assertEquals(WeaponState.Shot.FIRED, awp.shoot(0));
        assertEquals(WeaponState.Shot.EMPTY, awp.shoot(50));
        assertTrue(awp.reload(50));
        assertEquals(WeaponState.Shot.RELOADING, awp.shoot(149));
        assertEquals(WeaponState.Shot.FIRED, awp.shoot(150));
        assertEquals(39, awp.reserve());
    }
}
