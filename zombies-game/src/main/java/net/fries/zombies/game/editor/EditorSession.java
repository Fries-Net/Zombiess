package net.fries.zombies.game.editor;

import net.fries.zombies.common.content.MapDraft;

import org.bukkit.*;

import java.util.*;

/** One author owns an isolated draft lease. Terrain undo remains the building tool's concern. */
public final class EditorSession {
    public final UUID player;
    public final String map, worldName;
    public final MapDraft draft;
    public World world;
    public Location first, second;
    public String selectedList = "objects", selectedId = "", tool = "select", field = "point";
    public boolean busy;

    public EditorSession(UUID player, String map, String worldName, MapDraft draft, World world) {
        this.player = player;
        this.map = map;
        this.worldName = worldName;
        this.draft = draft;
        this.world = world;
    }

    public Map<String, Object> selected() {
        return draft.node(selectedList, selectedId);
    }
}
