package org.johnfries.jZombies.model;

import java.util.Map;

public record SkillTree(
        String id,
        String displayName,
        Map<String, SkillDefinition> skills
) {
}
