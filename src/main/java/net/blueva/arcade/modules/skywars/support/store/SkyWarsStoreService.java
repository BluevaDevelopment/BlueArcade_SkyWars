package net.blueva.arcade.modules.skywars.support.store;

import net.blueva.arcade.api.config.ModuleConfigAPI;
import net.blueva.arcade.api.module.ModuleInfo;
import net.blueva.arcade.api.store.StoreAPI;
import net.blueva.arcade.api.store.StoreCategoryDefinition;
import net.blueva.arcade.api.store.StoreCategoryType;
import net.blueva.arcade.api.store.StoreItemDefinition;
import net.blueva.arcade.api.store.StoreScope;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SkyWarsStoreService {

    private final ModuleConfigAPI moduleConfig;
    private final StoreAPI storeAPI;
    private final ModuleInfo moduleInfo;

    public SkyWarsStoreService(ModuleConfigAPI moduleConfig, StoreAPI storeAPI, ModuleInfo moduleInfo) {
        this.moduleConfig = moduleConfig;
        this.storeAPI = storeAPI;
        this.moduleInfo = moduleInfo;
    }

    public void registerStoreItems() {
        if (storeAPI == null) {
            return;
        }

        List<String> categories = moduleConfig.getStringListFrom("store.yml", "categories");
        for (String key : categories) {
            registerCategoryFromStore(key);
        }
    }

    private void registerCategoryFromStore(String key) {
        if (storeAPI == null) {
            return;
        }

        String base = "category_settings." + key;

        String id = moduleConfig.getStringFrom("store.yml", base + ".id");
        String name = moduleConfig.getStringFrom("store.yml", base + ".name");
        Material icon = parseMaterial(moduleConfig.getStringFrom("store.yml", base + ".icon"), Material.CHEST);
        List<String> description = moduleConfig.getStringListFrom("store.yml", base + ".description");
        boolean enabled = moduleConfig.getBooleanFrom("store.yml", base + ".enabled", true);
        boolean selectionEnabled = moduleConfig.getBooleanFrom("store.yml", base + ".selection_enabled", true);
        int sortOrder = moduleConfig.getIntFrom("store.yml", base + ".sort_order", 0);
        String parentId = moduleConfig.getStringFrom("store.yml", base + ".parent_id", null);
        StoreCategoryType type = parseCategoryType(moduleConfig.getStringFrom("store.yml", base + ".type", "SELECTION"));
        boolean randomSelectionEnabled = moduleConfig.getBooleanFrom("store.yml", base + ".random_selection_enabled", false);
        String randomDisplayName = moduleConfig.getStringFrom("store.yml", base + ".random_item.display_name", "Random");
        Material randomIcon = parseMaterial(moduleConfig.getStringFrom("store.yml", base + ".random_item.icon"),
                Material.ENDER_CHEST);
        List<String> randomDescription = moduleConfig.getStringListFrom("store.yml", base + ".random_item.description");

        List<StoreItemDefinition<Material>> items = new ArrayList<>();
        if ("kits".equalsIgnoreCase(key)) {
            items.addAll(buildKitItems());
        } else if ("cages".equalsIgnoreCase(key)) {
            items.addAll(buildCageItems());
        } else {
            items.addAll(buildStaticItems(base));
        }

        StoreCategoryDefinition<Material> category = new StoreCategoryDefinition<>(
                id != null ? id : key,
                name != null ? name : key,
                icon,
                description != null ? description : List.of(),
                StoreScope.MODULE,
                parentId,
                type,
                moduleInfo.getId(),
                enabled,
                sortOrder,
                selectionEnabled,
                randomSelectionEnabled,
                randomDisplayName,
                randomIcon,
                randomDescription != null ? randomDescription : List.of()
        );

        storeAPI.registerCategory(category, items);
    }

    private List<StoreItemDefinition<Material>> buildStaticItems(String base) {
        List<StoreItemDefinition<Material>> items = new ArrayList<>();
        List<String> itemsOrder = moduleConfig.getStringListFrom("store.yml", base + ".items.order");
        for (String itemId : itemsOrder == null ? List.<String>of() : itemsOrder) {
            String itemPath = base + ".items." + itemId;
            String displayName = moduleConfig.getStringFrom("store.yml", itemPath + ".name", itemId);
            Material itemIcon = parseMaterial(moduleConfig.getStringFrom("store.yml", itemPath + ".icon"), Material.CHEST);
            int price = moduleConfig.getIntFrom("store.yml", itemPath + ".price", 0);
            boolean itemEnabled = moduleConfig.getBooleanFrom("store.yml", itemPath + ".enabled", true);
            boolean defaultUnlocked = moduleConfig.getBooleanFrom("store.yml", itemPath + ".default_unlocked", false);
            List<String> lore = moduleConfig.getStringListFrom("store.yml", itemPath + ".description");

            items.add(new StoreItemDefinition<>(itemId, displayName, itemIcon, lore != null ? lore : List.of(),
                    price, itemEnabled, defaultUnlocked));
        }
        return items;
    }

    private List<StoreItemDefinition<Material>> buildKitItems() {
        List<StoreItemDefinition<Material>> items = new ArrayList<>();
        List<String> order = moduleConfig.getStringListFrom("kits.yml", "kits.order");
        for (String kitId : order == null ? List.<String>of() : order) {
            String base = "kits." + kitId;
            String displayName = moduleConfig.getStringFrom("kits.yml", base + ".name", kitId);
            Material icon = parseMaterial(moduleConfig.getStringFrom("kits.yml", base + ".icon"), Material.CHEST);
            int price = moduleConfig.getIntFrom("kits.yml", base + ".price", 0);
            boolean enabled = moduleConfig.getBooleanFrom("kits.yml", base + ".enabled", true);
            boolean defaultUnlocked = moduleConfig.getBooleanFrom("kits.yml", base + ".default_unlocked", false);
            List<String> lore = moduleConfig.getStringListFrom("kits.yml", base + ".description");

            items.add(new StoreItemDefinition<>(kitId, displayName, icon, lore != null ? lore : List.of(),
                    price, enabled, defaultUnlocked));
        }
        return items;
    }

    private List<StoreItemDefinition<Material>> buildCageItems() {
        List<StoreItemDefinition<Material>> items = new ArrayList<>();
        List<String> order = moduleConfig.getStringListFrom("cage.yml", "cages.order");
        for (String cageId : order == null ? List.<String>of() : order) {
            String base = "cages." + cageId;
            String displayName = moduleConfig.getStringFrom("cage.yml", base + ".name", cageId);
            Material icon = parseMaterial(moduleConfig.getStringFrom("cage.yml", base + ".icon"), Material.GLASS);
            int price = moduleConfig.getIntFrom("cage.yml", base + ".price", 0);
            boolean enabled = moduleConfig.getBooleanFrom("cage.yml", base + ".enabled", true);
            boolean defaultUnlocked = moduleConfig.getBooleanFrom("cage.yml", base + ".default_unlocked", false);
            List<String> lore = moduleConfig.getStringListFrom("cage.yml", base + ".description");

            items.add(new StoreItemDefinition<>(cageId, displayName, icon, lore != null ? lore : List.of(),
                    price, enabled, defaultUnlocked));
        }
        return items;
    }

    private StoreCategoryType parseCategoryType(String raw) {
        try {
            return StoreCategoryType.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return StoreCategoryType.SELECTION;
        }
    }

    private Material parseMaterial(String raw, Material fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Material.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }
}
