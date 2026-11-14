package net.fries.zombies.common.content;

import static org.junit.jupiter.api.Assertions.*;

import net.fries.zombies.common.NamespacedId;

import org.junit.jupiter.api.Test;

import java.util.*;

class MysteryPoolsTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    private final NamespacedId common = new NamespacedId("friesnet:weapon/glock18"),
            rare = new NamespacedId("friesnet:weapon/awp"),
            id = new NamespacedId("friesnet:box/default");

    @Test
    void ticketsMatchExactWeightsIncludingRareBoundary() {
        var pool =
                new MysteryPools.Pool(
                        id,
                        950,
                        60,
                        300,
                        .2,
                        List.of(
                                new MysteryPools.Reward(common, 99, false),
                                new MysteryPools.Reward(rare, 1, true)));
        assertEquals(100, pool.totalWeight());
        for (int i = 0; i < 99; i++) assertEquals(common, pool.choose(i).weapon());
        assertEquals(rare, pool.choose(99).weapon());
        assertTrue(pool.choose(99).rare());
        assertThrows(IllegalArgumentException.class, () -> pool.choose(-1));
        assertThrows(IllegalArgumentException.class, () -> pool.choose(100));
        assertThrows(UnsupportedOperationException.class, () -> pool.rewards().clear());
    }

    @Test
    void parserRejectsUnknownWeaponsAndDuplicatePools() throws Exception {
        var path = directory.resolve("gameplay.yml");
        java.nio.file.Files.write(
                path, getClass().getResourceAsStream("/phase2-gameplay.yml").readAllBytes());
        var gameplay = Gameplay.load(path);
        String entry =
                """
                  - id: friesnet:box/default
                    price: 950
                    open-ticks: 60
                    claim-ticks: 300
                    relocate-chance: 0.2
                    rewards:
                      - {weapon: 'friesnet:weapon/glock18', weight: 1, rare: false}
                """;
        String yaml = "schema-version: 1\npools:\n" + entry;
        assertEquals(
                950,
                MysteryPools.parse(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8), gameplay)
                        .require("friesnet:box/default")
                        .price());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        MysteryPools.parse(
                                (yaml + entry).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                gameplay));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        MysteryPools.parse(
                                yaml.replace("glock18", "missing")
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                gameplay));
    }

    @Test
    void rejectsInvalidPoolsAndUnboundedWeights() {
        assertThrows(
                IllegalArgumentException.class, () -> new MysteryPools.Reward(common, 0, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new MysteryPools.Reward(common, 100001, false));
        var reward = new MysteryPools.Reward(common, 1, false);
        assertThrows(
                IllegalArgumentException.class,
                () -> new MysteryPools.Pool(id, 950, 60, 300, Double.NaN, List.of(reward)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new MysteryPools.Pool(id, 950, 60, 300, 0, List.of(reward, reward)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new MysteryPools.Pool(id, 950, 60, 300, 0, List.of()));
    }
}
