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
import net.blueva.arcade.api.player.PlayerAPI;
import net.blueva.arcade.api.stats.StatDefinition;
import net.blueva.arcade.api.stats.StatScope;
import net.blueva.arcade.api.stats.StatsAPI;
import net.blueva.arcade.api.store.StoreAPI;
import net.blueva.arcade.api.ui.ItemAPI;
import net.blueva.arcade.api.ui.MenuAPI;
import net.blueva.arcade.api.setup.SetupRequirement;
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
import java.util.Set;
import net.blueva.arcade.api.setup.ModuleSetupCommand;
import net.blueva.arcade.api.setup.ModuleSetupMetadata;
import net.blueva.arcade.api.setup.ModuleSetupStep;
import net.blueva.arcade.api.setup.ModuleSetupStatusCheck;
import java.util.List;

public class SkyWarsModule implements GameModule<Player, Location, World, Material, ItemStack, Sound, Block, Entity, Listener, EventPriority> {

    private ModuleConfigAPI moduleConfig;
    private CoreConfigAPI coreConfig;
    private ModuleInfo moduleInfo;
    private StatsAPI statsAPI;
    private MenuAPI<Player, Material> menuAPI;
    private ItemAPI<Player, ItemStack, Material> itemAPI;

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
        this.menuAPI = menuAPI;
        @SuppressWarnings("unchecked")
        ItemAPI<Player, ItemStack, Material> itemAPI = (ItemAPI<Player, ItemStack, Material>) ModuleAPI.getItemAPI();
        this.itemAPI = itemAPI;
        SkyWarsVoteService voteService = new SkyWarsVoteService(moduleConfig, menuAPI, itemAPI, moduleInfo.getId());

        game = new SkyWarsGame(moduleInfo, moduleConfig, coreConfig, statsAPI, storeAPI, voteService);
        voteService.setGame(game);

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
                    moduleConfig.getTranslation(null, "vote_menu.name"),
                    moduleConfig.getTranslationList(null, "vote_menu.lore")
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
    public Set<SetupRequirement> getDisabledRequirements() {
        return Set.of(SetupRequirement.SPAWNS);
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
        if (menuAPI != null && moduleInfo != null) {
            menuAPI.unregisterModuleMenuAPI(moduleInfo.getId());
        }
        if (itemAPI != null) {
            itemAPI.unregisterWaitingItem("skywars_vote_settings");
            itemAPI.unregisterClickHandler("skywars_vote_settings");
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
        moduleConfig.register("settings.yml");
        moduleConfig.register("achievements.yml");
        moduleConfig.register("store.yml");
        moduleConfig.registerCopyOnly("kits.yml");
        moduleConfig.registerCopyOnly("cage.yml");
        moduleConfig.register("menus/java/skywars_vote_main.yml");
        moduleConfig.register("menus/java/skywars_vote_chests.yml");
        moduleConfig.register("menus/java/skywars_vote_hearts.yml");
        moduleConfig.register("menus/java/skywars_vote_time.yml");
        moduleConfig.register("menus/java/skywars_vote_weather.yml");
        moduleConfig.register("menus/bedrock/skywars_vote_main.yml");
        moduleConfig.register("menus/bedrock/skywars_vote_chests.yml");
        moduleConfig.register("menus/bedrock/skywars_vote_hearts.yml");
        moduleConfig.register("menus/bedrock/skywars_vote_time.yml");
        moduleConfig.register("menus/bedrock/skywars_vote_weather.yml");
    }

    private void registerStats() {
        if (statsAPI == null) {
            return;
        }

        statsAPI.registerModuleStat(moduleInfo.getId(),
                new StatDefinition("wins", moduleConfig.getTranslation(null, "stats.labels.wins"), moduleConfig.getTranslation(null, "stats.descriptions.wins"), StatScope.MODULE));
        statsAPI.registerModuleStat(moduleInfo.getId(),
                new StatDefinition("games_played", moduleConfig.getTranslation(null, "stats.labels.games_played"), moduleConfig.getTranslation(null, "stats.descriptions.games_played"), StatScope.MODULE));
        statsAPI.registerModuleStat(moduleInfo.getId(),
                new StatDefinition("kills", moduleConfig.getTranslation(null, "stats.labels.kills"), moduleConfig.getTranslation(null, "stats.descriptions.kills"), StatScope.MODULE));
        statsAPI.registerModuleStat(moduleInfo.getId(),
                new StatDefinition("chests_looted", moduleConfig.getTranslation(null, "stats.labels.chests_looted"), moduleConfig.getTranslation(null, "stats.descriptions.chests_looted"), StatScope.MODULE));
        statsAPI.registerModuleStat(moduleInfo.getId(),
                new StatDefinition("storm_damage_taken", moduleConfig.getTranslation(null, "stats.labels.storm_damage_taken"),
                        moduleConfig.getTranslation(null, "stats.descriptions.storm_damage_taken"), StatScope.MODULE));
    }

    private void registerAchievements() {
        AchievementsAPI achievementsAPI = ModuleAPI.getAchievementsAPI();
        if (achievementsAPI != null) {
            achievementsAPI.registerModuleAchievements(moduleInfo.getId(), "achievements.yml");
        }
    }

    @Override
    public ModuleSetupMetadata getSetupMetadata() {
        return new ModuleSetupMetadata() {

            @Override
            public List<ModuleSetupStep> getSetupSteps() {
                return List.of(
                        new ModuleSetupStep("region", true, "Configure Region", "Configure the module-specific region setup data.", List.of("/baa game <arena> skywars region"), "selection region"),
                        new ModuleSetupStep("searchchests", true, "Configure Searchchests", "Configure the module-specific searchchests setup data.", List.of("/baa game <arena> skywars searchchests"), "chest locations"),
                        new ModuleSetupStep("team", true, "Configure Team", "Configure the module-specific team setup data.", List.of("/baa game <arena> skywars team"), "team count and team size")
                );
            }

            @Override
            public List<ModuleSetupCommand> getSetupCommands() {
                return List.of(
                        new ModuleSetupCommand("region", "/baa game <arena> skywars region", "Configure region setup data.", true),
                        new ModuleSetupCommand("searchchests", "/baa game <arena> skywars searchchests", "Configure searchchests setup data.", true),
                        new ModuleSetupCommand("team", "/baa game <arena> skywars team", "Configure team setup data.", true)
                );
            }

            @Override
            public List<ModuleSetupStatusCheck<?, ?, ?>> getStatusChecks() {
                return List.of(
                        new ModuleSetupStatusCheck<>("region", true, "Select the play area region.", context -> (context.getData().has("game.play_area.bounds.min.x") && context.getData().has("game.play_area.bounds.max.x")) || (context.getData().has("game.region.bounds.min.x") && context.getData().has("game.region.bounds.max.x"))),
                        new ModuleSetupStatusCheck<>("searchchests", true, "Search and save map chests.", context -> context.getData().has("loot.chests.locations")),
                        new ModuleSetupStatusCheck<>("team", true, "Set team count and team size.", context -> context.getData().getInt("teams.count", 0) > 0 && context.getData().getInt("teams.size", 0) > 0)
                );
            }
        };
    }

}
