package cn.endvoidrescue;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

final class Config {
    private final JavaPlugin plugin;
    private final FileConfiguration config;

    Config(JavaPlugin plugin) {
        this.plugin = plugin;
        plugin.saveDefaultConfig();
        this.config = plugin.getConfig();
        addMissingDefaults();
    }

    private void addMissingDefaults() {
        Configuration defaults = config.getDefaults();
        if (defaults == null) {
            return;
        }
        List<String> addedKeys = new ArrayList<>();
        for (String path : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(path) || config.contains(path, true)) {
                continue;
            }
            config.set(path, defaults.get(path));
            addedKeys.add(path);
        }
        if (!addedKeys.isEmpty()) {
            plugin.saveConfig();
            plugin.getLogger().warning("Added missing config keys: " + String.join(", ", addedKeys));
        }
    }

    boolean saveImmediately() {
        return "immediate".equalsIgnoreCase(config.getString("pending-drops.save-mode", "immediate"));
    }

    Set<Material> blacklist() {
        Set<Material> blacklist = EnumSet.noneOf(Material.class);
        for (String name : config.getStringList("blacklist")) {
            Material material = Material.matchMaterial(name);
            if (material != null) {
                blacklist.add(material);
            } else {
                plugin.getLogger().warning("Unknown blacklist material: " + name);
            }
        }
        return blacklist;
    }

    World.Environment triggerEnvironment() {
        try {
            String value = config.getString("trigger-world", "THE_END");
            return World.Environment.valueOf(value == null ? "THE_END" : value);
        } catch (IllegalArgumentException exception) {
            return World.Environment.THE_END;
        }
    }

    EntityDamageEvent.DamageCause triggerCause() {
        try {
            String value = config.getString("trigger-cause", "VOID");
            return EntityDamageEvent.DamageCause.valueOf(value == null ? "VOID" : value);
        } catch (IllegalArgumentException exception) {
            return EntityDamageEvent.DamageCause.VOID;
        }
    }

    boolean removeNonCurseEnchantments() {
        return config.getBoolean("remove-non-curse-enchant", true);
    }

    boolean mergeSimilarItems() {
        return config.getBoolean("burst.merge-similar", true);
    }

    double burstRadius() {
        return config.getDouble("burst.radius", 0.5);
    }

    int pickupDelayTicks() {
        return config.getInt("burst.pickup-delay-ticks", 10);
    }
}
