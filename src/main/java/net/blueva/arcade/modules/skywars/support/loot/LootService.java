package net.blueva.arcade.modules.skywars.support.loot;

import net.blueva.arcade.api.config.ModuleConfigAPI;
import net.blueva.arcade.api.game.GameContext;
import net.blueva.arcade.api.module.ModuleInfo;
import net.blueva.arcade.api.stats.StatsAPI;
import net.blueva.arcade.modules.skywars.state.ArenaState;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.NamespacedKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public class LootService {

    private final ModuleInfo moduleInfo;
    private final ModuleConfigAPI moduleConfig;
    private final StatsAPI statsAPI;

    public LootService(ModuleInfo moduleInfo, ModuleConfigAPI moduleConfig, StatsAPI statsAPI) {
        this.moduleInfo = moduleInfo;
        this.moduleConfig = moduleConfig;
        this.statsAPI = statsAPI;
    }

    public void handleChestLoot(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                ArenaState state,
                                Player player,
                                Block block) {
        if (player == null || block == null || state == null) {
            return;
        }

        Material blockType = block.getType();
        boolean isChest = blockType == Material.CHEST
                || blockType == Material.TRAPPED_CHEST
                || blockType == Material.ENDER_CHEST;
        if (!isChest) {
            return;
        }

        if (!state.isTrackedChest(block.getLocation())
                && !moduleConfig.getBoolean("loot.chests.allow_untracked", false)) {
            return;
        }

        long now = System.currentTimeMillis();
        if (!state.shouldRefillChest(block.getLocation(), now)) {
            return;
        }

        List<LootEntry> entries = parseEntries(state);
        if (entries.isEmpty()) {
            return;
        }

        int minItems = resolveMinItems(state);
        int maxItems = resolveMaxItems(state);
        int itemCount = ThreadLocalRandom.current().nextInt(Math.max(1, minItems), Math.max(minItems, maxItems) + 1);

        if (!fillChest(block, entries, itemCount)) {
            return;
        }

        long nextRefillAt = resolveNextRefillAt(now);
        state.markChestRefill(block.getLocation(), nextRefillAt);

        Location particleLocation = block.getLocation().add(0.5, 1.0, 0.5);
        block.getWorld().spawnParticle(Particle.FLAME,
                particleLocation, 20, 0.4, 0.4, 0.4, 0.01);

        if (statsAPI != null) {
            statsAPI.addModuleStat(player, moduleInfo.getId(), "chests_looted", 1);
        }
    }

    public List<TrackedChest> loadChests(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        if (context == null || context.getDataAccess() == null) {
            return List.of();
        }

        World arenaWorld = context.getArenaAPI() != null ? context.getArenaAPI().getWorld() : null;
        List<String> entries = context.getDataAccess().getGameData("loot.chests.locations", List.class);
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }

        List<TrackedChest> chests = new ArrayList<>();
        for (String entry : entries) {
            TrackedChest trackedChest = parseTrackedChest(entry, arenaWorld);
            if (trackedChest != null) {
                chests.add(trackedChest);
            }
        }
        return chests;
    }

    public void restoreChests(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                              ArenaState state) {
        if (context == null || state == null) {
            return;
        }

        for (TrackedChest chest : state.getTrackedChests()) {
            Location location = chest.getLocation();
            if (location == null) {
                continue;
            }
            context.getSchedulerAPI().runAtLocation(location, () -> {
                if (location.getWorld() != null) {
                    location.getBlock().setType(chest.getMaterial());
                }
            });
        }
    }

    public void prefillChests(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                              ArenaState state) {
        if (context == null || state == null) {
            return;
        }
        if (!moduleConfig.getBoolean("loot.refill.prefill_on_start", true)) {
            return;
        }

        long now = System.currentTimeMillis();
        for (TrackedChest chest : state.getTrackedChests()) {
            if (chest == null || chest.getLocation() == null) {
                continue;
            }
            Block block = chest.getLocation().getBlock();
            Material blockType = block.getType();
            if (blockType != Material.CHEST && blockType != Material.TRAPPED_CHEST && blockType != Material.ENDER_CHEST) {
                continue;
            }
            List<LootEntry> entries = parseEntries(state);
            if (entries.isEmpty()) {
                continue;
            }
            int minItems = resolveMinItems(state);
            int maxItems = resolveMaxItems(state);
            int itemCount = ThreadLocalRandom.current().nextInt(Math.max(1, minItems), Math.max(minItems, maxItems) + 1);
            if (fillChest(block, entries, itemCount)) {
                long nextRefillAt = resolveNextRefillAt(now);
                state.markChestRefill(chest.getLocation(), nextRefillAt);
            }
        }
    }

    public void startChestMarkers(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                  ArenaState state) {
        if (context == null || state == null) {
            return;
        }

        int arenaId = context.getArenaId();
        String taskId = "arena_" + arenaId + "_skywars_chest_markers";
        int intervalTicks = Math.max(20, moduleConfig.getInt("loot.chests.marker_interval_ticks", 40));

        context.getSchedulerAPI().runTimer(taskId, () -> {
            for (TrackedChest chest : state.getTrackedChests()) {
                Location location = chest.getLocation();
                if (location == null || location.getWorld() == null) {
                    continue;
                }
                Material currentType = location.getBlock().getType();
                if (currentType != Material.CHEST
                        && currentType != Material.TRAPPED_CHEST
                        && currentType != Material.ENDER_CHEST) {
                    continue;
                }

                Location particleLocation = location.clone().add(0.5, 1.1, 0.5);
                Particle particle = currentType == Material.ENDER_CHEST
                        ? Particle.PORTAL
                        : Particle.HAPPY_VILLAGER;
                location.getWorld().spawnParticle(particle, particleLocation, 6, 0.2, 0.3, 0.2, 0.01);
            }
        }, 20L, intervalTicks);
    }

    public void startChestRefills(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                  ArenaState state) {
        if (context == null || state == null) {
            return;
        }

        int intervalSeconds = moduleConfig.getInt("loot.refill.interval_seconds", 0);
        if (!moduleConfig.getBoolean("loot.refill.enabled", true) || intervalSeconds <= 0) {
            return;
        }

        int arenaId = context.getArenaId();
        String taskId = "arena_" + arenaId + "_skywars_chest_refill";
        int checkInterval = Math.max(10, moduleConfig.getInt("loot.refill.check_interval_ticks", 20));

        context.getSchedulerAPI().runTimer(taskId, () -> {
            long now = System.currentTimeMillis();
            for (TrackedChest chest : state.getTrackedChests()) {
                if (chest == null || chest.getLocation() == null) {
                    continue;
                }
                if (!state.shouldRefillChest(chest.getLocation(), now)) {
                    continue;
                }
                Block block = chest.getLocation().getBlock();
                Material blockType = block.getType();
                if (blockType != Material.CHEST && blockType != Material.TRAPPED_CHEST && blockType != Material.ENDER_CHEST) {
                    continue;
                }
                List<LootEntry> entries = parseEntries(state);
                if (entries.isEmpty()) {
                    continue;
                }
                int minItems = resolveMinItems(state);
                int maxItems = resolveMaxItems(state);
                int itemCount = ThreadLocalRandom.current().nextInt(Math.max(1, minItems), Math.max(minItems, maxItems) + 1);
                if (fillChest(block, entries, itemCount)) {
                    long nextRefillAt = resolveNextRefillAt(now);
                    state.markChestRefill(chest.getLocation(), nextRefillAt);
                }
            }
        }, checkInterval, checkInterval);
    }

    public void forceRefillChests(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                  ArenaState state) {
        if (context == null || state == null) {
            return;
        }

        long now = System.currentTimeMillis();
        for (TrackedChest chest : state.getTrackedChests()) {
            if (chest == null || chest.getLocation() == null) {
                continue;
            }
            Block block = chest.getLocation().getBlock();
            Material blockType = block.getType();
            if (blockType != Material.CHEST && blockType != Material.TRAPPED_CHEST && blockType != Material.ENDER_CHEST) {
                continue;
            }
            List<LootEntry> entries = parseEntries(state);
            if (entries.isEmpty()) {
                continue;
            }
            int minItems = resolveMinItems(state);
            int maxItems = resolveMaxItems(state);
            int itemCount = ThreadLocalRandom.current().nextInt(Math.max(1, minItems), Math.max(minItems, maxItems) + 1);
            if (fillChest(block, entries, itemCount)) {
                long nextRefillAt = resolveNextRefillAt(now);
                state.markChestRefill(chest.getLocation(), nextRefillAt);
            }
        }
    }

    private long resolveNextRefillAt(long now) {
        int intervalSeconds = moduleConfig.getInt("loot.refill.interval_seconds", 0);
        if (!moduleConfig.getBoolean("loot.refill.enabled", true) || intervalSeconds <= 0) {
            return Long.MAX_VALUE;
        }
        return now + (intervalSeconds * 1000L);
    }

    private boolean fillChest(Block block, List<LootEntry> entries, int itemCount) {
        if (block == null || entries == null || entries.isEmpty()) {
            return false;
        }

        BlockState blockState = block.getState();
        Inventory inventory = null;
        if (blockState instanceof Container container) {
            inventory = container.getInventory();
        } else if (blockState instanceof InventoryHolder holder) {
            inventory = holder.getInventory();
        }

        if (inventory == null) {
            return false;
        }

        inventory.clear();
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < inventory.getSize(); i++) {
            slots.add(i);
        }
        Collections.shuffle(slots);

        int slotIndex = 0;
        for (int i = 0; i < itemCount && slotIndex < slots.size(); i++) {
            LootEntry entry = pickEntry(entries);
            if (entry == null) {
                continue;
            }
            int amount = entry.minAmount == entry.maxAmount
                    ? entry.minAmount
                    : ThreadLocalRandom.current().nextInt(entry.minAmount, entry.maxAmount + 1);
            ItemStack itemStack = new ItemStack(entry.material, Math.max(1, amount));
            applyEnchantments(itemStack, entry.enchantments);
            inventory.setItem(slots.get(slotIndex), itemStack);
            slotIndex++;
        }
        return true;
    }

    private List<LootEntry> parseEntries(ArenaState state) {
        String tier = resolveChestTier(state);
        String basePath = "loot.chests.tiers." + tier;
        List<String> items = moduleConfig.getStringList(basePath + ".items");
        if (items == null || items.isEmpty()) {
            items = moduleConfig.getStringList("loot.chests.items");
        }
        List<LootEntry> parsed = new ArrayList<>();
        if (items == null || items.isEmpty()) {
            return parsed;
        }

        for (String entry : items) {
            try {
                String[] entryParts = entry.split("\\|", 2);
                String[] parts = entryParts[0].split(":");
                if (parts.length < 3) {
                    continue;
                }
                Material material = Material.valueOf(parts[0].toUpperCase(Locale.ROOT));
                int min = Integer.parseInt(parts[1]);
                int max = parts.length >= 4 ? Integer.parseInt(parts[2]) : min;
                int weight = Integer.parseInt(parts.length >= 4 ? parts[3] : parts[2]);
                if (weight <= 0) {
                    continue;
                }
                Map<Enchantment, Integer> enchantments = parseEnchantments(entryParts.length > 1 ? entryParts[1] : null);
                parsed.add(new LootEntry(material, Math.max(1, min), Math.max(min, max), weight, enchantments));
            } catch (Exception ignored) {
                // Ignore malformed entries
            }
        }

        return parsed;
    }

    private Map<Enchantment, Integer> parseEnchantments(String data) {
        if (data == null || data.isBlank()) {
            return Map.of();
        }

        Map<Enchantment, Integer> enchantments = new LinkedHashMap<>();
        String[] entries = data.split(",");
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String[] parts = entry.trim().split(":");
            if (parts.length < 2) {
                continue;
            }
            Enchantment enchantment = resolveEnchantment(parts[0]);
            if (enchantment == null) {
                continue;
            }
            int level = Integer.parseInt(parts[1]);
            if (level <= 0) {
                continue;
            }
            enchantments.put(enchantment, level);
        }
        return enchantments.isEmpty() ? Map.of() : enchantments;
    }

    private Enchantment resolveEnchantment(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim();
        Enchantment enchantment = Enchantment.getByName(normalized.toUpperCase(Locale.ROOT));
        if (enchantment != null) {
            return enchantment;
        }
        return Enchantment.getByKey(NamespacedKey.minecraft(normalized.toLowerCase(Locale.ROOT)));
    }

    private void applyEnchantments(ItemStack itemStack, Map<Enchantment, Integer> enchantments) {
        if (itemStack == null || enchantments == null || enchantments.isEmpty()) {
            return;
        }
        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) {
            return;
        }
        if (meta instanceof EnchantmentStorageMeta storageMeta && itemStack.getType() == Material.ENCHANTED_BOOK) {
            for (Map.Entry<Enchantment, Integer> entry : enchantments.entrySet()) {
                storageMeta.addStoredEnchant(entry.getKey(), entry.getValue(), true);
            }
            itemStack.setItemMeta(storageMeta);
            return;
        }

        for (Map.Entry<Enchantment, Integer> entry : enchantments.entrySet()) {
            meta.addEnchant(entry.getKey(), entry.getValue(), true);
        }
        itemStack.setItemMeta(meta);
    }

    private String resolveChestTier(ArenaState state) {
        if (state != null && state.getSelectedChestTier() != null) {
            return state.getSelectedChestTier().toLowerCase(Locale.ROOT);
        }
        return moduleConfig.getString("votes.defaults.chests", "normal").toLowerCase(Locale.ROOT);
    }

    private int resolveMinItems(ArenaState state) {
        String tier = resolveChestTier(state);
        String basePath = "loot.chests.tiers." + tier + ".item_count.min";
        if (moduleConfig.contains(basePath)) {
            return moduleConfig.getInt(basePath, 3);
        }
        return moduleConfig.getInt("loot.chests.item_count.min", 3);
    }

    private int resolveMaxItems(ArenaState state) {
        String tier = resolveChestTier(state);
        String basePath = "loot.chests.tiers." + tier + ".item_count.max";
        if (moduleConfig.contains(basePath)) {
            return moduleConfig.getInt(basePath, 6);
        }
        return moduleConfig.getInt("loot.chests.item_count.max", 6);
    }

    private TrackedChest parseTrackedChest(String entry, World fallbackWorld) {
        if (entry == null || entry.isEmpty()) {
            return null;
        }

        String[] parts = entry.split(":");
        if (parts.length < 4) {
            return null;
        }

        String worldName = parts[0];
        try {
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);
            Material material = Material.CHEST;
            if (parts.length >= 5) {
                material = Material.valueOf(parts[4].toUpperCase(Locale.ROOT));
            }
            if (material != Material.CHEST
                    && material != Material.TRAPPED_CHEST
                    && material != Material.ENDER_CHEST) {
                material = Material.CHEST;
            }

            World world = org.bukkit.Bukkit.getWorld(worldName);
            if (world == null) {
                world = fallbackWorld;
            }
            if (world == null) {
                return null;
            }
            Location location = new Location(world, x, y, z);
            return new TrackedChest(location, material);
        } catch (Exception ignored) {
            return null;
        }
    }

    private LootEntry pickEntry(List<LootEntry> entries) {
        if (entries.isEmpty()) {
            return null;
        }
        int total = entries.stream().mapToInt(entry -> entry.weight).sum();
        if (total <= 0) {
            return null;
        }
        int roll = ThreadLocalRandom.current().nextInt(total);
        int count = 0;
        for (LootEntry entry : entries) {
            count += entry.weight;
            if (roll < count) {
                return entry;
            }
        }
        return entries.get(entries.size() - 1);
    }

    private static class LootEntry {
        private final Material material;
        private final int minAmount;
        private final int maxAmount;
        private final int weight;
        private final Map<Enchantment, Integer> enchantments;

        private LootEntry(Material material, int minAmount, int maxAmount, int weight, Map<Enchantment, Integer> enchantments) {
            this.material = material;
            this.minAmount = minAmount;
            this.maxAmount = maxAmount;
            this.weight = weight;
            this.enchantments = enchantments == null ? Map.of() : Map.copyOf(enchantments);
        }
    }

}
