package net.blueva.arcade.modules.skywars.support.vote;

import net.blueva.arcade.api.ModuleAPI;
import net.blueva.arcade.api.config.ModuleConfigAPI;
import net.blueva.arcade.api.game.GameContext;
import net.blueva.arcade.api.game.GamePhase;
import net.blueva.arcade.api.ui.ItemAPI;
import net.blueva.arcade.api.ui.LobbyItemDefinition;
import net.blueva.arcade.api.ui.MenuAPI;
import net.blueva.arcade.api.ui.MessageAPI;
import net.blueva.arcade.api.utils.PlayerUtil;
import net.blueva.arcade.api.ui.menu.MenuDefinition;
import net.blueva.arcade.modules.skywars.game.SkyWarsGame;
import net.blueva.arcade.modules.skywars.state.ArenaState;
import net.blueva.arcade.modules.skywars.state.VoteState;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SkyWarsVoteService {

    private static final String VOTE_PERMISSION_BASE = "bluearcade.skywars.votes";
    private static final String WAITING_ITEM_ID = "skywars_vote_settings";
    public static final String COMMAND = "skywarsvote";
    public static final String MENU_MAIN = "vote_main";
    public static final String MENU_CHESTS = "vote_chests";
    public static final String MENU_HEARTS = "vote_hearts";
    public static final String MENU_TIME = "vote_time";
    public static final String MENU_WEATHER = "vote_weather";

    private static final Set<String> CHEST_OPTIONS = Set.of("basic", "normal", "overpowered");
    private static final Set<String> HEART_OPTIONS = Set.of("10", "20", "30");
    private static final Set<String> TIME_OPTIONS = Set.of("day", "night", "sunset", "sunrise");
    private static final Set<String> WEATHER_OPTIONS = Set.of("sunny", "rainy");

    private final ModuleConfigAPI moduleConfig;
    private final MenuAPI<Player, Material> menuAPI;
    private final ItemAPI<Player, ItemStack, Material> itemAPI;
    private final String moduleId;
    private final SkyWarsVoteMenuRepository menuRepository;
    private final Map<Integer, VoteState> waitingVoteStates = new ConcurrentHashMap<>();
    private final Map<UUID, Long> voteCooldowns = new ConcurrentHashMap<>();
    private SkyWarsGame game;

    public SkyWarsVoteService(ModuleConfigAPI moduleConfig,
                              MenuAPI<Player, Material> menuAPI,
                              ItemAPI<Player, ItemStack, Material> itemAPI,
                              String moduleId) {
        this.moduleConfig = moduleConfig;
        this.menuAPI = menuAPI;
        this.itemAPI = itemAPI;
        this.moduleId = moduleId;
        this.menuRepository = new SkyWarsVoteMenuRepository(moduleConfig);
        this.menuRepository.loadMenus();
        registerMenusWithCore();
    }

    /**
     * Register menu opener with the core so OPEN actions can find our menus.
     */
    private void registerMenusWithCore() {
        SkyWarsMenuAPI skyWarsMenuAPI = new SkyWarsMenuAPI(this.menuAPI, this);
        menuAPI.registerModuleMenuAPI("skywars", skyWarsMenuAPI);
    }

    public VoteState createVoteState() {
        Map<VoteCategory, String> defaults = new EnumMap<>(VoteCategory.class);
        defaults.put(VoteCategory.CHESTS, normalizeOption(moduleConfig.getString("votes.defaults.chests", "normal"), CHEST_OPTIONS, "normal"));
        defaults.put(VoteCategory.HEARTS, normalizeOption(moduleConfig.getString("votes.defaults.hearts", "10"), HEART_OPTIONS, "10"));
        defaults.put(VoteCategory.TIME, normalizeOption(moduleConfig.getString("votes.defaults.time", "day"), TIME_OPTIONS, "day"));
        defaults.put(VoteCategory.WEATHER, normalizeOption(moduleConfig.getString("votes.defaults.weather", "sunny"), WEATHER_OPTIONS, "sunny"));
        return new VoteState(defaults);
    }

    public VoteState getWaitingVoteState(int arenaId) {
        return waitingVoteStates.computeIfAbsent(arenaId, id -> createVoteState());
    }

    public void clearWaitingVote(int arenaId, UUID playerId) {
        voteCooldowns.remove(playerId);
        VoteState state = waitingVoteStates.get(arenaId);
        if (state == null) {
            return;
        }
        state.clearPlayerVotes(playerId);
        if (state.getVoterIds().isEmpty()) {
            waitingVoteStates.remove(arenaId);
        }
    }

    public void cleanStaleVotes() {
        @SuppressWarnings("unchecked")
        PlayerUtil<Player> playerUtil = (PlayerUtil<Player>) ModuleAPI.getPlayerUtil();
        if (playerUtil == null) {
            return;
        }

        for (Map.Entry<Integer, VoteState> entry : new ArrayList<>(waitingVoteStates.entrySet())) {
            cleanStaleVotesForArena(entry.getValue(), entry.getKey());
            if (entry.getValue().getVoterIds().isEmpty()) {
                waitingVoteStates.remove(entry.getKey());
            }
        }
    }

    private void cleanStaleVotesForArena(VoteState state, int arenaId) {
        @SuppressWarnings("unchecked")
        PlayerUtil<Player> playerUtil = (PlayerUtil<Player>) ModuleAPI.getPlayerUtil();
        if (playerUtil == null || state == null) {
            return;
        }

        for (UUID playerId : new ArrayList<>(state.getVoterIds())) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                state.clearPlayerVotes(playerId);
                continue;
            }
            Integer playerArena = playerUtil.getPlayerArena(player);
            if (playerArena == null || playerArena != arenaId) {
                state.clearPlayerVotes(playerId);
            }
        }
    }

    private Integer getPlayerArenaId(Player player) {
        if (player == null) {
            return null;
        }
        @SuppressWarnings("unchecked")
        PlayerUtil<Player> playerUtil = (PlayerUtil<Player>) ModuleAPI.getPlayerUtil();
        if (playerUtil == null) {
            return null;
        }
        return playerUtil.getPlayerArena(player);
    }

    public void setGame(SkyWarsGame game) {
        this.game = game;
    }

    public void applyPendingVotes(ArenaState state, List<Player> players) {
        if (state == null || players == null || players.isEmpty()) {
            return;
        }
        VoteState voteState = state.getVoteState();
        if (voteState == null) {
            return;
        }

        int arenaId = state.getContext().getArenaId();
        VoteState waiting = getWaitingVoteState(arenaId);
        cleanStaleVotesForArena(waiting, arenaId);

        for (Player player : players) {
            if (player == null) {
                continue;
            }
            for (VoteCategory category : VoteCategory.values()) {
                String option = waiting.getPlayerVote(player.getUniqueId(), category);
                if (option != null) {
                    voteState.castVote(player.getUniqueId(), category, option);
                }
            }
            voteCooldowns.remove(player.getUniqueId());
        }
        waitingVoteStates.remove(arenaId);
    }

    public void registerWaitingItem() {
        if (itemAPI == null || moduleConfig == null) {
            return;
        }

        if (!isWaitingItemEnabled()) {
            unregisterWaitingItem();
            return;
        }

        String materialName = moduleConfig.getString("waiting_items.vote_settings.material", "NETHER_STAR");
        Material material;
        try {
            material = Material.valueOf(materialName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            material = Material.NETHER_STAR;
        }

        int slot = moduleConfig.getInt("waiting_items.vote_settings.slot");
        String displayName = moduleConfig.getString("waiting_items.vote_settings.display_name");
        List<String> lore = moduleConfig.getStringList("waiting_items.vote_settings.lore");

        // Register item with empty actions (we use custom click handler instead)
        LobbyItemDefinition<Material> definition = new LobbyItemDefinition<>(
                WAITING_ITEM_ID,
                material,
                slot,
                displayName,
                lore,
                List.of(), // Empty actions - handled by custom click handler
                true
        );

        itemAPI.registerWaitingItem(moduleId, definition);

    }

    /**
     * Registers click handler for the vote item.
     * Must be called AFTER game instance is created.
     *
     * @param game SkyWarsGame instance
     */
    public void registerClickHandler(SkyWarsGame game) {
        if (itemAPI == null) {
            return;
        }
        if (!isWaitingItemEnabled()) {
            itemAPI.unregisterClickHandler(WAITING_ITEM_ID);
            return;
        }
        itemAPI.registerClickHandler(WAITING_ITEM_ID,
                player -> game.handleVoteCommand(player, new String[]{"menu", "main"}));
    }

    public void unregisterWaitingItem() {
        if (itemAPI == null) {
            return;
        }
        itemAPI.unregisterWaitingItem(WAITING_ITEM_ID);
        itemAPI.unregisterClickHandler(WAITING_ITEM_ID);
    }

    private boolean isWaitingItemEnabled() {
        return moduleConfig != null && moduleConfig.getBoolean("waiting_items.vote_settings.enabled", true);
    }

    public boolean handleVoteCommand(Player player,
                                     GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                     ArenaState state,
                                     String[] args) {
        if (player == null || context == null || state == null) {
            return false;
        }

        GamePhase phase = context.getPhase();
        if (phase == GamePhase.PLAYING || phase == GamePhase.ENDING || phase == GamePhase.FINISHED) {
            sendMessage(context, player, "votes.messages.not_available");
            return true;
        }

        if (args.length == 0) {
            return openMenu(player, state, MENU_MAIN);
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("menu")) {
            String menuId = args.length > 1 ? args[1] : "main";
            return openMenu(player, state, mapMenuId(menuId));
        }

        if (action.equals("vote")) {
            if (args.length < 3) {
                sendMessage(context, player, "votes.messages.invalid");
                return true;
            }

            VoteCategory category = VoteCategory.fromId(args[1]);
            String option = args[2].toLowerCase(Locale.ROOT);
            if (category == null || !isOptionValid(category, option)) {
                sendMessage(context, player, "votes.messages.invalid");
                return true;
            }
            if (!hasVotePermission(player, category, option)) {
                sendMessage(context, player, "votes.messages.no_permission", category, option);
                return true;
            }

            VoteState voteState = state.getVoteState();
            if (voteState == null) {
                return true;
            }

            long cooldownRemaining = getRemainingVoteCooldownSeconds(player.getUniqueId());
            if (cooldownRemaining > 0) {
                String message = moduleConfig.getTranslation(player, "votes.messages.cooldown");
                if (message != null && !message.isBlank()) {
                    context.getMessagesAPI().sendRaw(player,
                            message.replace("{time}", String.valueOf(cooldownRemaining)));
                }
                return true;
            }

            voteState.castVote(player.getUniqueId(), category, option);
            voteCooldowns.put(player.getUniqueId(), System.currentTimeMillis());
            String message = voteBroadcastMessage(player, category, option, voteState);
            if (message.isBlank()) {
                return true;
            }
            broadcastMessage(context, message);
            return true;
        }

        return openMenu(player, state, MENU_MAIN);
    }

    public boolean handleVoteCommandWithoutContext(Player player, String[] args) {
        if (player == null) {
            return false;
        }

        Integer arenaId = getPlayerArenaId(player);
        if (arenaId == null) {
            return true;
        }

        VoteState waiting = getWaitingVoteState(arenaId);
        cleanStaleVotesForArena(waiting, arenaId);

        String[] safeArgs = args != null ? args : new String[0];
        if (safeArgs.length == 0) {
            return openMenuWithDefaults(player, waiting, safeArgs);
        }

        String action = safeArgs[0].toLowerCase(Locale.ROOT);
        if (action.equals("menu")) {
            return openMenuWithDefaults(player, waiting, safeArgs);
        }

        if (action.equals("vote")) {
            if (safeArgs.length < 3) {
                sendWaitingMessage(player, "votes.messages.invalid");
                return true;
            }

            VoteCategory category = VoteCategory.fromId(safeArgs[1]);
            String option = safeArgs[2].toLowerCase(Locale.ROOT);
            if (category == null || !isOptionValid(category, option)) {
                sendWaitingMessage(player, "votes.messages.invalid");
                return true;
            }
            if (!hasVotePermission(player, category, option)) {
                sendWaitingMessage(player, "votes.messages.no_permission", category, option);
                return true;
            }

            long cooldownRemaining = getRemainingVoteCooldownSeconds(player.getUniqueId());
            if (cooldownRemaining > 0) {
                String message = moduleConfig.getTranslation(player, "votes.messages.cooldown");
                if (message != null && !message.isBlank()) {
                    sendWaitingBroadcast(player, message.replace("{time}", String.valueOf(cooldownRemaining)));
                }
                return openMenuWithDefaults(player, waiting, new String[]{"menu", "main"});
            }

            waiting.castVote(player.getUniqueId(), category, option);
            voteCooldowns.put(player.getUniqueId(), System.currentTimeMillis());
            broadcastWaitingVote(player, category, option, waiting);
            return openMenuWithDefaults(player, waiting, new String[]{"menu", "main"});
        }

        return false;
    }

    public void applyVotes(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                           ArenaState state) {
        if (context == null || state == null) {
            return;
        }

        VoteState voteState = state.getVoteState();
        if (voteState == null) {
            return;
        }

        String chestTier = voteState.resolveWinner(VoteCategory.CHESTS);
        String hearts = voteState.resolveWinner(VoteCategory.HEARTS);
        String time = voteState.resolveWinner(VoteCategory.TIME);
        String weather = voteState.resolveWinner(VoteCategory.WEATHER);

        state.setSelectedChestTier(chestTier);
        state.setSelectedHearts(resolveHearts(hearts));
        state.setSelectedTime(time);
        state.setSelectedWeather(weather);

        applyHearts(context, state.getSelectedHearts());
        applyWorldTime(context, time);
        applyWeather(context, weather);
    }

    public void broadcastVoteResults(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                     ArenaState state) {
        if (context == null || state == null) {
            return;
        }
        VoteState voteState = state.getVoteState();
        if (voteState == null) {
            return;
        }

        broadcastResultForCategory(context, voteState, VoteCategory.CHESTS, "votes.messages.selected.chests");
        broadcastResultForCategory(context, voteState, VoteCategory.HEARTS, "votes.messages.selected.hearts");
        broadcastResultForCategory(context, voteState, VoteCategory.TIME, "votes.messages.selected.time");
        broadcastResultForCategory(context, voteState, VoteCategory.WEATHER, "votes.messages.selected.weather");
    }

    /**
     * Opens vote menu when player is in waiting room (before countdown).
     * Uses default values since there's no active game state yet.
     *
     * @param player Player to open menu for
     * @param args Command arguments (menu, main, etc)
     * @return true if menu was opened
     */
    public boolean openMenuWithDefaults(Player player, String[] args) {
        Integer arenaId = getPlayerArenaId(player);
        if (arenaId == null) {
            return openMenu(player, createVoteState(), resolveMenuId(args));
        }
        VoteState waiting = getWaitingVoteState(arenaId);
        cleanStaleVotesForArena(waiting, arenaId);
        return openMenu(player, waiting, resolveMenuId(args));
    }

    public boolean openMenuWithDefaults(Player player, VoteState voteState, String[] args) {
        if (menuAPI == null || player == null) {
            return false;
        }
        return openMenu(player, voteState, resolveMenuId(args));
    }

    private String resolveMenuId(String[] args) {
        String menuId = MENU_MAIN;
        if (args != null && args.length > 0) {
            if (args[0].equalsIgnoreCase("menu") && args.length > 1) {
                menuId = mapMenuId(args[1]);
            }
        }
        return menuId;
    }

    public boolean openMenu(Player player, ArenaState state, String menuId) {
        VoteState voteState = state != null ? state.getVoteState() : null;
        return openMenu(player, voteState, menuId);
    }

    public boolean openMenu(Player player, VoteState voteState, String menuId) {
        if (menuAPI == null) {
            return false;
        }

        MenuDefinition<Material> menu = menuRepository.getMenu(menuId);
        if (menu == null) {
            return false;
        }

        Map<String, String> placeholders = buildPlaceholders(player, voteState);
        return menuAPI.openMenu(player, menu, placeholders);
    }

    public Map<String, String> buildPlaceholders(Player player, VoteState voteState) {
        Map<String, String> placeholders = new java.util.HashMap<>();

        placeholders.put("{selected_chests}", resolveWinningLabel(voteState, VoteCategory.CHESTS, CHEST_OPTIONS));
        placeholders.put("{selected_hearts}", resolveWinningLabel(voteState, VoteCategory.HEARTS, HEART_OPTIONS));
        placeholders.put("{selected_time}", resolveWinningLabel(voteState, VoteCategory.TIME, TIME_OPTIONS));
        placeholders.put("{selected_weather}", resolveWinningLabel(voteState, VoteCategory.WEATHER, WEATHER_OPTIONS));

        placeholders.put("{player_vote_chests}", resolvePlayerVoteLabel(player, voteState, VoteCategory.CHESTS));
        placeholders.put("{player_vote_hearts}", resolvePlayerVoteLabel(player, voteState, VoteCategory.HEARTS));
        placeholders.put("{player_vote_time}", resolvePlayerVoteLabel(player, voteState, VoteCategory.TIME));
        placeholders.put("{player_vote_weather}", resolvePlayerVoteLabel(player, voteState, VoteCategory.WEATHER));

        for (String option : CHEST_OPTIONS) {
            placeholders.put("{votes_chests_" + option + "}", String.valueOf(voteState != null
                    ? voteState.getVotes(VoteCategory.CHESTS, option)
                    : 0));
        }
        for (String option : HEART_OPTIONS) {
            placeholders.put("{votes_hearts_" + option + "}", String.valueOf(voteState != null
                    ? voteState.getVotes(VoteCategory.HEARTS, option)
                    : 0));
        }
        for (String option : TIME_OPTIONS) {
            placeholders.put("{votes_time_" + option + "}", String.valueOf(voteState != null
                    ? voteState.getVotes(VoteCategory.TIME, option)
                    : 0));
        }
        for (String option : WEATHER_OPTIONS) {
            placeholders.put("{votes_weather_" + option + "}", String.valueOf(voteState != null
                    ? voteState.getVotes(VoteCategory.WEATHER, option)
                    : 0));
        }

        return placeholders;
    }

    private void sendMessage(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                             Player player,
                             String path) {
        sendMessage(context, player, path, null, null);
    }

    private void sendMessage(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                             Player player,
                             String path,
                             VoteCategory category,
                             String option) {
        String message = moduleConfig.getTranslation(player, path);
        if (message == null || message.isBlank()) {
            return;
        }
        if (category != null) {
            message = message.replace("{category}", getCategoryLabel(category));
        }
        if (option != null) {
            message = message.replace("{option}", getOptionLabel(category, option));
        }
        context.getMessagesAPI().sendRaw(player, message);
    }

    private void sendWaitingMessage(Player player, String path) {
        sendWaitingMessage(player, path, null, null);
    }

    private void sendWaitingMessage(Player player, String path, VoteCategory category, String option) {
        if (player == null) {
            return;
        }
        String message = moduleConfig.getTranslation(player, path);
        if (message == null || message.isBlank()) {
            return;
        }
        if (category != null) {
            message = message.replace("{category}", getCategoryLabel(category));
        }
        if (option != null) {
            message = message.replace("{option}", getOptionLabel(category, option));
        }
        @SuppressWarnings("unchecked")
        MessageAPI<Player> messagesAPI = (MessageAPI<Player>) ModuleAPI.getMessagesAPI();
        if (messagesAPI != null) {
            messagesAPI.sendRaw(player, message);
            return;
        }
        player.sendMessage(message);
    }

    private void broadcastMessage(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                  String message) {
        if (context == null || message == null || message.isBlank()) {
            return;
        }
        MessageAPI<Player> messagesAPI = context.getMessagesAPI();
        for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (!context.isPlayerPlaying(player)) {
                continue;
            }
            if (messagesAPI != null) {
                messagesAPI.sendRaw(player, message);
            } else {
                player.sendMessage(message);
            }
        }
    }

    private void broadcastWaitingVote(Player player, VoteCategory category, String option, VoteState voteState) {
        if (player == null || category == null || option == null) {
            return;
        }
        String message = voteBroadcastMessage(player, category, option, voteState);
        if (message.isBlank()) {
            return;
        }

        GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context = getGameContext(player);
        if (context == null) {
            if (isPlayerInWaitingArena(player)) {
                broadcastToWaitingArena(player, message);
            }
            return;
        }
        broadcastMessage(context, message);
    }

    private GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> getGameContext(Player player) {
        if (game == null || player == null) {
            return null;
        }
        return game.getContext(player);
    }

    public boolean isPlayerInWaitingArena(Player player) {
        @SuppressWarnings("unchecked")
        PlayerUtil<Player> playerUtil = (PlayerUtil<Player>) ModuleAPI.getPlayerUtil();
        if (playerUtil == null) {
            return false;
        }
        return playerUtil.isInWaitingArena(player);
    }

    private void sendWaitingBroadcast(Player player, String message) {
        if (player == null || message == null || message.isBlank()) {
            return;
        }
        @SuppressWarnings("unchecked")
        MessageAPI<Player> messagesAPI = (MessageAPI<Player>) ModuleAPI.getMessagesAPI();
        if (messagesAPI != null) {
            messagesAPI.sendRaw(player, message);
        } else {
            player.sendMessage(message);
        }
    }

    private void broadcastToWaitingArena(Player sender, String message) {
        if (sender == null || message == null || message.isBlank()) {
            return;
        }
        @SuppressWarnings("unchecked")
        PlayerUtil<Player> playerUtil = (PlayerUtil<Player>) ModuleAPI.getPlayerUtil();
        if (playerUtil == null) {
            return;
        }
        Integer senderArenaId = playerUtil.getPlayerArena(sender);
        if (senderArenaId == null) {
            return;
        }
        @SuppressWarnings("unchecked")
        MessageAPI<Player> messagesAPI = (MessageAPI<Player>) ModuleAPI.getMessagesAPI();
        for (Player online : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (online == null || !online.isOnline()) {
                continue;
            }
            Integer onlineArenaId = playerUtil.getPlayerArena(online);
            if (!senderArenaId.equals(onlineArenaId)) {
                continue;
            }
            if (messagesAPI != null) {
                messagesAPI.sendRaw(online, message);
            } else {
                online.sendMessage(message);
            }
        }
    }

    private void broadcastResultForCategory(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                            VoteState voteState,
                                            VoteCategory category,
                                            String messagePath) {
        if (context == null || voteState == null || category == null) {
            return;
        }
        String option = voteState.resolveWinner(category);
        String optionLabel = getOptionLabel(category, option);
        String optionDisplay = optionLabel != null ? optionLabel.toUpperCase(Locale.ROOT) : "";
        String sourceKey = voteState.hasVotes(category)
                ? "votes.messages.selected.sources.popular"
                : "votes.messages.selected.sources.default";
        String source = moduleConfig.getTranslation(null, sourceKey);
        String message = moduleConfig.getTranslation(null, messagePath);
        if (message == null || message.isBlank()) {
            return;
        }
        message = message.replace("{option}", optionDisplay)
                .replace("{source}", source == null ? "" : source);
        broadcastMessage(context, message);
    }

    private String mapMenuId(String menuId) {
        if (menuId == null) {
            return MENU_MAIN;
        }

        return switch (menuId.toLowerCase(Locale.ROOT)) {
            case "chests" -> MENU_CHESTS;
            case "hearts" -> MENU_HEARTS;
            case "time" -> MENU_TIME;
            case "weather" -> MENU_WEATHER;
            default -> MENU_MAIN;
        };
    }

    private boolean isOptionValid(VoteCategory category, String option) {
        return switch (category) {
            case CHESTS -> CHEST_OPTIONS.contains(option);
            case HEARTS -> HEART_OPTIONS.contains(option);
            case TIME -> TIME_OPTIONS.contains(option);
            case WEATHER -> WEATHER_OPTIONS.contains(option);
        };
    }

    private long getVoteCooldownMillis() {
        if (moduleConfig == null) {
            return 0;
        }
        int seconds = moduleConfig.getInt("votes.cooldown_seconds", 5);
        return seconds <= 0 ? 0 : seconds * 1000L;
    }

    private long getRemainingVoteCooldownSeconds(UUID playerId) {
        if (playerId == null) {
            return 0;
        }
        long cooldownMillis = getVoteCooldownMillis();
        if (cooldownMillis <= 0) {
            return 0;
        }
        Long lastVote = voteCooldowns.get(playerId);
        if (lastVote == null) {
            return 0;
        }
        long remainingMillis = cooldownMillis - (System.currentTimeMillis() - lastVote);
        if (remainingMillis <= 0) {
            return 0;
        }
        return (remainingMillis + 999) / 1000;
    }

    private boolean hasVotePermission(Player player, VoteCategory category, String option) {
        if (player == null || category == null || option == null) {
            return false;
        }

        if (player.hasPermission(VOTE_PERMISSION_BASE + ".*")) {
            return true;
        }

        String optionKey = mapPermissionOption(category, option);
        String categoryId = category.getId();
        if (player.hasPermission(VOTE_PERMISSION_BASE + "." + categoryId + ".*")
                || player.hasPermission(VOTE_PERMISSION_BASE + "." + categoryId + "." + optionKey)) {
            return true;
        }

        if (category == VoteCategory.CHESTS) {
            String chestCategory = "chest";
            if (player.hasPermission(VOTE_PERMISSION_BASE + "." + chestCategory + ".*")
                    || player.hasPermission(VOTE_PERMISSION_BASE + "." + chestCategory + "." + optionKey)) {
                return true;
            }
        }

        return false;
    }

    private String mapPermissionOption(VoteCategory category, String option) {
        String normalized = option.toLowerCase(Locale.ROOT);
        if (category == VoteCategory.CHESTS && "overpowered".equals(normalized)) {
            return "op";
        }
        return normalized;
    }

    private String normalizeOption(String value, Set<String> options, String fallback) {
        String normalized = value == null ? fallback : value.trim().toLowerCase(Locale.ROOT);
        return options.contains(normalized) ? normalized : fallback;
    }

    private int resolveHearts(String hearts) {
        return switch (hearts == null ? "" : hearts) {
            case "20" -> 20;
            case "30" -> 30;
            default -> 10;
        };
    }

    private void applyHearts(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context, int hearts) {
        double maxHealth = Math.max(2.0, hearts * 2.0);
        Attribute maxHealthAttribute = maxHealthAttribute();
        for (Player player : context.getPlayers()) {
            if (player == null) {
                continue;
            }
            if (maxHealthAttribute != null && player.getAttribute(maxHealthAttribute) != null) {
                player.getAttribute(maxHealthAttribute).setBaseValue(maxHealth);
            }
            player.setHealth(Math.min(player.getHealth(), maxHealth));
        }
    }

    private void applyWorldTime(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context, String time) {
        World world = context.getArenaAPI() != null ? context.getArenaAPI().getWorld() : null;
        if (world == null || time == null) {
            return;
        }

        long ticks = switch (time) {
            case "night" -> 13000L;
            case "sunset" -> 12000L;
            case "sunrise" -> 23000L;
            default -> 1000L;
        };

        world.setTime(ticks);
    }

    private void applyWeather(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context, String weather) {
        World world = context.getArenaAPI() != null ? context.getArenaAPI().getWorld() : null;
        if (world == null || weather == null) {
            return;
        }

        boolean rainy = "rainy".equalsIgnoreCase(weather);
        world.setStorm(rainy);
        world.setThundering(false);
    }

    private String resolveWinningLabel(VoteState voteState, VoteCategory category, Set<String> options) {
        String resolved = voteState != null ? voteState.resolveWinner(category) : null;
        if (resolved == null || !options.contains(resolved)) {
            resolved = defaultOption(category);
        }
        return getOptionLabel(category, resolved);
    }

    private String resolvePlayerVoteLabel(Player player, VoteState voteState, VoteCategory category) {
        if (player == null || voteState == null) {
            return getOptionLabel(category, defaultOption(category));
        }
        String vote = voteState.getPlayerVote(player.getUniqueId(), category);
        if (vote == null) {
            vote = voteState.resolveWinner(category);
        }
        if (vote == null) {
            vote = defaultOption(category);
        }
        String label = getOptionLabel(category, vote);
        return label == null || label.isBlank() ? "-" : label;
    }

    private String defaultOption(VoteCategory category) {
        return switch (category) {
            case CHESTS -> normalizeOption(moduleConfig.getString("votes.defaults.chests", "normal"), CHEST_OPTIONS, "normal");
            case HEARTS -> normalizeOption(moduleConfig.getString("votes.defaults.hearts", "10"), HEART_OPTIONS, "10");
            case TIME -> normalizeOption(moduleConfig.getString("votes.defaults.time", "day"), TIME_OPTIONS, "day");
            case WEATHER -> normalizeOption(moduleConfig.getString("votes.defaults.weather", "sunny"), WEATHER_OPTIONS, "sunny");
        };
    }

    private String voteBroadcastMessage(Player player, VoteCategory category, String option, VoteState voteState) {
        String message = moduleConfig.getTranslation(player, "votes.messages.broadcast");
        if (message == null || message.isBlank()) {
            return "";
        }
        int voteCount = voteState != null ? voteState.getVotes(category, option) : 0;
        return message.replace("{player}", player.getName())
                .replace("{category}", getCategoryLabel(category))
                .replace("{option}", getOptionLabel(category, option))
                .replace("{votes}", String.valueOf(voteCount));
    }

    private String getCategoryLabel(VoteCategory category) {
        if (category == null) {
            return "";
        }
        String label = moduleConfig.getTranslation(null, "votes.labels.categories." + category.getId());
        return label == null ? "" : label;
    }

    private String getOptionLabel(VoteCategory category, String option) {
        if (category == null || option == null) {
            return "";
        }
        String label = moduleConfig.getTranslation(null, "votes.labels.options." + category.getId() + "." + option);
        return label == null ? "" : label;
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
