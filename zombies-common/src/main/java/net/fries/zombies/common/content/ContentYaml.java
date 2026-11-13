package net.fries.zombies.common.content;

import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Small strict reader shared by immutable gameplay and map documents. */
public record ContentYaml(Map<?, ?> values) {
    public boolean bool(String key) {
        if (values.get(key) instanceof Boolean value) return value;
        throw new IllegalArgumentException("Expected boolean: " + key);
    }

    public static ContentYaml read(Path path) throws IOException {
        return parse(readBytes(path));
    }

    public static byte[] readBytes(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(262145);
            if (bytes.length > 262144)
                throw new IllegalArgumentException("Content exceeds 256 KiB");
            return bytes;
        }
    }

    public static ContentYaml parse(byte[] bytes) {
        try {
            Object value =
                    new Load(
                                    LoadSettings.builder()
                                            .setAllowDuplicateKeys(false)
                                            .setMaxAliasesForCollections(0)
                                            .setCodePointLimit(262144)
                                            .build())
                            .loadFromString(
                                    new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            if (value instanceof Map<?, ?> map) return new ContentYaml(map);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid content YAML");
        }
        throw new IllegalArgumentException("Expected content mapping");
    }

    public void keys(String... allowed) {
        var names = Set.of(allowed);
        if (!values.keySet().equals(names))
            throw new IllegalArgumentException(
                    "Missing or unknown content keys; expected " + names);
    }

    public ContentYaml node(String key) {
        if (values.get(key) instanceof Map<?, ?> map) return new ContentYaml(map);
        throw new IllegalArgumentException("Expected mapping: " + key);
    }

    public List<ContentYaml> nodes(String key) {
        if (values.get(key) instanceof List<?> list)
            return list.stream()
                    .map(
                            value -> {
                                if (value instanceof Map<?, ?> map) return new ContentYaml(map);
                                throw new IllegalArgumentException("Expected mapping in " + key);
                            })
                    .toList();
        throw new IllegalArgumentException("Expected list: " + key);
    }

    public String text(String key) {
        if (values.get(key) instanceof String text && !text.isBlank()) return text;
        throw new IllegalArgumentException("Expected text: " + key);
    }

    public int integer(String key, int min, int max) {
        if (values.get(key) instanceof Integer number && number >= min && number <= max)
            return number;
        throw new IllegalArgumentException("Expected integer " + min + ".." + max + ": " + key);
    }

    public double number(String key, double min, double max) {
        if (values.get(key) instanceof Number number
                && Double.isFinite(number.doubleValue())
                && number.doubleValue() >= min
                && number.doubleValue() <= max) return number.doubleValue();
        throw new IllegalArgumentException(
                "Expected finite number " + min + ".." + max + ": " + key);
    }
}
