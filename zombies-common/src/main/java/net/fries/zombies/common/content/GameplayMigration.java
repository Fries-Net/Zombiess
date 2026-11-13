package net.fries.zombies.common.content;

import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;

import java.util.*;

/** Pure version migration. The disk adapter owns backup and atomic replacement. */
public final class GameplayMigration {
    public static byte[] upgrade(byte[] original, byte[] bundled) {
        var old = ContentYaml.parse(original);
        int schema = old.integer("schema-version", 1, 2);
        if (schema == 2) return original.clone();
        var defaults = ContentYaml.parse(bundled);
        var result = new LinkedHashMap<String, Object>();
        old.values().forEach((key, value) -> result.put((String) key, value));
        result.put("schema-version", 2);
        var weapons = new ArrayList<Map<String, Object>>();
        var ids = new HashSet<String>();
        for (var weapon : old.nodes("weapons")) {
            var target = new LinkedHashMap<String, Object>();
            weapon.values().forEach((key, value) -> target.put((String) key, value));
            String id = weapon.text("id");
            ids.add(id);
            var definition =
                    defaults.nodes("weapons").stream()
                            .filter(w -> w.text("id").equals(id))
                            .findFirst();
            var behaviour = definition.orElse(defaults.nodes("weapons").getFirst());
            for (String key :
                    List.of(
                            "fire-mode",
                            "burst-shots",
                            "burst-interval-ticks",
                            "fire-sound",
                            "sound-pitch",
                            "shot-particle",
                            "pierce-targets")) target.put(key, behaviour.values().get(key));
            target.put("asset", id);
            // Only replace the exact shipped Phase 2 balance; retain operator-customized values.
            if (id.equals("friesnet:weapon/glock18")
                    && Objects.equals(target.get("cooldown-ticks"), 4))
                target.put("cooldown-ticks", 30);
            if (id.equals("friesnet:weapon/glock18") && Objects.equals(target.get("magazine"), 20))
                target.put("magazine", 10);
            if (id.equals("friesnet:weapon/ak47")
                    && Objects.equals(target.get("cooldown-ticks"), 2))
                target.put("cooldown-ticks", 20);
            if (id.equals("friesnet:weapon/ak47") && Objects.equals(target.get("magazine"), 30))
                target.put("magazine", 20);
            weapons.add(target);
        }
        for (var weapon : defaults.nodes("weapons"))
            if (!ids.contains(weapon.text("id"))) {
                var target = new LinkedHashMap<String, Object>();
                weapon.values().forEach((key, value) -> target.put((String) key, value));
                weapons.add(target);
            }
        result.put("weapons", weapons);
        return new Dump(DumpSettings.builder().build())
                .dumpToString(result)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
