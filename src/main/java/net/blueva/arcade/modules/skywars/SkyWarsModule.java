package net.blueva.arcade.modules.skywars;

import net.blueva.arcade.api.ModuleAPI;
import net.blueva.arcade.api.achievements.AchievementsAPI;
import net.blueva.arcade.api.config.CoreConfigAPI;
import net.blueva.arcade.api.config.ModuleConfigAPI;
import net.blueva.arcade.api.events.CustomEventRegistry;
import net.blueva.arcade.api.game.GameContext;
import net.blueva.arcade.api.game.GameModule;
import net.blueva.arcade.api.game.GameResult;
import net.blueva.arcade.api.module.ModuleInfo;
import net.blueva.arcade.api.stats.StatDefinition;
import net.blueva.arcade.api.stats.StatScope;
import net.blueva.arcade.api.stats.StatsAPI;
import net.blueva.arcade.api.store.StoreAPI;
import net.blueva.arcade.api.ui.ItemAPI;
import net.blueva.arcade.api.ui.MenuAPI;
import net.blueva.arcade.api.ui.VoteMenuAPI;
import net.blueva.arcade.modules.skywars.game.SkyWarsGame;
import net.blueva.arcade.modules.skywars.listener.SkyWarsListener;
import net.blueva.arcade.modules.skywars.listener.SkyWarsVoteListener;
import net.blueva.arcade.modules.skywars.setup.SkyWarsSetup;
import net.blueva.arcade.modules.skywars.support.store.SkyWarsStoreService;
import net.blueva.arcade.modules.skywars.support.vote.SkyWarsVoteService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

public class SkyWarsModule implements GameModule<Player, Location, World, Material, ItemStack, Sound, Block, Entity, Listener, EventPriority> {

    private ModuleConfigAPI moduleConfig;
    private CoreConfigAPI coreConfig;
    private ModuleInfo moduleInfo;
    private StatsAPI statsAPI;

    private SkyWarsGame game;

    @Override
    public void onLoad() {
        moduleInfo = ModuleAPI.getModuleInfo("skywars");
        if (moduleInfo == null) {
            throw new IllegalStateException("ModuleInfo not available for SkyWars module");
        }

        moduleConfig = ModuleAPI.getModuleConfig(moduleInfo.getId());
        coreConfig = ModuleAPI.getCoreConfig();
        statsAPI = ModuleAPI.getStatsAPI();

        registerConfigs();
        registerStats();
        registerAchievements();

        StoreAPI storeAPI = ModuleAPI.getStoreAPI();
        SkyWarsStoreService storeService = new SkyWarsStoreService(moduleConfig, storeAPI, moduleInfo);
        storeService.registerStoreItems();

        MenuAPI<Player, Material> menuAPI = ModuleAPI.getMenuAPI();
        @SuppressWarnings("unchecked")
        ItemAPI<Player, ItemStack, Material> itemAPI = (ItemAPI<Player, ItemStack, Material>) ModuleAPI.getItemAPI();
        SkyWarsVoteService voteService = new SkyWarsVoteService(moduleConfig, menuAPI, itemAPI, moduleInfo.getId());

        game = new SkyWarsGame(moduleInfo, moduleConfig, coreConfig, statsAPI, storeAPI, voteService);

        if (menuAPI != null) {
            menuAPI.registerModuleActionHandler(moduleInfo.getId(), (player, payload) -> {
                if (player == null || payload == null || payload.isBlank()) {
                    return false;
                }
                String[] args = payload.trim().split("\\s+");
                return game.handleVoteCommand(player, args);
            });
        }

        voteService.registerWaitingItem();
        voteService.registerClickHandler(game);
        ModuleAPI.getSetupAPI().registerHandler(moduleInfo.getId(), new SkyWarsSetup(this));

        VoteMenuAPI voteMenu = ModuleAPI.getVoteMenuAPI();
        if (moduleConfig != null && voteMenu != null) {
            voteMenu.registerGame(
                    moduleInfo.getId(),
                    Material.valueOf(moduleConfig.getString("menus.vote.item")),
                    moduleConfig.getStringFrom("language.yml", "vote_menu.name"),
                    moduleConfig.getStringListFrom("language.yml", "vote_menu.lore")
            );
        }
    }

