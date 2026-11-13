package net.fries.zombies.persistence;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;

class WorldSnapshotTest {
    @TempDir Path root;

    @Test
    void copiesGeometryExcludesIdentityAndDetectsTampering() throws Exception {
        var from = Files.createDirectory(root.resolve("from"));
        Files.writeString(from.resolve("level.dat"), "level");
        Files.createDirectories(from.resolve("region"));
        Files.writeString(from.resolve("region/r.0.0.mca"), "geometry");
        Files.writeString(from.resolve("uid.dat"), "original identity");
        Files.writeString(from.resolve("session.lock"), "locked");
        var to = root.resolve("snapshot");
        var digest = WorldSnapshot.copy(from, to);
        assertEquals(digest, WorldSnapshot.digest(to));
        assertFalse(Files.exists(to.resolve("uid.dat")));
        assertEquals("geometry", Files.readString(to.resolve("region/r.0.0.mca")));
        Files.writeString(to.resolve("region/r.0.0.mca"), "altered");
        assertNotEquals(digest, WorldSnapshot.digest(to));
        assertThrows(java.io.IOException.class, () -> WorldSnapshot.copy(from, to));
        assertThrows(
                java.io.IOException.class, () -> WorldSnapshot.copy(from, from.resolve("nested")));
    }
}
