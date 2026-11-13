package net.fries.zombies.persistence;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.concurrent.*;

/** Serial bounded disk worker; accepted writes drain on close. No disk work on the Paper thread. */
public final class DurableFiles implements AutoCloseable {
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(256),
                    task -> new Thread(task, "zombies-journal"),
                    new ThreadPoolExecutor.AbortPolicy());

    public <T> CompletableFuture<T> submit(Operation<T> operation) {
        var future = new CompletableFuture<T>();
        try {
            worker.execute(
                    () -> {
                        try {
                            future.complete(operation.run());
                        } catch (Exception e) {
                            future.completeExceptionally(e);
                        }
                    });
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    public static void replace(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (var channel =
                FileChannel.open(
                        temporary,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE)) {
            var buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        // No non-atomic fallback: if the filesystem cannot offer this guarantee the instance stays
        // unavailable.
        Files.move(
                temporary,
                file,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        forceDirectory(file.getParent());
    }

    public static void delete(Path file) throws IOException {
        Files.deleteIfExists(file);
        forceDirectory(file.getParent());
    }

    private static void forceDirectory(Path directory) throws IOException {
        // Windows denies directory channel access; NTFS atomic replace plus forced file contents is
        // used there.
        if (System.getProperty("os.name").startsWith("Windows")) return;
        try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    @Override
    public void close() {
        worker.shutdown();
    }

    @FunctionalInterface
    public interface Operation<T> {
        T run() throws Exception;
    }
}
