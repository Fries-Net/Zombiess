package net.fries.zombies.common.content;

import org.snakeyaml.engine.v2.api.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Updates only a known shipped weapon/upgrade pair. Custom definitions are never guessed at. */
public final class WeaponBalanceMigration {
    public record Result(byte[] gameplay, byte[] upgrades) {}

    public static Result upgrade(
            byte[] gameplay,
            byte[] upgrades,
            byte[] priorGameplay,
            byte[] priorUpgrades,
            byte[] nextGameplay,
            byte[] nextUpgrades) {
        var g = ContentYaml.parse(gameplay);
        var u = ContentYaml.parse(upgrades);
        if (g.integer("schema-version", 1, 3) != 3)
            return new Result(gameplay.clone(), upgrades.clone());
        var oldG = entries(priorGameplay, "id");
        var oldU = entries(priorUpgrades, "weapon");
        var newG = entries(nextGameplay, "id");
        var newU = entries(nextUpgrades, "weapon");
        var currentU = entries(upgrades, "weapon");
        var replacements = new HashSet<String>();
        for (var n : g.nodes("weapons")) {
            String id = n.text("id");
            if (n.values().equals(oldG.get(id))
                    && (Objects.equals(currentU.get(id), oldU.get(id))
                            || Objects.equals(currentU.get(id), newU.get(id)))
                    && newG.containsKey(id)
                    && newU.containsKey(id)) replacements.add(id);
        }
        if (replacements.isEmpty()) return new Result(gameplay.clone(), upgrades.clone());
        return new Result(
                replace(g, "id", newG, replacements), replace(u, "weapon", newU, replacements));
    }

    private static Map<String, Map<?, ?>> entries(byte[] bytes, String key) {
        var entries = new LinkedHashMap<String, Map<?, ?>>();
        for (var n : ContentYaml.parse(bytes).nodes("weapons"))
            entries.put(n.text(key), n.values());
        return entries;
    }

    private static byte[] replace(
            ContentYaml root,
            String key,
            Map<String, Map<?, ?>> replacements,
            Set<String> selected) {
        var doc = new LinkedHashMap<Object, Object>(root.values());
        var list =
                root.nodes("weapons").stream()
                        .map(
                                n ->
                                        selected.contains(n.text(key))
                                                ? replacements.get(n.text(key))
                                                : n.values())
                        .toList();
        doc.put("weapons", list);
        return new Dump(DumpSettings.builder().build())
                .dumpToString(doc)
                .getBytes(StandardCharsets.UTF_8);
    }
}
