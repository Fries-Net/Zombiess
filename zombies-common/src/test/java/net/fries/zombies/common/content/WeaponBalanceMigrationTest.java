package net.fries.zombies.common.content;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

class WeaponBalanceMigrationTest {
    private byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void preservesCustomPairsAndResumesInterruptedUpgradeFirstWrite() {
        var g =
                bytes(
                        "schema-version: 3\n"
                                + "weapons: [{id: 'test:ak', cooldown-ticks: 20}]\n"
                                + "custom: kept\n");
        var u = bytes("weapons: [{weapon: 'test:ak', value: 10}]\n");
        var ng = bytes("schema-version: 3\nweapons: [{id: 'test:ak', cooldown-ticks: 4}]\n");
        var nu = bytes("weapons: [{weapon: 'test:ak', value: 20}]\n");
        var result = WeaponBalanceMigration.upgrade(g, u, g, u, ng, nu);
        assertEquals(
                4,
                ContentYaml.parse(result.gameplay())
                        .nodes("weapons")
                        .getFirst()
                        .integer("cooldown-ticks", 1, 400));
        assertEquals("kept", ContentYaml.parse(result.gameplay()).text("custom"));
        assertArrayEquals(
                result.gameplay(),
                WeaponBalanceMigration.upgrade(g, result.upgrades(), g, u, ng, nu).gameplay());
        var customized =
                bytes(new String(u, StandardCharsets.UTF_8).replace("value: 10", "value: 13"));
        assertArrayEquals(
                g, WeaponBalanceMigration.upgrade(g, customized, g, u, ng, nu).gameplay());
        assertArrayEquals(
                customized, WeaponBalanceMigration.upgrade(g, customized, g, u, ng, nu).upgrades());
        assertArrayEquals(
                result.gameplay(),
                WeaponBalanceMigration.upgrade(result.gameplay(), result.upgrades(), g, u, ng, nu)
                        .gameplay());
    }
}
