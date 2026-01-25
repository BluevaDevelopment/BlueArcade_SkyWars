package net.blueva.arcade.modules.skywars.support.spawn;

import net.blueva.arcade.api.config.ModuleConfigAPI;
import net.blueva.arcade.api.game.GameContext;
import net.blueva.arcade.api.store.StoreAPI;
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

        if (context.getArenaAPI() == null) {
            return;
        }

        List<Location> spawns = context.getArenaAPI().getSpawns();
        if (spawns == null || spawns.isEmpty()) {
            return;
        }

        List<Player> players = context.getPlayers();
        int interiorRadius = resolveInteriorRadius(context);
        int wallOffset = interiorRadius + 1;
        int floorOffset = -1;
        int roofOffset = 3;
        int wallTopOffset = 2;

        for (int index = 0; index < spawns.size(); index++) {
            Location spawn = spawns.get(index);
            if (spawn == null || spawn.getWorld() == null) {
                continue;
            }

            Player player = index < players.size() ? players.get(index) : null;
            CageDefinition cage = resolveCageDefinition(player);

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

    private int resolveInteriorRadius(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        TeamsAPI teamsAPI = context.getTeamsAPI();
        if (teamsAPI != null && teamsAPI.isEnabled()) {
            int teamSize = teamsAPI.getTeamSize();
            if (teamSize > 1) {
                return 1;
            }
        }
        return 0;
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
