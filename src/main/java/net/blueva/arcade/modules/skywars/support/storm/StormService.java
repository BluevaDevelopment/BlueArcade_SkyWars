package net.blueva.arcade.modules.skywars.support.storm;

import net.blueva.arcade.api.config.ModuleConfigAPI;
import net.blueva.arcade.api.game.GameContext;
import net.blueva.arcade.api.module.ModuleInfo;
import net.blueva.arcade.api.stats.StatsAPI;
import net.blueva.arcade.modules.skywars.game.SkyWarsGame;
import net.blueva.arcade.modules.skywars.state.ArenaState;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.WorldBorder;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class StormService {

    private final ModuleInfo moduleInfo;
    private final ModuleConfigAPI moduleConfig;
    private final StatsAPI statsAPI;
    private final SkyWarsGame game;

    public StormService(ModuleInfo moduleInfo, ModuleConfigAPI moduleConfig, StatsAPI statsAPI, SkyWarsGame game) {
        this.moduleInfo = moduleInfo;
        this.moduleConfig = moduleConfig;
        this.statsAPI = statsAPI;
        this.game = game;
    }

    public void initializeStorm(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                ArenaState state) {
        Location center = resolveCenter(context);
        state.setStormCenter(center);
        double maxRadius = resolveMaxRadius(context, center);
        state.setStormMaxRadius(maxRadius);
        double finalRadius = Math.max(1.0, moduleConfig.getDouble("storm.final_radius_blocks", 5.0));
        int shrinkDuration = Math.max(0, moduleConfig.getInt("storm.shrink_duration_seconds", 120));
        double damagePerSecond = Math.max(0.0, moduleConfig.getDouble("storm.damage_per_second", 2.0));

        state.setStormFinalRadius(finalRadius);
        state.setStormShrinkDurationSeconds(shrinkDuration);
        state.setStormDamagePerSecond(damagePerSecond);
        state.setStormRadius(maxRadius);

        initializeWorldBorder(context, state);
        startStormShrink(state);
    }

    public void tickStorm(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                          ArenaState state) {
        if (state.getStormCenter() == null) {
            initializeStorm(context, state);
        }

        syncStormRadius(state);
        updateWorldBorder(context, state);
        applyStormDamage(context, state);
        spawnStormLightning(context, state);
    }

    private void applyStormDamage(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                  ArenaState state) {
        if (context.getPhase() == null) {
            return;
        }

        double damage = Math.max(0.0, state.getStormDamagePerSecond());
        if (damage <= 0) {
            return;
        }

        double safeRadius = resolveCurrentRadius(state);
        for (Player player : context.getAlivePlayers()) {
            if (!player.isOnline()) {
                continue;
            }
            if (!isInsideSafeZone(state, player.getLocation(), safeRadius)) {
                double finalHealth = player.getHealth() - damage;
                if (finalHealth <= 0) {
                    if (statsAPI != null) {
                        statsAPI.addModuleStat(player, moduleInfo.getId(), "storm_damage_taken", (int) Math.ceil(damage));
                    }
                    game.handleNonCombatDeath(context, player);
                } else {
                    player.damage(damage);
                    if (statsAPI != null) {
                        statsAPI.addModuleStat(player, moduleInfo.getId(), "storm_damage_taken", (int) Math.ceil(damage));
                    }
                }
            }
        }
    }

    private void spawnStormLightning(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                     ArenaState state) {
        if (!moduleConfig.getBoolean("storm.lightning.enabled", true)) {
            return;
        }

        int interval = moduleConfig.getInt("storm.lightning.interval_seconds", 3);
        if (interval <= 0) {
            return;
        }

        int ticks = state.incrementStormLightningTicks();
        if (ticks < interval) {
            return;
        }
        state.resetStormLightningTicks();

        int strikes = moduleConfig.getInt("storm.lightning.strikes_per_wave", 3);
        Location center = state.getStormCenter();
        if (center == null) {
            return;
        }

        double radius = Math.max(0.0, state.getStormMaxRadius());
        World world = center.getWorld();
        for (int i = 0; i < strikes; i++) {
            Location strikeLocation = randomStormLocation(center, radius, resolveCurrentRadius(state));
            world.strikeLightningEffect(strikeLocation);
        }
    }

    public boolean isInsideSafeZone(ArenaState state, Location location) {
        return isInsideSafeZone(state, location, resolveCurrentRadius(state));
    }

    private boolean isInsideSafeZone(ArenaState state, Location location, double safeRadius) {
        if (state.getStormCenter() == null || location == null) {
            return true;
        }
        if (!location.getWorld().equals(state.getStormCenter().getWorld())) {
            return true;
        }

        double dx = location.getX() - state.getStormCenter().getX();
        double dz = location.getZ() - state.getStormCenter().getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        return distance <= safeRadius;
    }

    private Location resolveCenter(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        Location[] bounds = getBounds(context);
        if (bounds != null) {
            Location min = bounds[0];
            Location max = bounds[1];
            double centerX = (min.getX() + max.getX()) / 2.0;
            double centerY = (min.getY() + max.getY()) / 2.0;
            double centerZ = (min.getZ() + max.getZ()) / 2.0;
            return new Location(min.getWorld(), centerX, centerY, centerZ);
        }

        List<Player> players = context.getPlayers();
        return players.isEmpty() ? null : players.get(0).getLocation();
    }

    private double resolveMaxRadius(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                    Location center) {
        Location[] bounds = getBounds(context);
        if (bounds != null) {
            Location min = bounds[0];
            Location max = bounds[1];
            double halfX = Math.abs(max.getX() - min.getX()) / 2.0;
            double halfZ = Math.abs(max.getZ() - min.getZ()) / 2.0;
            return Math.max(1.0, Math.min(halfX, halfZ));
        }
        return 1.0;
    }

    private Location randomStormLocation(Location center, double maxRadius, double safeRadius) {
        double minRadius = Math.min(maxRadius, Math.max(safeRadius + 2.0, safeRadius));
        double maxPickRadius = Math.max(minRadius, maxRadius + 1.0);
        double radius = ThreadLocalRandom.current().nextDouble(minRadius, maxPickRadius);
        double angle = ThreadLocalRandom.current().nextDouble(0.0, Math.PI * 2);
        double x = center.getX() + radius * Math.cos(angle);
        double z = center.getZ() + radius * Math.sin(angle);
        double y = center.getY();
        return new Location(center.getWorld(), x, y, z);
    }

    private void startStormShrink(ArenaState state) {
        WorldBorder border = state.getStormBorder();
        if (border == null) {
            return;
        }
        double finalRadius = Math.max(1.0, state.getStormFinalRadius());
        int durationSeconds = Math.max(0, state.getStormShrinkDurationSeconds());
        border.setSize(finalRadius * 2.0, durationSeconds);
    }

    private void initializeWorldBorder(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                       ArenaState state) {
        if (state.getStormCenter() == null) {
            return;
        }
        WorldBorder border = Bukkit.createWorldBorder();
        border.setCenter(state.getStormCenter());
        border.setSize(Math.max(1.0, state.getStormRadius() * 2.0));
        border.setWarningDistance(0);
        border.setWarningTime(0);
        state.setStormBorder(border);
        for (Player player : context.getPlayers()) {
            player.setWorldBorder(border);
        }
    }

    private void updateWorldBorder(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                   ArenaState state) {
        WorldBorder border = state.getStormBorder();
        if (border == null || state.getStormCenter() == null) {
            return;
        }
        border.setCenter(state.getStormCenter());
        for (Player player : context.getPlayers()) {
            player.setWorldBorder(border);
        }
    }

    public void clearWorldBorder(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                 ArenaState state) {
        state.setStormBorder(null);
        for (Player player : context.getPlayers()) {
            player.setWorldBorder(null);
        }
    }

    private void syncStormRadius(ArenaState state) {
        WorldBorder border = state.getStormBorder();
        if (border != null) {
            state.setStormRadius(Math.max(0.0, border.getSize() / 2.0));
        }
    }

    private double resolveCurrentRadius(ArenaState state) {
        WorldBorder border = state.getStormBorder();
        if (border != null) {
            return Math.max(0.0, border.getSize() / 2.0);
        }
        return Math.max(0.0, state.getStormRadius());
    }

    private Location[] getBounds(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        if (context.getArenaAPI() == null) {
            return null;
        }
        Location min = context.getArenaAPI().getBoundsMin();
        Location max = context.getArenaAPI().getBoundsMax();
        if (min == null || max == null || min.getWorld() == null) {
            return null;
        }
        return new Location[]{min, max};
    }

}
