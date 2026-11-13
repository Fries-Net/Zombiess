package org.johnfries.jZombies;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.johnfries.jZombies.command.JZBasicCommand;
import org.johnfries.jZombies.command.JZCommand;
import org.johnfries.jZombies.config.ConfigService;
import org.johnfries.jZombies.config.LangService;
import org.johnfries.jZombies.data.PlayerDataStore;
import org.johnfries.jZombies.game.GameManager;
import org.johnfries.jZombies.integration.MythicMobsBridge;
import org.johnfries.jZombies.listener.GameListener;
import org.johnfries.jZombies.service.AreaHologramService;
import org.johnfries.jZombies.service.ScoreboardService;
import org.johnfries.jZombies.service.SkillService;
import org.johnfries.jZombies.service.SkillTreeGuiService;
import org.johnfries.jZombies.service.WeaponService;

import java.util.Set;

public final class JZombies extends JavaPlugin {
    private ConfigService configService;
    private LangService langService;
    private PlayerDataStore dataStore;
    private MythicMobsBridge mythicMobsBridge;
    private SkillService skillService;
    private SkillTreeGuiService skillTreeGuiService;
    private WeaponService weaponService;
    private AreaHologramService areaHologramService;
    private GameManager gameManager;
    private ScoreboardService scoreboardService;
    private BukkitTask autosaveTask;

    @Override
    public void onEnable() {
        this.configService = new ConfigService(this);
        configService.loadOrReload();

        this.langService = new LangService(this);
        langService.load(configService.getLanguage());

        this.dataStore = new PlayerDataStore(this);
        dataStore.load();

        this.mythicMobsBridge = new MythicMobsBridge(this);
        mythicMobsBridge.refresh();

        this.skillService = new SkillService(configService.getSkillTrees());
        this.weaponService = new WeaponService(this, configService.getWeapons(), configService.getSkins());
        this.areaHologramService = new AreaHologramService(this, langService);
        this.gameManager = new GameManager(
                this,
                configService,
                langService,
                dataStore,
                skillService,
                weaponService,
                mythicMobsBridge,
                areaHologramService
        );
        this.skillTreeGuiService = new SkillTreeGuiService(this, langService, dataStore, skillService, gameManager);
        this.scoreboardService = new ScoreboardService(this, configService, dataStore, gameManager);

        JZCommand command = new JZCommand(
                configService,
                langService,
                gameManager,
                dataStore,
                skillService,
                weaponService,
                skillTreeGuiService,
                this::reloadSystems
        );
        registerCommand("jz", new JZBasicCommand(command));

        Bukkit.getPluginManager().registerEvents(
                new GameListener(this, gameManager, configService, langService, dataStore, skillService, weaponService, skillTreeGuiService),
                this
        );

        for (var player : Bukkit.getOnlinePlayers()) {
            gameManager.refreshPlayerStats(player);
        }

        autosaveTask = Bukkit.getScheduler().runTaskTimer(this, dataStore::save, 20L * 60L, 20L * 60L);
        areaHologramService.rebuild(configService.getBuyableAreas(), Set.of());
        scoreboardService.start();
        getLogger().info("jZombies enabled.");
    }

    @Override
    public void onDisable() {
        if (autosaveTask != null) {
            autosaveTask.cancel();
            autosaveTask = null;
        }
        if (gameManager != null) {
            gameManager.stopGame();
        }
        if (scoreboardService != null) {
            scoreboardService.stop();
        }
        if (areaHologramService != null) {
            areaHologramService.clear();
        }
        if (dataStore != null) {
            dataStore.save();
        }
        getLogger().info("jZombies disabled.");
    }

    private void reloadSystems() {
        if (gameManager != null) {
            gameManager.stopGame();
        }
        configService.loadOrReload();
        langService.load(configService.getLanguage());
        mythicMobsBridge.refresh();
        skillService.setTrees(configService.getSkillTrees());
        weaponService.setCatalog(configService.getWeapons(), configService.getSkins());
        areaHologramService.rebuild(configService.getBuyableAreas(), Set.of());
        if (scoreboardService != null) {
            scoreboardService.stop();
            scoreboardService.start();
        }
        for (var player : Bukkit.getOnlinePlayers()) {
            gameManager.refreshPlayerStats(player);
        }
    }
}
