package net.blueva.arcade.modules.skywars.support.spawn;

import net.blueva.arcade.api.config.ModuleConfigAPI;
import net.blueva.arcade.api.game.GameContext;
import net.blueva.arcade.api.store.StoreAPI;
import net.blueva.arcade.api.team.TeamInfo;
import net.blueva.arcade.api.team.TeamsAPI;
import net.blueva.arcade.modules.skywars.state.ArenaState;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SpawnCageService {

    private final ModuleConfigAPI moduleConfig;
    private final StoreAPI storeAPI;

    public SpawnCageService(ModuleConfigAPI moduleConfig, StoreAPI storeAPI) {
        this.moduleConfig = moduleConfig;
        this.storeAPI = storeAPI;
    }

    public void buildCages(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                           ArenaState state) {
        if (context == null || state == null) {
            return;
        }

        List<Player> players = context.getPlayers();
        if (players.isEmpty()) {
            return;
        }

        int interiorRadius = 0;
        int wallOffset = interiorRadius + 1;
        int floorOffset = -1;
        int roofOffset = 3;
        int wallTopOffset = 2;
        Map<String, Location> teamSpawnMap = state.getTeamSpawns();
        List<Location> spawns = !teamSpawnMap.isEmpty()
                ? new java.util.ArrayList<>(teamSpawnMap.values())
                : (context.getArenaAPI() != null ? context.getArenaAPI().getSpawns() : List.of());

        for (Player player : players) {
            if (player == null || !player.isOnline() || state.hasCage(player.getUniqueId())) {
                continue;
            }
            Location spawn = resolveSpawnForPlayer(player, spawns, state, context);
            if (spawn == null) {
                continue;
            }
            CageDefinition cage = resolveCageDefinition(player);
            spawn = player.getLocation();
            if (spawn == null || spawn.getWorld() == null) {
                continue;
            }
            if (context.getArenaAPI() != null && !context.getArenaAPI().isInBounds(spawn)) {
                continue;
            }

            int baseX = spawn.getBlockX();
            int baseY = spawn.getBlockY();
            int baseZ = spawn.getBlockZ();

            for (int x = -interiorRadius; x <= interiorRadius; x++) {
                for (int z = -interiorRadius; z <= interiorRadius; z++) {
                    placeBlock(context, state, cage.material(), true,
                            new Location(spawn.getWorld(), baseX + x, baseY + floorOffset, baseZ + z));
                }
            }

            for (int y = 0; y <= wallTopOffset; y++) {
                if (cage.headClear() && y == 1) {
                    continue;
                }
                for (int x = -interiorRadius; x <= interiorRadius; x++) {
                    placeBlock(context, state, cage.material(), true,
                            new Location(spawn.getWorld(), baseX + x, baseY + y, baseZ + wallOffset));
                    placeBlock(context, state, cage.material(), true,
                            new Location(spawn.getWorld(), baseX + x, baseY + y, baseZ - wallOffset));
                }
                for (int z = -interiorRadius; z <= interiorRadius; z++) {
                    placeBlock(context, state, cage.material(), true,
                            new Location(spawn.getWorld(), baseX + wallOffset, baseY + y, baseZ + z));
                    placeBlock(context, state, cage.material(), true,
                            new Location(spawn.getWorld(), baseX - wallOffset, baseY + y, baseZ + z));
                }
            }

            if (interiorRadius == 0) {
                placeBlock(context, state, cage.material(), true,
                        new Location(spawn.getWorld(), baseX, baseY + roofOffset, baseZ));
            } else {
                for (int x = -interiorRadius; x <= interiorRadius; x++) {
                    for (int z = -interiorRadius; z <= interiorRadius; z++) {
                        placeBlock(context, state, cage.material(), true,
                                new Location(spawn.getWorld(), baseX + x, baseY + roofOffset, baseZ + z));
                    }
                }
            }
            markPlayersAtSpawn(players, state, spawn);
        }
    }

    public void removeCages(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                            ArenaState state) {
        if (context == null || state == null) {
            return;
        }

        Map<String, Material> cageBlocks = state.getCageBlocks();
        if (cageBlocks.isEmpty()) {
            return;
        }

        for (String key : cageBlocks.keySet()) {
            Location location = parseLocation(key);
            if (location == null) {
                continue;
            }
            context.getSchedulerAPI().runAtLocation(location, () -> {
                if (location.getWorld() != null) {
                    location.getBlock().setType(Material.AIR);
                }
            });
        }

        state.clearCageBlocks();
        state.clearCagedPlayers();
        state.clearCagedSpawns();
    }

    private void placeBlock(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                            ArenaState state,
                            Material material,
                            boolean replaceBlocks,
                            Location location) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        context.getSchedulerAPI().runAtLocation(location, () -> {
            Block block = location.getBlock();
            if (!replaceBlocks && block.getType() != Material.AIR) {
                return;
            }
            state.trackCageBlock(location, block.getType());
            block.setType(material);
        });
    }

    private Location parseLocation(String key) {
        if (key == null) {
            return null;
        }
        String[] parts = key.split(":");
        if (parts.length != 4) {
            return null;
        }
        World world = Bukkit.getWorld(parts[0]);
        if (world == null) {
            return null;
        }
        try {
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);
            return new Location(world, x, y, z);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Location resolveSpawnForPlayer(Player player,
                                           List<Location> spawns,
                                           ArenaState state,
                                           GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        if (player == null) {
            return null;
        }
        Location playerLocation = player.getLocation();
        if (playerLocation == null || playerLocation.getWorld() == null) {
            return null;
        }
        if (contextIsOutOfBounds(context, playerLocation)) {
            return null;
        }
        if (spawns == null || spawns.isEmpty()) {
            return playerLocation;
        }

        Location closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (Location spawn : spawns) {
            if (spawn == null) {
                continue;
            }
            if (state.isSpawnCaged(spawn)) {
                continue;
            }
            double distance = squaredDistanceCoords(spawn, playerLocation);
            if (distance < closestDistance) {
                closestDistance = distance;
                closest = spawn;
            }
        }

        double maxDistanceSquared = 2.25;
        if (closest == null || closestDistance > maxDistanceSquared) {
            return null;
        }
        return closest;
    }

    private boolean contextIsOutOfBounds(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                         Location location) {
        if (context == null || location == null) {
            return true;
        }
        return context.getArenaAPI() != null && !context.getArenaAPI().isInBounds(location);
    }

    private void markPlayersAtSpawn(List<Player> players, ArenaState state, Location spawn) {
        if (spawn == null || spawn.getWorld() == null) {
            return;
        }
        state.markSpawnCaged(spawn);
        for (Player candidate : players) {
            if (candidate == null || !candidate.isOnline()) {
                continue;
            }
            if (squaredDistanceCoords(candidate.getLocation(), spawn) <= 2.25) {
                state.markCageBuilt(candidate.getUniqueId());
            }
        }
    }

    private double squaredDistanceCoords(Location a, Location b) {
        if (a == null || b == null) {
            return Double.MAX_VALUE;
        }
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private CageDefinition resolveCageDefinition(Player player) {
        String defaultId = moduleConfig.getStringFrom("cage.yml", "default_cage", "clear_glass");
        String cageId = defaultId;
        if (storeAPI != null && player != null) {
            String selected = storeAPI.resolveSelected(player, getCageCategoryId());
            if (selected != null && moduleConfig.containsFrom("cage.yml", "cages." + selected)) {
                cageId = selected;
            }
        }
        String base = "cages." + cageId;
        Material material = resolveMaterialFrom("cage.yml", base + ".material", Material.GLASS);
        boolean headClear = moduleConfig.getBooleanFrom("cage.yml", base + ".head_clear", false);
        return new CageDefinition(material, headClear);
    }

    private List<Player> resolveCageOwners(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                           List<Location> spawns) {
        List<Player> players = context.getPlayers();
        List<Player> owners = new java.util.ArrayList<>();
        if (spawns == null || spawns.isEmpty()) {
            return owners;
        }
        if (players.isEmpty()) {
            return owners;
        }

        if (players.size() <= spawns.size()) {
            for (int index = 0; index < spawns.size(); index++) {
                owners.add(index < players.size() ? players.get(index) : null);
            }
            return owners;
        }

        TeamsAPI<Player, Material> teamsAPI = context.getTeamsAPI();
        if (teamsAPI != null && teamsAPI.isEnabled()) {
            return resolveTeamCageOwners(teamsAPI, players, spawns.size());
        }

        for (int index = 0; index < spawns.size(); index++) {
            owners.add(players.get(index));
        }
        return owners;
    }

    private List<Player> resolveTeamCageOwners(TeamsAPI<Player, Material> teamsAPI,
                                               List<Player> players,
                                               int spawnCount) {
        List<Player> owners = new java.util.ArrayList<>(java.util.Collections.nCopies(spawnCount, null));
        Map<String, List<Player>> teamMembers = new java.util.LinkedHashMap<>();
        for (Player player : players) {
            String teamId = resolveTeamId(teamsAPI, player);
            teamMembers.computeIfAbsent(teamId, ignored -> new java.util.ArrayList<>()).add(player);
        }

        List<String> orderedTeams = new java.util.ArrayList<>();
        for (TeamInfo<Player, Material> team : teamsAPI.getTeams()) {
            if (teamMembers.containsKey(team.getId())) {
                orderedTeams.add(team.getId());
            }
        }
        for (String teamId : teamMembers.keySet()) {
            if (!orderedTeams.contains(teamId)) {
                orderedTeams.add(teamId);
            }
        }

        int spawnIndex = 0;
        for (String teamId : orderedTeams) {
            List<Player> members = teamMembers.get(teamId);
            if (members == null || members.isEmpty()) {
                continue;
            }
            int slot = spawnIndex % spawnCount;
            if (owners.get(slot) == null) {
                owners.set(slot, members.get(0));
            }
            spawnIndex++;
        }
        return owners;
    }

    private String resolveTeamId(TeamsAPI<Player, Material> teamsAPI, Player player) {
        if (teamsAPI == null || player == null) {
            return "solo:" + (player == null ? "unknown" : player.getUniqueId());
        }
        TeamInfo<Player, Material> team = teamsAPI.getTeam(player);
        if (team != null && team.getId() != null) {
            return team.getId();
        }
        return "solo:" + player.getUniqueId();
    }

    private String getCageCategoryId() {
        return moduleConfig.getStringFrom("store.yml", "category_settings.cages.id", "skywars_cages");
    }

    private Material resolveMaterialFrom(String file, String path, Material fallback) {
        String raw = moduleConfig.getStringFrom(file, path);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Material.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    private record CageDefinition(Material material, boolean headClear) {
    }
}
