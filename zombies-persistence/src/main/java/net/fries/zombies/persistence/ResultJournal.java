package net.fries.zombies.persistence;

import net.fries.zombies.common.NamespacedId;
import net.fries.zombies.common.match.*;
import net.fries.zombies.common.persistence.AccountRepository;
import net.fries.zombies.common.persistence.ResultReceipt;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Atomic checkpoints and final submissions. A final entry never regresses to an active checkpoint.
 */
public final class ResultJournal {
    public record Entry(boolean completed, MatchResult result) {}

    private final Path directory;
    private final DurableFiles disk;

    public ResultJournal(Path directory, DurableFiles disk) {
        this.directory = directory;
        this.disk = disk;
    }

    public CompletableFuture<Void> save(MatchResult result, boolean completed) {
        return disk.submit(
                () -> {
                    var file = file(result.identity().matchId());
                    if (Files.exists(file)) {
                        var old = read(file);
                        if (old.completed()) {
                            if (!completed
                                    || !old.result().fingerprint().equals(result.fingerprint()))
                                throw new IOException("Final result cannot change");
                            return null;
                        }
                    }
                    DurableFiles.replace(file, encode(new Entry(completed, result)));
                    return null;
                });
    }

    public CompletableFuture<ResultReceipt> commit(MatchResult result, AccountRepository accounts) {
        return save(result, true)
                .thenCompose(ignored -> accounts.commit(result))
                .thenCompose(
                        receipt ->
                                disk.submit(
                                        () -> {
                                            DurableFiles.delete(file(result.identity().matchId()));
                                            return receipt;
                                        }));
    }

    public CompletableFuture<Integer> recover(AccountRepository accounts) {
        return disk.submit(
                        () -> {
                            Files.createDirectories(directory);
                            var entries = new ArrayList<Entry>();
                            try (var files = Files.list(directory)) {
                                for (Path file :
                                        files.filter(
                                                        p ->
                                                                p.getFileName()
                                                                        .toString()
                                                                        .endsWith(".result"))
                                                .sorted()
                                                .toList()) {
                                    var entry = read(file);
                                    if (!file.equals(file(entry.result().identity().matchId())))
                                        throw new IOException("Journal identity mismatch");
                                    entries.add(entry);
                                }
                            }
                            return entries;
                        })
                .thenCompose(
                        entries -> {
                            CompletableFuture<Integer> chain = CompletableFuture.completedFuture(0);
                            for (var entry : entries)
                                chain =
                                        chain.thenCompose(
                                                count ->
                                                        commit(entry.result(), accounts)
                                                                .thenApply(receipt -> count + 1));
                            return chain;
                        });
    }

    private Path file(UUID id) {
        return directory.resolve(id + ".result");
    }

    private Entry read(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || Files.size(path) > 65536)
            throw new IOException("Invalid journal file");
        return decode(Files.readAllBytes(path));
    }

    static byte[] encode(Entry entry) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(1);
            out.writeBoolean(entry.completed());
            var r = entry.result();
            var i = r.identity();
            out.writeUTF(i.matchId().toString());
            out.writeUTF(i.serverId());
            out.writeUTF(i.mapId().value());
            out.writeUTF(i.mapVersion());
            out.writeUTF(i.packVersion());
            out.writeUTF(r.startedAt().toString());
            out.writeUTF(r.endedAt().toString());
            out.writeUTF(r.outcome().name());
            out.writeInt(r.players().size());
            for (var p : r.players()) {
                out.writeUTF(p.playerId().toString());
                out.writeInt(p.rounds());
                out.writeInt(p.kills());
                out.writeInt(p.headshots());
                out.writeInt(p.revives());
                out.writeLong(p.tokens());
                out.writeLong(p.experience());
            }
        }
        byte[] payload = bytes.toByteArray();
        bytes.writeBytes(digest(payload));
        return bytes.toByteArray();
    }

    static Entry decode(byte[] bytes) throws IOException {
        if (bytes.length < 37 || bytes.length > 65536)
            throw new IOException("Invalid journal size");
        byte[] payload = Arrays.copyOf(bytes, bytes.length - 32);
        if (!MessageDigest.isEqual(
                digest(payload), Arrays.copyOfRange(bytes, bytes.length - 32, bytes.length)))
            throw new IOException("Journal checksum mismatch");
        try (var in = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (in.readInt() != 1) throw new IOException("Unsupported journal version");
            boolean completed = in.readBoolean();
            var identity =
                    new MatchIdentity(
                            UUID.fromString(in.readUTF()),
                            in.readUTF(),
                            new NamespacedId(in.readUTF()),
                            in.readUTF(),
                            in.readUTF());
            var start = Instant.parse(in.readUTF());
            var end = Instant.parse(in.readUTF());
            var outcome = MatchResult.Outcome.valueOf(in.readUTF());
            if (!completed && outcome != MatchResult.Outcome.ABANDONED)
                throw new IOException("Invalid checkpoint outcome");
            int count = in.readInt();
            if (count < 1 || count > 64) throw new IOException("Invalid journal roster");
            var players = new ArrayList<PlayerResult>();
            for (int n = 0; n < count; n++)
                players.add(
                        new PlayerResult(
                                UUID.fromString(in.readUTF()),
                                in.readInt(),
                                in.readInt(),
                                in.readInt(),
                                in.readInt(),
                                in.readLong(),
                                in.readLong()));
            if (in.available() != 0) throw new IOException("Trailing journal bytes");
            return new Entry(completed, new MatchResult(identity, start, end, outcome, players));
        } catch (RuntimeException e) {
            throw new IOException("Invalid journal record", e);
        }
    }

    private static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