    @Override
    public void onStart(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        game.startGame(context);
    }

    @Override
    public void onCountdownTick(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                int secondsLeft) {
        game.handleCountdownTick(context, secondsLeft);
    }

    @Override
    public void onCountdownFinish(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        game.handleCountdownFinish(context);
    }

    @Override
    public boolean freezePlayersOnCountdown() {
        return false;
    }

    @Override
    public void onGameStart(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        game.beginPlaying(context);
    }

    @Override
    public void onEnd(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                      GameResult<Player> result) {
        game.finishGame(context);
    }

    @Override
    public void onDisable() {
        if (game != null) {
            game.shutdown();
        }
    }

    @Override
    public void registerEvents(CustomEventRegistry<Listener, EventPriority> registry) {
        registry.register(new SkyWarsListener(game));
        registry.register(new SkyWarsVoteListener(game));
    }

    @Override
    public Map<String, String> getCustomPlaceholders(Player player) {
        return game.getPlaceholders(player);
    }

    public ModuleConfigAPI getModuleConfig() {
        return moduleConfig;
    }

    public CoreConfigAPI getCoreConfig() {
        return coreConfig;
    }

    public ModuleInfo getModuleInfo() {
        return moduleInfo;
    }

    private void registerConfigs() {
        moduleConfig.register("language.yml", 2);
        moduleConfig.register("settings.yml", 2);
        moduleConfig.register("achievements.yml", 1);
        moduleConfig.register("store.yml", 1);
        moduleConfig.register("kits.yml", 1);
        moduleConfig.register("cage.yml", 1);
        moduleConfig.register("menus/java/skywars_vote_main.yml", 1);
        moduleConfig.register("menus/java/skywars_vote_chests.yml", 1);
        moduleConfig.register("menus/java/skywars_vote_hearts.yml", 1);
        moduleConfig.register("menus/java/skywars_vote_time.yml", 1);
        moduleConfig.register("menus/java/skywars_vote_weather.yml", 1);
        moduleConfig.register("menus/bedrock/skywars_vote_main.yml", 1);
        moduleConfig.register("menus/bedrock/skywars_vote_chests.yml", 1);
        moduleConfig.register("menus/bedrock/skywars_vote_hearts.yml", 1);
        moduleConfig.register("menus/bedrock/skywars_vote_time.yml", 1);
        moduleConfig.register("menus/bedrock/skywars_vote_weather.yml", 1);
    }

    private void registerStats() {
        if (statsAPI == null) {
            return;
        }

        statsAPI.registerModuleStat(moduleInfo.getId(),
                new StatDefinition("wins", "Wins", "SkyWars victories", StatScope.MODULE));
        statsAPI.registerModuleStat(moduleInfo.getId(),
                new StatDefinition("games_played", "Games Played", "SkyWars matches played", StatScope.MODULE));
        statsAPI.registerModuleStat(moduleInfo.getId(),
                new StatDefinition("kills", "Eliminations", "Opponents eliminated in SkyWars", StatScope.MODULE));
        statsAPI.registerModuleStat(moduleInfo.getId(),
                new StatDefinition("chests_looted", "Chests Looted", "Looted chests in SkyWars", StatScope.MODULE));
        statsAPI.registerModuleStat(moduleInfo.getId(),
                new StatDefinition("storm_damage_taken", "Storm Damage Taken",
                        "Damage received from the storm in SkyWars", StatScope.MODULE));
    }

    private void registerAchievements() {
        AchievementsAPI achievementsAPI = ModuleAPI.getAchievementsAPI();
        if (achievementsAPI != null) {
            achievementsAPI.registerModuleAchievements(moduleInfo.getId(), "achievements.yml");
        }
    }
}
