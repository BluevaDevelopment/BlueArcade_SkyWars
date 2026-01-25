package net.blueva.arcade.modules.skywars.support.vote;

import net.blueva.arcade.api.config.ModuleConfigAPI;
import net.blueva.arcade.api.ui.menu.BedrockButtonDefinition;
import net.blueva.arcade.api.ui.menu.BedrockMenuDefinition;
import net.blueva.arcade.api.ui.menu.BedrockSimpleMenuDefinition;
import net.blueva.arcade.api.ui.menu.JavaItemDefinition;
import net.blueva.arcade.api.ui.menu.JavaMenuItem;
import net.blueva.arcade.api.ui.menu.MenuDefinition;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SkyWarsVoteMenuRepository {

    private final ModuleConfigAPI moduleConfig;
    private final Map<String, MenuDefinition<Material>> menus = new HashMap<>();

    public SkyWarsVoteMenuRepository(ModuleConfigAPI moduleConfig) {
        this.moduleConfig = moduleConfig;
    }

    public void loadMenus() {
        menus.clear();
        loadMenu(SkyWarsVoteService.MENU_MAIN, "menus/java/skywars_vote_main.yml",
                "menus/bedrock/skywars_vote_main.yml");
        loadMenu(SkyWarsVoteService.MENU_CHESTS, "menus/java/skywars_vote_chests.yml",
                "menus/bedrock/skywars_vote_chests.yml");
        loadMenu(SkyWarsVoteService.MENU_HEARTS, "menus/java/skywars_vote_hearts.yml",
                "menus/bedrock/skywars_vote_hearts.yml");
        loadMenu(SkyWarsVoteService.MENU_TIME, "menus/java/skywars_vote_time.yml",
                "menus/bedrock/skywars_vote_time.yml");
        loadMenu(SkyWarsVoteService.MENU_WEATHER, "menus/java/skywars_vote_weather.yml",
                "menus/bedrock/skywars_vote_weather.yml");
    }

    public MenuDefinition<Material> getMenu(String id) {
        if (id == null) {
            return null;
        }
        return menus.get(id);
    }

    private void loadMenu(String id, String javaFile, String bedrockFile) {
        File javaPath = resolveFile(javaFile);
        if (javaPath == null || !javaPath.exists()) {
            return;
        }

        YamlConfiguration javaConfig = YamlConfiguration.loadConfiguration(javaPath);
        String title = javaConfig.getString("menuName", "");
        int size = javaConfig.getInt("menuSize", 9);
        List<JavaMenuItem<Material>> items = loadJavaItems(javaConfig.getConfigurationSection("items"));

        BedrockMenuDefinition bedrockMenu = loadBedrockMenu(bedrockFile);
        menus.put(id, new MenuDefinition<>(title, size, items, bedrockMenu));
    }

    private List<JavaMenuItem<Material>> loadJavaItems(ConfigurationSection section) {
        if (section == null) {
            return List.of();
        }

        List<JavaMenuItem<Material>> items = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            ConfigurationSection itemSection = section.getConfigurationSection(key);
            if (itemSection == null) {
                continue;
            }

            int slot = itemSection.getInt("slot", -1);
            String name = itemSection.getString("name", "");
            List<String> lore = itemSection.getStringList("lore");
            List<String> actions = itemSection.getStringList("actions");

            ConfigurationSection stackSection = itemSection.getConfigurationSection("itemStack");
            if (stackSection == null) {
                continue;
            }

            String materialName = stackSection.getString("material");
            if (materialName == null) {
                continue;
            }

            Material material;
            try {
                material = Material.valueOf(materialName.toUpperCase());
            } catch (IllegalArgumentException ex) {
                continue;
            }

            int amount = Math.max(1, stackSection.getInt("amount", 1));
            JavaItemDefinition<Material> definition = JavaItemDefinition.of(material, amount, name, lore, actions);
            items.add(JavaMenuItem.of(slot, definition));
        }

        return items;
    }

    private BedrockMenuDefinition loadBedrockMenu(String bedrockFile) {
        File bedrockPath = resolveFile(bedrockFile);
        if (bedrockPath == null || !bedrockPath.exists()) {
            return null;
        }

        YamlConfiguration bedrockConfig = YamlConfiguration.loadConfiguration(bedrockPath);
        String title = bedrockConfig.getString("menuName", "");
        List<String> content = bedrockConfig.getStringList("content");
        ConfigurationSection buttonsSection = bedrockConfig.getConfigurationSection("buttons");
        List<BedrockButtonDefinition> buttons = new ArrayList<>();

        if (buttonsSection != null) {
            for (String key : buttonsSection.getKeys(false)) {
                ConfigurationSection button = buttonsSection.getConfigurationSection(key);
                if (button == null) {
                    continue;
                }
                String text = button.getString("text", "");
                String image = button.getString("image");
                List<String> actions = button.getStringList("actions");
                buttons.add(BedrockButtonDefinition.of(text, image, actions));
            }
        }

        return new BedrockSimpleMenuDefinition(title, content, buttons);
    }

    private File resolveFile(String path) {
        if (moduleConfig == null) {
            return null;
        }
        return new File(moduleConfig.getDataFolder(), path);
    }
}
