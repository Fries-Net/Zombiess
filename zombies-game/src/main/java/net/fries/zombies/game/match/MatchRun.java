package net.fries.zombies.game.match;

import net.fries.zombies.common.content.*;
import net.fries.zombies.common.gameplay.Survivor;
import net.fries.zombies.common.match.*;
import net.fries.zombies.game.combat.CombatService;
import net.fries.zombies.game.player.LifeService;
import net.fries.zombies.game.ui.MatchHud;
import net.fries.zombies.game.world.InteractionService;
import net.fries.zombies.game.world.WaveService;

import org.bukkit.World;

import java.util.*;

/**
 * Session-scoped resources. The coordinator owns creation and disposal, services own their
 * behavior.
 */
public final class MatchRun {
    public final MatchSession session;
    public final Gameplay content;
    public final MapTemplate map;
    public final MatchRules rules;
    public final Map<UUID, Survivor> players = new LinkedHashMap<>();
    public World world;
    public String worldName;
    public long tick;
    public java.time.Instant countdownEnds;
    public boolean preparing, ending, committing, cleaning, checkpointing;
    public MatchResult result;
    public long retryAt;
    public CombatService combat;
    public final net.fries.zombies.common.gameplay.PointsLedger points;
    public WaveService waves;
    public LifeService lives;
    public InteractionService interactions;
    public MatchHud hud;
    public net.fries.zombies.game.ui.DynamicHolograms holograms;
    public net.fries.zombies.game.economy.PowerUpService powerups;
    public net.fries.zombies.game.world.WindowService windows;

    public MatchRun(MatchSession session, MatchRules rules, Gameplay content, MapTemplate map) {
        this.session = session;
        this.rules = rules;
        this.content = content;
        this.map = map;
        points = new net.fries.zombies.common.gameplay.PointsLedger(content.scoring(), players);
    }

    public boolean playing() {
        return !preparing && !ending && session.snapshot().phase() == MatchPhase.IN_PROGRESS;
    }

    public Survivor survivor(UUID id) {
        return players.get(id);
    }

    public boolean alive(UUID id) {
        var s = players.get(id);
        return playing() && s != null && s.life() == Survivor.Life.ALIVE;
    }

    public void dispose() {
        if (windows != null) windows.clear();
        if (powerups != null) powerups.clear();
        if (waves != null) waves.clear();
        if (interactions != null) interactions.clear();
        if (hud != null) hud.clear();
        if (holograms != null) holograms.clear();
    }
}
