package net.fries.zombies.persistence;

import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Immutable world-folder snapshots. Call only for unloaded worlds on the disk worker. */
public final class WorldSnapshot {
    private static final Set<String> OMIT =
            Set.of(
                    "session.lock",
                    "uid.dat",
                    "playerdata",
                    "advancements",
                    "stats",
                    "friesnet-runtime-world");

    public static String copy(Path source, Path destination) throws IOException {
        source = source.toAbsolutePath().normalize();
        destination = destination.toAbsolutePath().normalize();
        if (destination.startsWith(source)
                || Files.exists(destination)
                || Files.isSymbolicLink(source)
                || !Files.isRegularFile(source.resolve("level.dat")))
            throw new IOException("Expected an unloaded world and a new snapshot destination");
        var files = files(source);
        Files.createDirectories(destination);
        long size = 0;
        for (var file : files) {
            size = Math.addExact(size, Files.size(file));
            if (size > 4L * 1024 * 1024 * 1024)
                throw new IOException("World snapshot exceeds 4 GiB");
            var out = destination.resolve(source.relativize(file)).normalize();
            if (!out.startsWith(destination)) throw new IOException("Invalid world entry");
            Files.createDirectories(out.getParent());
            Files.copy(file, out);
        }
        return digest(destination);
    }

    public static String digest(Path directory) throws IOException {
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            for (var file : files(directory)) {
                byte[] name =
                        directory
                                .relativize(file)
                                .toString()
                                .replace('\\', '/')
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                hash.update(java.nio.ByteBuffer.allocate(4).putInt(name.length).array());
                hash.update(name);
                hash.update(java.nio.ByteBuffer.allocate(8).putLong(Files.size(file)).array());
                try (var in = Files.newInputStream(file)) {
                    int n;
                    while ((n = in.read(buffer)) != -1) hash.update(buffer, 0, n);
                }
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static List<Path> files(Path directory) throws IOException {
        try (var walk = Files.walk(directory)) {
            var entries = walk.toList();
            if (entries.size() > 50000 || entries.stream().anyMatch(Files::isSymbolicLink))
                throw new IOException("Unsafe or oversized world tree");
            return entries.stream()
                    .filter(Files::isRegularFile)
                    .filter(
                            p -> {
                                for (var part : directory.relativize(p))
                                    if (OMIT.contains(part.toString())) return false;
                                return true;
                            })
                    .sorted(
                            Comparator.comparing(
                                    p -> directory.relativize(p).toString().replace('\\', '/')))
                    .toList();
        }
    }

    private WorldSnapshot() {}
}
