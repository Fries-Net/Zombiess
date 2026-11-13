package net.fries.zombies.common.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import net.fries.zombies.common.NamespacedId;
import net.fries.zombies.common.content.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.UUID;

class GameplayTest {
    @TempDir Path directory;

    private Gameplay content() throws Exception {
        return Gameplay.load(Path.of("src/test/resources/phase2-gameplay.yml"));
    }

    @Test
    void glockAmmoCooldownAndReloadConserveRounds() throws Exception {
        var w =
                new WeaponState(
                        content().weapons().get(new NamespacedId("friesnet:weapon/glock18")));
        assertEquals(WeaponState.Shot.FIRED, w.shoot(0));
        assertEquals(WeaponState.Shot.COOLDOWN, w.shoot(1));
        assertEquals(19, w.magazine());
        assertTrue(w.reload(4));
        assertFalse(w.reload(5));
        assertEquals(WeaponState.Shot.RELOADING, w.shoot(35));
        w.tick(36);
        assertEquals(20, w.magazine());
        assertEquals(159, w.reserve());
        assertFalse(w.reload(36));
    }

    @Test
    void akEmptyMagazineCannotFireAndSwitchCancelsReload() throws Exception {
        var w = new WeaponState(content().weapons().get(new NamespacedId("friesnet:weapon/ak47")));
        for (int n = 0; n < 30; n++) assertEquals(WeaponState.Shot.FIRED, w.shoot(n * 2));
        assertEquals(WeaponState.Shot.EMPTY, w.shoot(60));
        assertTrue(w.reload(60));
        w.cancelReload();
        w.tick(1000);
        assertEquals(0, w.magazine());
        assertEquals(240, w.reserve());
        assertTrue(w.reload(1000));
        w.tick(1048);
        assertEquals(30, w.magazine());
        assertEquals(210, w.reserve());
    }

    @Test
    void reserveCannotCreateAmmunitionThroughRepeatedReloads() throws Exception {
        var w = new WeaponState(content().weapons().get(content().starter()));
        long tick = 0;
        for (int magazine = 0; magazine < 9; magazine++) {
            for (int shot = 0; shot < 20; shot++) {
                assertEquals(WeaponState.Shot.FIRED, w.shoot(tick));
                tick += 4;
            }
            if (magazine < 8) {
                assertTrue(w.reload(tick));
                tick += 32;
                w.tick(tick);
            }
        }
        assertEquals(0, w.magazine());
        assertEquals(0, w.reserve());
        assertFalse(w.reload(tick));
        assertEquals(WeaponState.Shot.EMPTY, w.shoot(tick));
        assertTrue(w.refill());
        assertFalse(w.refill());
        assertNotEquals(w.instanceId(), new WeaponState(w.definition()).instanceId());
    }

    @Test
    void scalingIsDeterministicWithFiniteVictoryAndMonotonicDifficulty() throws Exception {
        var waves = content().waves();
        var one = waves.at(1, 1);
        var four = waves.at(1, 4);
        var later = waves.at(30, 4);
        assertEquals(5, one.count());
        assertEquals(15, four.count());
        assertTrue(later.health() > four.health());
        assertTrue(later.damage() > four.damage());
        assertEquals(four, waves.at(1, 4));
        assertThrows(IllegalArgumentException.class, () -> waves.at(31, 1));
        assertThrows(IllegalArgumentException.class, () -> waves.at(1, 0));
    }

    @Test
    void downReviveBleedoutAndRewardsStaySeparateFromMatchPoints() throws Exception {
        var c = content();
        var a = new Survivor(UUID.randomUUID(), c);
        var b = new Survivor(UUID.randomUUID(), c);
        assertFalse(a.spend(501));
        assertEquals(500, a.points());
        assertThrows(IllegalArgumentException.class, () -> a.spend(-1));
        a.down(0, 500);
        assertFalse(a.bleed(499));
        assertFalse(a.revive(b));
        assertTrue(b.revive(a));
        assertEquals(Survivor.Life.ALIVE, a.life());
        a.down(500, 500);
        assertTrue(a.bleed(1000));
        a.respawn();
        assertEquals(Survivor.Life.ALIVE, a.life());
        a.kill(true, c);
        a.round(c);
        assertEquals(850, a.points());
        assertEquals(1, a.result(c, false).tokens());
        assertEquals(1, a.result(c, false).headshots());
        assertEquals(1, b.result(c, false).revives());
        a.leave();
        a.respawn();
        assertEquals(Survivor.Life.LEFT, a.life());
        assertEquals(1, a.result(c, true).tokens());
    }

    @Test
    void contentRejectsUnknownKeysAliasesAndNonfiniteDamage() throws Exception {
        String source = Files.readString(Path.of("src/test/resources/phase2-gameplay.yml"));
        Path file = directory.resolve("gameplay.yml");
        for (String invalid :
                new String[] {
                    source + "\nextra: true\n",
                    source + "\nextra: &alias [1]\nalias: *alias\n",
                    source.replace("damage: 6.0", "damage: .nan"),
                    source.replace("magazine: 20", "magazine: -1"),
                    source.replace(
                            "starter: friesnet:weapon/glock18", "starter: friesnet:weapon/missing"),
                    source.replace("schema-version: 1", "schema-version: 1\nschema-version: 1")
                }) {
            Files.writeString(file, invalid);
            assertThrows(IllegalArgumentException.class, () -> Gameplay.load(file));
        }
    }

    @Test
    void mapVersionPinsContentAndRejectsInvalidReferencesAndBudgets() throws Exception {
        var c = content();
        Path source = Path.of("../zombies-game/src/main/resources/maps/training.yml");
        var original = MapTemplate.load(source, c);
        Path file = directory.resolve("training.yml");
        String text = Files.readString(source);
        Files.writeString(file, text.replace("price: 750", "price: 800"));
        assertNotEquals(original.version(), MapTemplate.load(file, c).version());
        for (String invalid :
                new String[] {
                    text.replace("gate: workshop", "gate: missing"),
                    text.replace("weapon: friesnet:weapon/ak47", "weapon: friesnet:weapon/unknown"),
                    text.replace("x2: 16, y2: 72, z2: 16", "x2: 128, y2: 255, z2: 128"),
                    text.replace("x: -4.5, y: 64", "x: -100, y: 64")
                }) {
            Files.writeString(file, invalid);
            assertThrows(IllegalArgumentException.class, () -> MapTemplate.load(file, c));
        }
    }
}
