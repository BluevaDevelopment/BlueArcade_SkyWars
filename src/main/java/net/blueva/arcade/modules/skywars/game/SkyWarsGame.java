package net.blueva.arcade.modules.skywars.game;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.blueva.arcade.api.config.CoreConfigAPI;
import net.blueva.arcade.api.config.ModuleConfigAPI;
import net.blueva.arcade.api.game.GameContext;
import net.blueva.arcade.api.game.GamePhase;
import net.blueva.arcade.api.module.ModuleInfo;
import net.blueva.arcade.api.stats.StatsAPI;
import net.blueva.arcade.api.store.StoreAPI;
import net.blueva.arcade.api.team.TeamInfo;
import net.blueva.arcade.api.team.TeamsAPI;
import net.blueva.arcade.modules.skywars.state.ArenaState;
import net.blueva.arcade.modules.skywars.support.DescriptionService;
import net.blueva.arcade.modules.skywars.support.PlaceholderService;
import net.blueva.arcade.modules.skywars.support.combat.CombatService;
import net.blueva.arcade.modules.skywars.support.loadout.PlayerLoadoutService;
import net.blueva.arcade.modules.skywars.support.loot.LootService;
import net.blueva.arcade.modules.skywars.support.outcome.OutcomeService;
import net.blueva.arcade.modules.skywars.support.spawn.SpawnCageService;
import net.blueva.arcade.modules.skywars.support.storm.StormService;
import net.blueva.arcade.modules.skywars.support.vote.SkyWarsVoteService;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class SkyWarsGame {

    private static final double CAGE_GUARD_MAX_DISTANCE_SQUARED = 2.25;

    private final ModuleInfo moduleInfo;
    private final ModuleConfigAPI moduleConfig;
    private final CoreConfigAPI coreConfig;
    private final StatsAPI statsAPI;

    private final Map<Integer, ArenaState> arenas = new ConcurrentHashMap<>();
    private final Map<Player, Integer> playerArena = new ConcurrentHashMap<>();

    private final DescriptionService descriptionService;
    private final PlayerLoadoutService loadoutService;
    private final PlaceholderService placeholderService;
    private final OutcomeService outcomeService;
    private final CombatService combatService;
    private final LootService lootService;
    private final StormService stormService;
    private final SpawnCageService spawnCageService;
    private final SkyWarsVoteService voteService;
    private final List<ArenaState.ScheduledEvent> scheduledEvents;

    public SkyWarsGame(ModuleInfo moduleInfo,
                       ModuleConfigAPI moduleConfig,
                       CoreConfigAPI coreConfig,
                       StatsAPI statsAPI,
                       StoreAPI storeAPI,
                       SkyWarsVoteService voteService) {
        this.moduleInfo = moduleInfo;
        this.moduleConfig = moduleConfig;
        this.coreConfig = coreConfig;
        this.statsAPI = statsAPI;
        this.descriptionService = new DescriptionService(moduleConfig);
        this.loadoutService = new PlayerLoadoutService(moduleConfig, storeAPI);
        this.placeholderService = new PlaceholderService(moduleConfig, this);
        this.outcomeService = new OutcomeService(moduleInfo, statsAPI, this, placeholderService);
        this.combatService = new CombatService(moduleConfig, coreConfig, statsAPI, this, loadoutService);
        this.lootService = new LootService(moduleInfo, moduleConfig, statsAPI);
        this.stormService = new StormService(moduleInfo, moduleConfig, statsAPI, this);
        this.spawnCageService = new SpawnCageService(moduleConfig, storeAPI);
        this.voteService = voteService;
        this.scheduledEvents = loadScheduledEvents();
    }

    public void startGame(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        int arenaId = context.getArenaId();

        context.getSchedulerAPI().cancelArenaTasks(arenaId);
        ArenaState state = new ArenaState(context);
        arenas.put(arenaId, state);
        if (voteService != null) {
            state.setVoteState(voteService.createVoteState());
            voteService.applyPendingVotes(state, context.getPlayers());
        }
        state.setTrackedChests(lootService.loadChests(context));
        state.setScheduledEvents(scheduledEvents);

        loadTeamSpawns(context, state);

        TeamsAPI<Player, Material> teamsAPI = context.getTeamsAPI();
        for (Player player : context.getPlayers()) {
            playerArena.put(player, arenaId);
            state.initializePlayer(player.getUniqueId());
            if (teamsAPI != null && teamsAPI.isEnabled() && teamsAPI.getTeam(player) == null) {
                teamsAPI.autoAssignPlayer(player);
            }
        }

        for (Player player : context.getPlayers()) {
            if (player != null && player.isOnline()) {
                teleportToTeamSpawn(context, state, player);
            }
        }

        scheduleSpawnCages(context, state);
        scheduleCageGuard(context, state);
        descriptionService.sendDescription(context);
    }

    private void loadTeamSpawns(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                ArenaState state) {
        if (context.getDataAccess() == null) {
            return;
        }

        TeamsAPI<Player, Material> teamsAPI = context.getTeamsAPI();
        if (teamsAPI == null || !teamsAPI.isEnabled()) {
            return;
        }

        String spawnBase = resolveDataBasePath(context, "team_spawns");
        World arenaWorld = context.getArenaAPI() != null ? context.getArenaAPI().getWorld() : null;

        int teamIndex = 1;
        boolean foundAny = false;
        for (TeamInfo<Player, Material> team : teamsAPI.getTeams()) {
            String teamId = team.getId();
            if (teamId == null || teamId.isBlank()) {
                teamIndex++;
                continue;
            }
            teamId = teamId.toLowerCase();
            String canonicalPath = spawnBase + "." + teamId;
            String numericPath = spawnBase + "." + teamIndex;

            String resolvedPath = null;
            if (context.getDataAccess().hasGameData(canonicalPath)) {
                resolvedPath = canonicalPath;
            } else if (context.getDataAccess().hasGameData(numericPath)) {
                resolvedPath = numericPath;
            }

            if (resolvedPath != null) {
                Location spawn = context.getDataAccess().getGameLocation(resolvedPath);
                if (spawn != null) {
                    if (spawn.getWorld() == null && arenaWorld != null) {
                        spawn = new Location(arenaWorld, spawn.getX(), spawn.getY(), spawn.getZ(), spawn.getYaw(), spawn.getPitch());
                    }
                    state.setTeamSpawn(teamId, spawn);
                    foundAny = true;
                }
            }
            teamIndex++;
        }

        // Adapter: if no team spawns configured, migrate from generic spawns.list to disk
        if (!foundAny && context.getArenaAPI() != null) {
            List<Location> genericSpawns = context.getArenaAPI().getSpawns();
            if (!genericSpawns.isEmpty()) {
                Map<String, Location> migrated = new HashMap<>();
                List<TeamInfo<Player, Material>> orderedTeams = new ArrayList<>(teamsAPI.getTeams());
                for (int i = 0; i < genericSpawns.size(); i++) {
                    Location spawn = genericSpawns.get(i);
                    if (spawn == null) {
                        continue;
                    }
                    if (spawn.getWorld() == null && arenaWorld != null) {
                        spawn = new Location(arenaWorld, spawn.getX(), spawn.getY(), spawn.getZ(), spawn.getYaw(), spawn.getPitch());
                    }
                    String teamId;
                    if (i < orderedTeams.size()) {
                        teamId = orderedTeams.get(i).getId() != null ? orderedTeams.get(i).getId() : String.valueOf(i + 1);
                        if (teamId == null || teamId.isBlank()) {
                            teamId = String.valueOf(i + 1);
                        } else {
                            teamId = teamId.toLowerCase();
                        }
                    } else {
                        teamId = String.valueOf(i + 1);
                    }
                    migrated.put(teamId, spawn);
                    state.setTeamSpawn(teamId, spawn);
                }
                if (!migrated.isEmpty()) {
                    migrateSpawnsToDisk(context.getArenaId(), context.getGameId(), migrated);
                }
            }
        }
    }

    private void migrateSpawnsToDisk(int arenaId, String gameId, Map<String, Location> teamSpawns) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("BlueArcade3");
        if (plugin == null) {
            return;
        }
        File gameFile = new File(plugin.getDataFolder(),
                "data/arenas/" + arenaId + "/games/" + gameId + ".json");
        if (!gameFile.exists()) {
            return;
        }
        try {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            JsonObject root;
            try (FileReader reader = new FileReader(gameFile)) {
                root = gson.fromJson(reader, JsonObject.class);
            }
            if (root == null) {
                return;
            }

            if (!root.has("game")) root.add("game", new JsonObject());
            JsonObject game = root.getAsJsonObject("game");
            if (!game.has("play_area")) game.add("play_area", new JsonObject());
            JsonObject playArea = game.getAsJsonObject("play_area");

            JsonObject teamSpawnsObj = new JsonObject();
            for (Map.Entry<String, Location> entry : teamSpawns.entrySet()) {
                Location loc = entry.getValue();
                JsonObject locObj = new JsonObject();
                locObj.addProperty("x", loc.getX());
                locObj.addProperty("y", loc.getY());
                locObj.addProperty("z", loc.getZ());
                locObj.addProperty("yaw", loc.getYaw());
                locObj.addProperty("pitch", loc.getPitch());
                teamSpawnsObj.add(entry.getKey(), locObj);
            }
            playArea.add("team_spawns", teamSpawnsObj);

            if (root.has("spawns")) {
                JsonObject spawnsSection = root.getAsJsonObject("spawns");
                if (spawnsSection != null) {
                    spawnsSection.remove("list");
                    if (spawnsSection.size() == 0) {
                        root.remove("spawns");
                    }
                }
            }

            try (FileWriter writer = new FileWriter(gameFile)) {
                gson.toJson(root, writer);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("[SkyWars] Failed to migrate spawns for arena " + arenaId + ": " + e.getMessage());
        }
    }

    private void teleportToTeamSpawn(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                     ArenaState state,
                                     Player player) {
        TeamsAPI<Player, Material> teamsAPI = context.getTeamsAPI();
        if (teamsAPI == null || !teamsAPI.isEnabled()) {
            return;
        }

        TeamInfo<Player, Material> team = teamsAPI.getTeam(player);
        if (team == null) {
            return;
        }

        Location spawn = state.getTeamSpawn(team.getId());
        if (spawn == null || spawn.getWorld() == null) {
            return;
        }

        context.getSchedulerAPI().runAtEntity(player, () -> player.teleport(centerSpawnLocation(spawn)));
    }

    private String resolveDataBasePath(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                       String section) {
        if (context.getDataAccess().hasGameData("game.play_area." + section)) {
            return "game.play_area." + section;
        }
        return "game." + section;
    }

    private Location centerSpawnLocation(Location spawn) {
        double centeredX = Math.floor(spawn.getX()) + 0.5;
        double centeredZ = Math.floor(spawn.getZ()) + 0.5;
        return new Location(spawn.getWorld(), centeredX, spawn.getY(), centeredZ, spawn.getYaw(), spawn.getPitch());
    }

    private void scheduleSpawnCages(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                    ArenaState state) {
        int arenaId = context.getArenaId();
        String taskId = "arena_" + arenaId + "_skywars_cages";
        int maxTicks = 40;
        int[] ticks = {0};
        context.getSchedulerAPI().runTimer(taskId, () -> {
            spawnCageService.buildCages(context, state);
            ticks[0]++;
            if (ticks[0] >= maxTicks || state.getCagedPlayerCount() >= context.getPlayers().size()) {
                context.getSchedulerAPI().cancelTask(taskId);
            }
        }, 1L, 1L);
    }

    private void scheduleCageGuard(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                   ArenaState state) {
        int arenaId = context.getArenaId();
        String taskId = "arena_" + arenaId + "_skywars_cage_guard";
        TeamsAPI<Player, Material> teamsAPI = context.getTeamsAPI();
        context.getSchedulerAPI().runTimer(taskId, () -> {
            if (teamsAPI == null || !teamsAPI.isEnabled()) {
                return;
            }
            for (Player player : context.getPlayers()) {
                if (player == null || !player.isOnline()) {
                    continue;
                }
                TeamInfo<Player, Material> team = teamsAPI.getTeam(player);
                if (team == null) {
                    continue;
                }
                Location spawn = state.getTeamSpawn(team.getId());
                if (spawn == null || spawn.getWorld() == null) {
                    continue;
                }
                Location playerLoc = player.getLocation();
                if (playerLoc.getWorld() == null || !playerLoc.getWorld().equals(spawn.getWorld())) {
                    continue;
                }
                if (playerLoc.distanceSquared(spawn) > CAGE_GUARD_MAX_DISTANCE_SQUARED) {
                    context.getSchedulerAPI().runAtEntity(player, () -> player.teleport(centerSpawnLocation(spawn)));
                }
            }
        }, 10L, 10L);
    }

    public void handleCountdownTick(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                    int secondsLeft) {
        for (Player player : context.getPlayers()) {
            if (!player.isOnline()) {
                continue;
            }

            context.getSoundsAPI().play(player, coreConfig.getSound("sounds.starting_game.countdown"));

            String title = coreConfig.getLanguage("titles.starting_game.title")
                    .replace("{game_display_name}", moduleInfo.getName())
                    .replace("{time}", String.valueOf(secondsLeft));

            String subtitle = coreConfig.getLanguage("titles.starting_game.subtitle")
                    .replace("{game_display_name}", moduleInfo.getName())
                    .replace("{time}", String.valueOf(secondsLeft));

            context.getTitlesAPI().sendRaw(player, title, subtitle, 0, 20, 5);
        }
    }

    public void handleCountdownFinish(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        for (Player player : context.getPlayers()) {
            if (!player.isOnline()) {
                continue;
            }

            String title = coreConfig.getLanguage("titles.game_started.title")
                    .replace("{game_display_name}", moduleInfo.getName());

            String subtitle = coreConfig.getLanguage("titles.game_started.subtitle")
                    .replace("{game_display_name}", moduleInfo.getName());

            context.getTitlesAPI().sendRaw(player, title, subtitle, 0, 20, 20);
            context.getSoundsAPI().play(player, coreConfig.getSound("sounds.starting_game.start"));
        }
    }

    public void beginPlaying(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        ArenaState state = getArenaState(context);
        if (state == null) {
            return;
        }

        if (context.getAlivePlayers().isEmpty() && !context.getPlayers().isEmpty()) {
            context.setPlayers(context.getPlayers());
        }

        if (voteService != null) {
            voteService.applyVotes(context, state);
        }

        startGameTimer(context, state);
        lootService.startChestMarkers(context, state);
        lootService.prefillChests(context, state);
        lootService.startChestRefills(context, state);
        context.getSchedulerAPI().cancelTask("arena_" + context.getArenaId() + "_skywars_cage_guard");
        spawnCageService.removeCages(context, state);
        if (voteService != null) {
            voteService.broadcastVoteResults(context, state);
        }

        for (Player player : context.getPlayers()) {
            player.setGameMode(GameMode.SURVIVAL);
            loadoutService.restoreVitals(player);
            loadoutService.giveStartingItems(player);
            loadoutService.applyStartingEffects(player);
            loadoutService.applySelectedKit(player);
            registerFallProtection(state, player);
            context.getScoreboardAPI().showScoreboard(player, getScoreboardPath(context));
        }
    }

    public void finishGame(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        int arenaId = context.getArenaId();
        context.getSchedulerAPI().cancelArenaTasks(arenaId);

        ArenaState state = arenas.remove(arenaId);
        if (state != null) {
            lootService.restoreChests(context, state);
            stormService.clearWorldBorder(context, state);
            spawnCageService.removeCages(context, state);
        }
        resetWorldDefaults(context);
        resetPlayerHearts(context.getPlayers());
        removePlayersFromArena(arenaId, context.getPlayers());

        if (statsAPI != null) {
            for (Player player : context.getPlayers()) {
                statsAPI.addModuleStat(player, moduleInfo.getId(), "games_played", 1);
            }
        }
    }

    public void shutdown() {
        Set<ArenaState> states = Set.copyOf(arenas.values());
        for (ArenaState state : states) {
            state.getContext().getSchedulerAPI().cancelModuleTasks("skywars");
            stormService.clearWorldBorder(state.getContext(), state);
            spawnCageService.removeCages(state.getContext(), state);
            resetWorldDefaults(state.getContext());
            resetPlayerHearts(state.getContext().getPlayers());
        }

        arenas.clear();
        playerArena.clear();
    }

    public Map<String, String> getPlaceholders(Player player) {
        return placeholderService.buildPlaceholders(player);
    }

    public boolean handleVoteCommand(Player player, String[] args) {
        if (voteService == null || player == null) {
            return false;
        }

        GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context = getContext(player);
        ArenaState state = context != null ? getArenaState(context) : null;

        // If no context/state (WAITING ROOM before countdown), open menu with defaults
        if (context == null || state == null) {
            return voteService.handleVoteCommandWithoutContext(player, args);
        }

        // Check game phase - voting only allowed in WAITING and COUNTDOWN
        GamePhase phase = context.getPhase();

        if (phase == GamePhase.PLAYING || phase == GamePhase.ENDING || phase == GamePhase.FINISHED) {
            return false;
        }

        // Game is in COUNTDOWN, voting is allowed
        String[] safeArgs = args != null ? args : new String[0];
        boolean result = voteService.handleVoteCommand(player, context, state, safeArgs);

        return result;
    }

    public GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> getContext(Player player) {
        Integer arenaId = playerArena.get(player);

        // Fallback: search all active arenas if player not yet in playerArena map
        // (happens during countdown before onGameStart when players use vote items)
        if (arenaId == null) {
            for (ArenaState state : arenas.values()) {
                if (state.getContext() != null && state.getContext().getPlayers().contains(player)) {
                    arenaId = state.getContext().getArenaId();
                    // Cache for next time
                    playerArena.put(player, arenaId);
                    break;
                }
            }
        }

        if (arenaId == null) {
            return null;
        }
        ArenaState state = arenas.get(arenaId);
        return state != null ? state.getContext() : null;
    }

    public ArenaState getArenaState(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        if (context == null) {
            return null;
        }
        return arenas.get(context.getArenaId());
    }

    public int getPlayerKills(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                              Player player) {
        ArenaState state = getArenaState(context);
        if (state == null) {
            return 0;
        }
        return state.getKills(player.getUniqueId());
    }

    public void addPlayerKill(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                              Player player) {
        ArenaState state = getArenaState(context);
        if (state == null) {
            return;
        }
        state.addKill(player.getUniqueId());
    }

    public void healKiller(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                           Player killer) {
        loadoutService.handleKillRegeneration(context, killer);
        context.getSoundsAPI().play(killer, coreConfig.getSound("sounds.in_game.respawn"));
    }

    public void handleKill(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                           Player attacker,
                           Player victim) {
        combatService.handleKillCredit(context, attacker);
        combatService.handleElimination(context, victim, attacker);
        checkForTeamVictory(context);
    }

    public void handleNonCombatDeath(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                     Player victim) {
        combatService.handleElimination(context, victim, null);
        checkForTeamVictory(context);
    }

    public void handleChestLoot(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                Player player,
                                org.bukkit.block.Block block) {
        ArenaState state = getArenaState(context);
        if (state == null) {
            return;
        }
        lootService.handleChestLoot(context, state, player, block);
    }

    public void endGame(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        ArenaState state = getArenaState(context);
        if (state == null) {
            return;
        }

        outcomeService.endGame(context, state);
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

    public StatsAPI getStatsAPI() {
        return statsAPI;
    }

    public Map<Player, Integer> getPlayerArena() {
        return playerArena;
    }

    public void removePlayersFromArena(int arenaId, List<Player> players) {
        for (Player player : players) {
            playerArena.remove(player);
        }
    }

    private void resetWorldDefaults(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        if (context == null || context.getArenaAPI() == null) {
            return;
        }
        World world = context.getArenaAPI().getWorld();
        if (world == null) {
            return;
        }
        world.setTime(1000L);
        world.setStorm(false);
        world.setThundering(false);
    }

    private void resetPlayerHearts(List<Player> players) {
        if (players == null) {
            return;
        }
        for (Player player : players) {
            if (player == null) {
                continue;
            }
            Attribute maxHealthAttribute = maxHealthAttribute();
            if (maxHealthAttribute != null && player.getAttribute(maxHealthAttribute) != null) {
                player.getAttribute(maxHealthAttribute).setBaseValue(20.0);
            }
            player.setHealth(Math.min(player.getHealth(), 20.0));
        }
    }

    public String getScoreboardPath(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        return isSoloMode(context) ? "scoreboard.solo" : "scoreboard.default";
    }

    public boolean isSoloMode(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        if (context == null) {
            return true;
        }
        TeamsAPI<Player, Material> teamsAPI = context.getTeamsAPI();
        if (teamsAPI == null || !teamsAPI.isEnabled()) {
            return true;
        }
        if (context.getDataAccess() == null) {
            return false;
        }
        Integer teamSize = context.getDataAccess().getGameData("teams.size", Integer.class);
        Integer teamCount = context.getDataAccess().getGameData("teams.count", Integer.class);
        if (teamSize != null && teamSize <= 1) {
            return true;
        }
        return teamCount != null && teamCount <= 1;
    }

    public List<String> getAliveTeamIds(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        TeamsAPI<Player, Material> teamsAPI = context.getTeamsAPI();
        if (teamsAPI == null || !teamsAPI.isEnabled()) {
            List<String> ids = new ArrayList<>();
            if (!context.getAlivePlayers().isEmpty()) {
                ids.add("solo");
            }
            return ids;
        }

        Set<String> teamIds = new HashSet<>();
        for (Player player : context.getAlivePlayers()) {
            TeamInfo<Player, Material> team = teamsAPI.getTeam(player);
            if (team != null) {
                teamIds.add(team.getId());
            }
        }
        return new ArrayList<>(teamIds);
    }

    public Map<String, Integer> getTeamKills(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        Map<String, Integer> teamKills = new HashMap<>();
        TeamsAPI<Player, Material> teamsAPI = context.getTeamsAPI();
        for (Player player : context.getPlayers()) {
            int kills = getPlayerKills(context, player);
            String teamId = "solo";
            if (teamsAPI != null && teamsAPI.isEnabled()) {
                TeamInfo<Player, Material> team = teamsAPI.getTeam(player);
                if (team != null) {
                    teamId = team.getId();
                }
            }
            teamKills.merge(teamId, kills, Integer::sum);
        }
        return teamKills;
    }

    public List<Player> getTeamPlayers(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                       String teamId) {
        TeamsAPI<Player, Material> teamsAPI = context.getTeamsAPI();
        List<Player> players = new ArrayList<>();
        for (Player player : context.getPlayers()) {
            if (teamsAPI == null || !teamsAPI.isEnabled()) {
                players.add(player);
                continue;
            }
            TeamInfo<Player, Material> team = teamsAPI.getTeam(player);
            if (team != null && team.getId().equalsIgnoreCase(teamId)) {
                players.add(player);
            }
        }
        return players;
    }

    public void checkForTeamVictory(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        ArenaState state = getArenaState(context);
        if (state == null || state.isEnded()) {
            return;
        }

        if (shouldEndForVictory(context)) {
            endGame(context);
        }
    }

    public void tickStorm(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        ArenaState state = getArenaState(context);
        if (state == null || !state.isStormActive()) {
            return;
        }
        stormService.tickStorm(context, state);
    }

    private void startGameTimer(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                ArenaState state) {
        int arenaId = context.getArenaId();
        int fallProtectionSeconds = Math.max(0, moduleConfig.getInt("spawn_protection.fall_damage_seconds", 5));

        int gameTime = moduleConfig.getInt("game.time_limit_seconds", 0);
        boolean hasTimeLimit = gameTime > 0;
        final int[] timeLeft = {gameTime};
        String taskId = "arena_" + arenaId + "_skywars_timer";

        context.getSchedulerAPI().runTimer(taskId, () -> {
            if (state.isEnded()) {
                context.getSchedulerAPI().cancelTask(taskId);
                return;
            }

            state.incrementMatchSeconds();
            handleScheduledEvents(context, state);
            refreshFallProtection(state, context.getPlayers(), fallProtectionSeconds);
            tickStorm(context);

            List<Player> alivePlayers = context.getAlivePlayers();
            List<Player> allPlayers = context.getPlayers();

            if (hasTimeLimit && timeLeft[0] > 0) {
                timeLeft[0]--;
                if (timeLeft[0] <= 0) {
                    endGame(context);
                    return;
                }
            }

            if (shouldEndForVictory(context)) {
                endGame(context);
                return;
            }

            String actionBarTemplate = coreConfig.getLanguage("action_bar.in_game.global");
            for (Player player : allPlayers) {
                if (!player.isOnline()) {
                    continue;
                }

                Map<String, String> customPlaceholders = placeholderService.buildPlaceholders(player);
                if (hasTimeLimit && timeLeft[0] > 0) {
                    customPlaceholders.put("time", String.valueOf(timeLeft[0]));
                }
                customPlaceholders.put("alive", String.valueOf(alivePlayers.size()));
                customPlaceholders.put("spectators", String.valueOf(context.getSpectators().size()));

                if (actionBarTemplate != null && hasTimeLimit) {
                    String actionBarMessage = actionBarTemplate
                            .replace("{time}", String.valueOf(timeLeft[0]))
                            .replace("{round}", String.valueOf(context.getCurrentRound()))
                            .replace("{round_max}", String.valueOf(context.getMaxRounds()));
                    context.getMessagesAPI().sendActionBar(player, actionBarMessage);
                }

                context.getScoreboardAPI().update(player, getScoreboardPath(context), customPlaceholders);
            }
        }, 0L, 20L);
    }

    private boolean shouldEndForVictory(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        TeamsAPI<Player, Material> teamsAPI = context.getTeamsAPI();
        if (teamsAPI != null && teamsAPI.isEnabled()) {
            return getAliveTeamIds(context).size() <= 1;
        }
        return context.getAlivePlayers().size() <= 1;
    }

    private void handleScheduledEvents(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                       ArenaState state) {
        ArenaState.ScheduledEvent event = state.getNextEvent();
        while (event != null && state.getMatchSeconds() >= event.getTriggerSeconds()) {
            triggerEvent(context, state, event);
            state.advanceEvent();
            event = state.getNextEvent();
        }
    }

    private void triggerEvent(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                              ArenaState state,
                              ArenaState.ScheduledEvent event) {
        if (event == null || state == null || context == null) {
            return;
        }

        String type = event.getType();
        if ("CHEST_REFILL".equalsIgnoreCase(type)) {
            lootService.forceRefillChests(context, state);
            broadcastEventMessage(context, "messages.events.chest_refill");
        } else if ("STORM".equalsIgnoreCase(type)) {
            if (!state.isStormActive()) {
                state.setStormActive(true);
                stormService.initializeStorm(context, state);
            }
            broadcastEventMessage(context, "messages.events.storm_started");
        }
    }

    private void broadcastEventMessage(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                       String languagePath) {
        String message = moduleConfig.getStringFrom("language.yml", languagePath);
        if (message == null || message.isBlank()) {
            return;
        }
        for (Player player : context.getPlayers()) {
            if (player == null || !player.isOnline()) {
                continue;
            }
            context.getMessagesAPI().sendRaw(player, message);
        }
    }

    private void registerFallProtection(ArenaState state, Player player) {
        if (state == null || player == null) {
            return;
        }
        int protectionSeconds = Math.max(0, moduleConfig.getInt("spawn_protection.fall_damage_seconds", 5));
        if (protectionSeconds <= 0) {
            return;
        }
        state.setFallProtection(player.getUniqueId(), System.currentTimeMillis() + (protectionSeconds * 1000L));
    }

    private void refreshFallProtection(ArenaState state, List<Player> players, int protectionSeconds) {
        if (state == null || protectionSeconds <= 0) {
            return;
        }
        for (Player player : players) {
            if (player == null) {
                continue;
            }
            if (state.hasFallProtection(player.getUniqueId())) {
                continue;
            }
            if (state.getMatchSeconds() == 1) {
                state.setFallProtection(player.getUniqueId(),
                        System.currentTimeMillis() + (protectionSeconds * 1000L));
            }
        }
    }

    private List<ArenaState.ScheduledEvent> loadScheduledEvents() {
        List<String> entries = moduleConfig.getStringList("events.schedule");
        List<ArenaState.ScheduledEvent> events = new ArrayList<>();
        if (entries == null) {
            return events;
        }
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String[] parts = entry.split(":", 3);
            if (parts.length < 2) {
                continue;
            }
            try {
                int seconds = Integer.parseInt(parts[0].trim());
                String type = parts[1].trim();
                String label = parts.length >= 3 ? parts[2].trim() : type;
                if (seconds < 0 || type.isEmpty()) {
                    continue;
                }
                if ("STORM".equalsIgnoreCase(type)
                        && !moduleConfig.getBoolean("storm.enabled", true)) {
                    continue;
                }
                if ("CHEST_REFILL".equalsIgnoreCase(type)
                        && !moduleConfig.getBoolean("loot.refill.enabled", true)) {
                    continue;
                }
                events.add(new ArenaState.ScheduledEvent(seconds, type, label.isEmpty() ? type : label));
            } catch (NumberFormatException ignored) {
                // Ignore malformed entries
            }
        }
        return events;
    }

    private Attribute maxHealthAttribute() {
        Attribute attribute = attributeConstant("MAX_HEALTH");
        return attribute != null ? attribute : attributeConstant("GENERIC_MAX_HEALTH");
    }

    private Attribute attributeConstant(String fieldName) {
        try {
            Object value = Attribute.class.getField(fieldName).get(null);
            return value instanceof Attribute attribute ? attribute : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }
}
