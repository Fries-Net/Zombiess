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
import org.johnfries.jZombies.service.SkillService;
import org.johnfries.jZombies.service.WeaponService;

public final class JZombies extends JavaPlugin {
    private ConfigService configService;
    private LangService langService;
    private PlayerDataStore dataStore;
    private MythicMobsBridge mythicMobsBridge;
    private SkillService skillService;
    private WeaponService weaponService;
    private GameManager gameManager;
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
        this.gameManager = new GameManager(
                this,
                configService,
                langService,
                dataStore,
                skillService,
                weaponService,
                mythicMobsBridge
        );

        JZCommand command = new JZCommand(
                configService,
                langService,
                gameManager,
                dataStore,
                skillService,
                weaponService,
                this::reloadSystems
        );
        registerCommand("jz", new JZBasicCommand(command));

        Bukkit.getPluginManager().registerEvents(
                new GameListener(gameManager, configService, langService, dataStore, skillService),
                this
        );

        for (var player : Bukkit.getOnlinePlayers()) {
            gameManager.refreshPlayerStats(player);
        }

        autosaveTask = Bukkit.getScheduler().runTaskTimer(this, dataStore::save, 20L * 60L, 20L * 60L);
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
        for (var player : Bukkit.getOnlinePlayers()) {
            gameManager.refreshPlayerStats(player);
        }
    }
}
