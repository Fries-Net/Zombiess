package net.fries.zombies.common.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import net.fries.zombies.common.NamespacedId;
import net.fries.zombies.common.content.*;

import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

class WeaponUpgradeTest {
    @org.junit.jupiter.api.io.TempDir Path directory;

    private Gameplay content() throws Exception {
        var file = directory.resolve("gameplay.yml");
        Files.write(file, getClass().getResourceAsStream("/phase2-gameplay.yml").readAllBytes());
        return Gameplay.load(file);
    }

    private WeaponUpgrades.Tier tier(Gameplay.Weapon w, int cost) {
        return new WeaponUpgrades.Tier(
                cost,
                "Upgraded",
                w.damage() * 2,
                w.magazine() + 1,
                w.reserve() + 50,
                w.cooldownTicks(),
                w.reloadTicks(),
                new NamespacedId("test:weapon/upgraded"),
                new NamespacedId("minecraft:block.anvil.use"),
                new NamespacedId("minecraft:block.anvil.use"));
    }

    @Test
    void upgradePreservesIdentityAmmunitionAndShotRecovery() throws Exception {
        var c = content();
        var s = new Survivor(UUID.randomUUID(), c);
        var w = s.weapons().get(c.starter());
        var id = w.instanceId();
        w.shoot(0);
        int ammo = w.magazine(), reserve = w.reserve();
        long deadline = w.readyAt();
        var r =
                new WeaponUpgrades(
                        Map.of(
                                c.starter(),
                                List.of(tier(w.definition(), 10), tier(w.definition(), 20))));
        int gold = s.points();
        assertTrue(w.upgrade(s, r, 0, 10, 1));
        assertEquals(id, w.instanceId());
        assertEquals(ammo, w.magazine());
        assertEquals(reserve, w.reserve());
        assertEquals(deadline, w.readyAt());
        assertEquals(1, w.upgradeLevel());
        assertEquals(gold - 10, s.points());
        assertEquals(WeaponState.Shot.COOLDOWN, w.shoot(1));
        assertFalse(w.upgrade(s, r, 0, 10, 2));
        assertEquals(gold - 10, s.points());
        assertTrue(w.upgrade(s, r, 1, 20, 20));
        assertFalse(w.upgrade(s, r, 2, 20, 40));
        assertEquals(gold - 30, s.points());
    }

    @Test
    void reloadLifeOwnershipAndFundsAreCheckedBeforeCharging() throws Exception {
        var c = content();
        var s = new Survivor(UUID.randomUUID(), c);
        var w = s.weapons().get(c.starter());
        var r = new WeaponUpgrades(Map.of(c.starter(), List.of(tier(w.definition(), 10))));
        int gold = s.points();
        w.shoot(0);
        w.reload(1);
        assertFalse(w.upgrade(s, r, 0, 10, 2));
        w.cancelReload();
        assertFalse(w.upgrade(s, r, 0, gold + 1, 3));
        var foreign = new WeaponState(w.definition());
        assertFalse(foreign.upgrade(s, r, 0, 0, 4));
        s.down(5, 100);
        assertFalse(w.upgrade(s, r, 0, 0, 6));
        assertEquals(gold, s.points());
    }

    @Test
    void parserRejectsUnknownWeaponsAndStatRegressions() throws Exception {
        var c = content();
        var w = c.weapons().get(c.starter());
        String yaml =
                "schema-version: 1\nweapons:\n  - weapon: "
                        + w.id().value()
                        + "\n"
                        + "    tiers:\n"
                        + "      - {cost: 10, name: Upgraded, damage: 1000, magazine: 200, reserve:"
                        + " 2000, cooldown-ticks: 1, reload-ticks: 1, asset:"
                        + " 'test:weapon/upgraded', fire-sound: 'minecraft:block.anvil.use',"
                        + " upgrade-sound: 'minecraft:block.anvil.use'}\n";
        assertEquals(
                1,
                WeaponUpgrades.parse(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8), c)
                        .weapons()
                        .size());
        for (var bad :
                List.of(
                        yaml.replace(w.id().value(), "test:weapon/unknown"),
                        yaml.replace("damage: 1000", "damage: 0.1"),
                        yaml.replace("magazine: 200", "magazine: 0"),
                        yaml.replace("cost: 10", "cost: -1")))
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            WeaponUpgrades.parse(
                                    bad.getBytes(java.nio.charset.StandardCharsets.UTF_8), c));
    }
}
