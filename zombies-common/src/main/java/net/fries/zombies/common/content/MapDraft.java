package net.fries.zombies.common.content;

import org.snakeyaml.engine.v2.api.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/**
 * Recoverable metadata editing with bounded undo/redo; published templates never share this graph.
 */
public final class MapDraft {
    private Map<String, Object> document;
    private final Deque<byte[]> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();

    public MapDraft(byte[] source) {
        document = copy(ContentYaml.parse(source).values());
        int schema = ((Number) document.get("schema-version")).intValue();
        if (schema == 1) {
            document.put("windows", new ArrayList<>());
            document.put("initial-rooms", List.of("spawn"));
            document.put("playable-bounds", document.get("bounds"));
            for (var gate : nodes("gates")) gate.put("unlocks-room", gate.get("id"));
        }
        document.put("schema-version", 3);
        document.putIfAbsent("world-source", "blueprint");
        document.putIfAbsent("world-digest", "none");
        document.putIfAbsent("objects", new ArrayList<>());
    }

    public byte[] bytes() {
        return new Dump(
                        DumpSettings.builder()
                                .setDefaultFlowStyle(org.snakeyaml.engine.v2.common.FlowStyle.BLOCK)
                                .build())
                .dumpToString(copy(document))
                .getBytes(StandardCharsets.UTF_8);
    }

    public MapTemplate validate(Gameplay gameplay) {
        return MapTemplate.parse(bytes(), gameplay);
    }

    public void change(Consumer<Map<String, Object>> mutation) {
        byte[] before = bytes();
        try {
            mutation.accept(document);
            if (bytes().length > 262144) throw new IllegalArgumentException("Draft too large");
        } catch (RuntimeException error) {
            document = copy(ContentYaml.parse(before).values());
            throw error;
        }
        undo.addLast(before);
        if (undo.size() > 64) undo.removeFirst();
        redo.clear();
    }

    public boolean undo() {
        return restore(undo, redo);
    }

    public boolean redo() {
        return restore(redo, undo);
    }

    private boolean restore(Deque<byte[]> from, Deque<byte[]> to) {
        if (from.isEmpty()) return false;
        to.addLast(bytes());
        document = copy(ContentYaml.parse(from.removeLast()).values());
        return true;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> nodes(String key) {
        return (List<Map<String, Object>>) document.get(key);
    }

    public Map<String, Object> node(String list, String id) {
        return nodes(list).stream()
                .filter(n -> id.equals(n.get("id")))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown selected object"));
    }

    public static Map<String, Object> point(double x, double y, double z) {
        return new LinkedHashMap<>(Map.of("x", x, "y", y, "z", z));
    }

    public static Map<String, Object> box(int x1, int y1, int z1, int x2, int y2, int z2) {
        return new LinkedHashMap<>(
                Map.of(
                        "x1",
                        Math.min(x1, x2),
                        "y1",
                        Math.min(y1, y2),
                        "z1",
                        Math.min(z1, z2),
                        "x2",
                        Math.max(x1, x2),
                        "y2",
                        Math.max(y1, y2),
                        "z2",
                        Math.max(z1, z2)));
    }

    private static Map<String, Object> copy(Map<?, ?> map) {
        var result = new LinkedHashMap<String, Object>();
        map.forEach((k, v) -> result.put((String) k, deep(v)));
        return result;
    }

    private static Object deep(Object value) {
        if (value instanceof Map<?, ?> map) return copy(map);
        if (value instanceof List<?> list)
            return new ArrayList<>(list.stream().map(MapDraft::deep).toList());
        return value;
    }
}
