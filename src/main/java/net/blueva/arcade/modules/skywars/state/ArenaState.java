package net.blueva.arcade.modules.skywars.state;

import net.blueva.arcade.api.game.GameContext;
import net.blueva.arcade.modules.skywars.support.loot.TrackedChest;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ArenaState {

    private final GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context;
    private final Map<UUID, Integer> playerKills = new ConcurrentHashMap<>();
    private final Map<String, Long> chestRefillTimes = new ConcurrentHashMap<>();
    private final Set<String> trackedChestKeys = ConcurrentHashMap.newKeySet();
    private final Map<String, Material> cageBlocks = new ConcurrentHashMap<>();
    private final Set<UUID> cagedPlayers = ConcurrentHashMap.newKeySet();
    private final Set<String> cagedSpawnKeys = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> fallProtectionUntil = new ConcurrentHashMap<>();
    private final Map<String, Location> teamSpawns = new ConcurrentHashMap<>();
    private List<TrackedChest> trackedChests = new ArrayList<>();
    private List<ScheduledEvent> scheduledEvents = new ArrayList<>();
    private int nextEventIndex;

    private UUID winnerId;
    private boolean ended;
    private int supplyTicks;

    private double stormRadius;
    private double stormMaxRadius;
    private double stormFinalRadius;
    private double stormDamagePerSecond;
    private int stormShrinkDurationSeconds;
    private Location stormCenter;
    private int stormLightningTicks;
    private boolean stormActive;
    private int matchSeconds;

    private VoteState voteState;
    private String selectedChestTier = "normal";
    private int selectedHearts = 10;
    private String selectedTime = "day";
    private String selectedWeather = "sunny";

    private WorldBorder stormBorder;

    public ArenaState(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        this.context = context;
    }

    public GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> getContext() {
        return context;
    }

    public int getArenaId() {
        return context.getArenaId();
    }

    public void initializePlayer(UUID playerId) {
        playerKills.putIfAbsent(playerId, 0);
    }

    public int addKill(UUID playerId) {
        return playerKills.merge(playerId, 1, Integer::sum);
    }

    public int getKills(UUID playerId) {
        return playerKills.getOrDefault(playerId, 0);
    }

    public Map<UUID, Integer> getKillSnapshot() {
        return new ConcurrentHashMap<>(playerKills);
    }

    public boolean markEnded() {
        boolean wasEnded = ended;
        ended = true;
        return wasEnded;
    }

    public boolean isEnded() {
        return ended;
    }

    public void setWinner(UUID winnerId) {
        this.winnerId = winnerId;
    }

    public UUID getWinnerId() {
        return winnerId;
    }

    public int incrementSupplyTicks(int increment) {
        supplyTicks += increment;
        return supplyTicks;
    }

    public void resetSupplyTicks() {
        supplyTicks = 0;
    }

    public boolean markChestRefill(Location location, long nextRefillAt) {
        if (location == null) {
            return false;
        }
        chestRefillTimes.put(toKey(location), nextRefillAt);
        return true;
    }

    public boolean shouldRefillChest(Location location, long now) {
        if (location == null) {
            return false;
        }
        Long nextRefill = chestRefillTimes.get(toKey(location));
        return nextRefill == null || now >= nextRefill;
    }

    public void setTrackedChests(List<TrackedChest> trackedChests) {
        if (trackedChests == null) {
            this.trackedChests = new ArrayList<>();
            trackedChestKeys.clear();
            return;
        }
        this.trackedChests = new ArrayList<>(trackedChests);
        trackedChestKeys.clear();
        for (TrackedChest chest : trackedChests) {
            if (chest != null && chest.getLocation() != null) {
                trackedChestKeys.add(toKey(chest.getLocation()));
            }
        }
    }

    public List<TrackedChest> getTrackedChests() {
        return List.copyOf(trackedChests);
    }

    public boolean isTrackedChest(Location location) {
        if (location == null) {
            return false;
        }
        return trackedChestKeys.contains(toKey(location));
    }

    public Map<String, Long> getChestRefillTimes() {
        return new ConcurrentHashMap<>(chestRefillTimes);
    }

    public void setStormCenter(Location stormCenter) {
        this.stormCenter = stormCenter;
    }

    public Location getStormCenter() {
        return stormCenter;
    }

    public double getStormRadius() {
        return stormRadius;
    }

    public void setStormRadius(double stormRadius) {
        this.stormRadius = stormRadius;
    }

    public double getStormMaxRadius() {
        return stormMaxRadius;
    }

    public void setStormMaxRadius(double stormMaxRadius) {
        this.stormMaxRadius = stormMaxRadius;
    }

    public double getStormFinalRadius() {
        return stormFinalRadius;
    }

    public void setStormFinalRadius(double stormFinalRadius) {
        this.stormFinalRadius = stormFinalRadius;
    }

    public double getStormDamagePerSecond() {
        return stormDamagePerSecond;
    }

    public void setStormDamagePerSecond(double stormDamagePerSecond) {
        this.stormDamagePerSecond = stormDamagePerSecond;
    }

    public int getStormShrinkDurationSeconds() {
        return stormShrinkDurationSeconds;
    }

    public void setStormShrinkDurationSeconds(int stormShrinkDurationSeconds) {
        this.stormShrinkDurationSeconds = stormShrinkDurationSeconds;
    }

    public int incrementStormLightningTicks() {
        return ++stormLightningTicks;
    }

    public void resetStormLightningTicks() {
        stormLightningTicks = 0;
    }

    public boolean isStormActive() {
        return stormActive;
    }

    public void setStormActive(boolean stormActive) {
        this.stormActive = stormActive;
    }

    public int incrementMatchSeconds() {
        return ++matchSeconds;
    }

    public int getMatchSeconds() {
        return matchSeconds;
    }

    public VoteState getVoteState() {
        return voteState;
    }

    public void setVoteState(VoteState voteState) {
        this.voteState = voteState;
    }

    public String getSelectedChestTier() {
        return selectedChestTier;
    }

    public void setSelectedChestTier(String selectedChestTier) {
        if (selectedChestTier != null) {
            this.selectedChestTier = selectedChestTier;
        }
    }

    public int getSelectedHearts() {
        return selectedHearts;
    }

    public void setSelectedHearts(int selectedHearts) {
        if (selectedHearts > 0) {
            this.selectedHearts = selectedHearts;
        }
    }

    public String getSelectedTime() {
        return selectedTime;
    }

    public void setSelectedTime(String selectedTime) {
        if (selectedTime != null) {
            this.selectedTime = selectedTime;
        }
    }

    public String getSelectedWeather() {
        return selectedWeather;
    }

    public void setSelectedWeather(String selectedWeather) {
        if (selectedWeather != null) {
            this.selectedWeather = selectedWeather;
        }
    }

    public void setFallProtection(UUID playerId, long untilMillis) {
        if (playerId == null) {
            return;
        }
        fallProtectionUntil.put(playerId, untilMillis);
    }

    public boolean hasFallProtection(UUID playerId) {
        if (playerId == null) {
            return false;
        }
        Long until = fallProtectionUntil.get(playerId);
        return until != null && System.currentTimeMillis() <= until;
    }

    public void setScheduledEvents(List<ScheduledEvent> events) {
        if (events == null) {
            scheduledEvents = new ArrayList<>();
            nextEventIndex = 0;
            return;
        }
        scheduledEvents = new ArrayList<>(events);
        scheduledEvents.sort(Comparator.comparingInt(ScheduledEvent::getTriggerSeconds));
        nextEventIndex = 0;
    }

    public ScheduledEvent getNextEvent() {
        if (nextEventIndex < 0 || nextEventIndex >= scheduledEvents.size()) {
            return null;
        }
        return scheduledEvents.get(nextEventIndex);
    }

    public void advanceEvent() {
        nextEventIndex++;
    }

    public int getSecondsUntilNextEvent() {
        ScheduledEvent event = getNextEvent();
        if (event == null) {
            return -1;
        }
        return event.getTriggerSeconds() - matchSeconds;
    }

    public WorldBorder getStormBorder() {
        return stormBorder;
    }

    public void setStormBorder(WorldBorder stormBorder) {
        this.stormBorder = stormBorder;
    }

    public void trackCageBlock(Location location, Material previousMaterial) {
        if (location == null || previousMaterial == null) {
            return;
        }
        cageBlocks.put(toKey(location), previousMaterial);
    }

    public Map<String, Material> getCageBlocks() {
        return new ConcurrentHashMap<>(cageBlocks);
    }

    public void clearCageBlocks() {
        cageBlocks.clear();
    }

    public boolean hasCage(UUID playerId) {
        return playerId != null && cagedPlayers.contains(playerId);
    }

    public void markCageBuilt(UUID playerId) {
        if (playerId != null) {
            cagedPlayers.add(playerId);
        }
    }

    public int getCagedPlayerCount() {
        return cagedPlayers.size();
    }

    public void clearCagedPlayers() {
        cagedPlayers.clear();
    }

    public boolean isSpawnCaged(Location location) {
        return location != null && cagedSpawnKeys.contains(toKey(location));
    }

    public void markSpawnCaged(Location location) {
        if (location != null) {
            cagedSpawnKeys.add(toKey(location));
        }
    }

    public void clearCagedSpawns() {
        cagedSpawnKeys.clear();
    }

    private String toKey(Location location) {
        return location.getWorld().getName() + ":" +
                location.getBlockX() + ":" +
                location.getBlockY() + ":" +
                location.getBlockZ();
    }

    public static class ScheduledEvent {
        private final int triggerSeconds;
        private final String type;
        private final String label;

        public ScheduledEvent(int triggerSeconds, String type, String label) {
            this.triggerSeconds = triggerSeconds;
            this.type = type;
            this.label = label;
        }

        public int getTriggerSeconds() {
            return triggerSeconds;
        }

        public String getType() {
            return type;
        }

        public String getLabel() {
            return label;
        }
    }

    public void setTeamSpawn(String teamId, Location location) {
        if (teamId != null) {
            teamSpawns.put(teamId.toLowerCase(), location);
        }
    }

    public Location getTeamSpawn(String teamId) {
        if (teamId == null) {
            return null;
        }
        return teamSpawns.get(teamId.toLowerCase());
    }

    public Map<String, Location> getTeamSpawns() {
        return new ConcurrentHashMap<>(teamSpawns);
    }
}
