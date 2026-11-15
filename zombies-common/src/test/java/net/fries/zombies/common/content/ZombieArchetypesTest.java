package net.fries.zombies.common.content;

import static org.junit.jupiter.api.Assertions.*;

import net.fries.zombies.common.NamespacedId;

import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

class ZombieArchetypesTest {
    private ZombieArchetypes rules() throws Exception {
        return ZombieArchetypes.load(
                Path.of("../zombies-game/src/main/resources/zombie-archetypes.yml"));
    }

    @Test
    void thresholdsAndLimitsHaveAnUnlimitedFallback() throws Exception {
        var rules = rules();
        var map = new NamespacedId("friesnet:map/training");
        var random = new Random(1);
        for (int i = 0; i < 1000; i++)
            assertEquals(
                    "friesnet:zombie/normal",
                    rules.choose(map, 1, Map.of(), Map.of(), random).id().value());
        var full = new HashMap<NamespacedId, Integer>();
        rules.types().keySet().forEach(id -> full.put(id, 128));
        assertNull(rules.choose(map, 30, full, Map.of(), random));
        full.put(new NamespacedId("friesnet:zombie/normal"), 0);
        for (int i = 0; i < 100; i++)
            assertEquals(
                    "friesnet:zombie/normal",
                    rules.choose(map, 30, full, Map.of(), random).id().value());
        var counts = new HashMap<NamespacedId, Integer>();
        counts.put(new NamespacedId("friesnet:zombie/elite"), 2);
        for (int i = 0; i < 1000; i++)
            assertNotEquals(
                    "friesnet:zombie/elite",
                    rules.choose(map, 30, Map.of(), counts, random).id().value());
    }

    @Test
    void curvesStayBoundedAndVariationRemainsControlled() throws Exception {
        var rules = rules();
        var random = new Random(9);
        var wave = new Gameplay.Wave(1, 1e6, 1e6, 1e6);
        for (var type : rules.types().values())
            for (int i = 0; i < 100; i++) {
                var stats = rules.stats(type, wave, random);
                assertEquals(rules.maxHealth(), stats.health());
                assertEquals(rules.maxSpeed(), stats.speed());
                assertEquals(rules.maxDamage(), stats.damage());
            }
        var normal = rules.types().get(new NamespacedId("friesnet:zombie/normal"));
        for (int i = 0; i < 100; i++) {
            var stats = rules.stats(normal, new Gameplay.Wave(1, 20, 3, .2), random);
            assertTrue(stats.health() >= 19.2 && stats.health() <= 20.8);
        }
    }

    @Test
    void invalidReferencesAndMissingFallbackAreRejected() throws Exception {
        String yaml =
                Files.readString(
                        Path.of("../zombies-game/src/main/resources/zombie-archetypes.yml"));
        for (var invalid :
                List.of(
                        yaml.replace("variation: 0.04", "variation: 0.9"),
                        yaml.replace(
                                "default-pool: friesnet:zombie_pool/default",
                                "default-pool: bad:missing"),
                        yaml.replace("max-health: 240", "max-health: .nan"),
                        yaml.replace("round-limit: 0", "round-limit: 1")))
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            ZombieArchetypes.parse(
                                    invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
