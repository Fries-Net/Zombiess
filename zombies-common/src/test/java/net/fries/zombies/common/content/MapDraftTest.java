package net.fries.zombies.common.content;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class MapDraftTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;

    private MapDraft draft() throws Exception {
        return new MapDraft(getClass().getResourceAsStream("/phase2-map.yml").readAllBytes());
    }

    @Test
    void legacyStationsAndSpawnsMigrateWithoutLosingReferences() throws Exception {
        var d = draft();
        var file = directory.resolve("gameplay.yml");
        java.nio.file.Files.write(
                file, getClass().getResourceAsStream("/phase2-gameplay.yml").readAllBytes());
        var map = d.validate(Gameplay.load(file));
        assertTrue(map.shops().isEmpty());
        assertTrue(map.zombieSpawns().isEmpty());
        assertEquals(2, map.wallShops().size());
        assertTrue(map.objects().stream().anyMatch(o -> o.kind() == MapObject.Kind.ZOMBIE_REGION));
        assertArrayEquals(d.bytes(), new MapDraft(d.bytes()).bytes());
    }

    @Test
    void migrationUndoRedoAndPublishedIsolation() throws Exception {
        var d = draft();
        var before = d.bytes();
        assertEquals(3, ContentYaml.parse(before).integer("schema-version", 3, 3));
        d.change(root -> root.put("name", "Edited"));
        assertEquals("Edited", ContentYaml.parse(d.bytes()).text("name"));
        assertTrue(d.undo());
        assertArrayEquals(before, d.bytes());
        assertTrue(d.redo());
        d.change(root -> root.put("name", "Second"));
        assertFalse(d.redo());
    }

    @Test
    void failedMutationRollsBackAndObjectsAreImmutable() throws Exception {
        var d = draft();
        byte[] before = d.bytes();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        d.change(
                                root -> {
                                    root.put("name", "bad");
                                    throw new IllegalArgumentException();
                                }));
        assertArrayEquals(before, d.bytes());
        var o =
                new MapObject(
                        "test",
                        MapObject.Kind.HOLOGRAM,
                        new MapTemplate.Point(0, 64, 0),
                        new MapTemplate.Box(0, 64, 0, 0, 64, 0),
                        Set.of("spawn"),
                        new net.fries.zombies.common.NamespacedId("friesnet:hologram/test"),
                        Map.of("text", "Gold: {gold}"));
        assertThrows(
                UnsupportedOperationException.class, () -> o.properties().put("text", "changed"));
    }
}
