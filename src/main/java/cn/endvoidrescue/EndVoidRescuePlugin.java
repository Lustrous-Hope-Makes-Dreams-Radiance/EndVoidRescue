package cn.endvoidrescue;

import io.papermc.paper.datacomponent.DataComponentTypes;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.ShulkerBox;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public class EndVoidRescuePlugin extends JavaPlugin implements Listener {
    private static final int MAX_BUNDLE_DEPTH = 16;
    private final Random random = new Random();
    private PendingDropStore store;
    private Set<Material> blacklist;
    private boolean loggedBurstRadiusClamp;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        store = new PendingDropStore(getDataFolder(), getLogger());
        reloadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("EndVoidRescue enabled");
    }

    private void reloadSettings() {
        blacklist = EnumSet.noneOf(Material.class);
        for (String name : getConfig().getStringList("blacklist")) {
            Material material = Material.matchMaterial(name);
            if (material != null) {
                blacklist.add(material);
            } else {
                getLogger().warning("Unknown blacklist material: " + name);
            }
        }
        loggedBurstRadiusClamp = false;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!isTriggerDeath(player)) {
            return;
        }
        event.setKeepInventory(true);
        event.getDrops().clear();
        UUID playerId = player.getUniqueId();
        List<ItemStack> pending = store.get(playerId);
        processInventory(player.getInventory(), pending);
        if (pending.isEmpty()) {
            store.remove(playerId);
        } else {
            store.put(playerId, mergeIfConfigured(pending));
        }
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        List<ItemStack> pending = store.get(playerId);
        if (pending.isEmpty()) {
            store.remove(playerId);
            return;
        }
        Location respawnLocation = event.getRespawnLocation();
        try {
            burst(player, respawnLocation, pending);
        } catch (Exception e) {
            getLogger().log(Level.SEVERE,
                    "Burst pending drops failed for " + player.getName() + " at " + respawnLocation, e);
            return;
        }
        store.remove(playerId);
    }

    private boolean isTriggerDeath(Player player) {
        if (player.getWorld().getEnvironment() != configuredEnvironment()) {
            return false;
        }
        EntityDamageEvent lastDamageCause = player.getLastDamageCause();
        return lastDamageCause != null && lastDamageCause.getCause() == configuredCause();
    }

    private World.Environment configuredEnvironment() {
        try {
            String value = getConfig().getString("trigger-world", "THE_END");
            return World.Environment.valueOf(value == null ? "THE_END" : value);
        } catch (IllegalArgumentException exception) {
            return World.Environment.THE_END;
        }
    }

    private EntityDamageEvent.DamageCause configuredCause() {
        try {
            String value = getConfig().getString("trigger-cause", "VOID");
            return EntityDamageEvent.DamageCause.valueOf(value == null ? "VOID" : value);
        } catch (IllegalArgumentException exception) {
            return EntityDamageEvent.DamageCause.VOID;
        }
    }

    private void processInventory(Inventory inventory, List<ItemStack> pending) {
        int size = inventory.getSize();
        for (int slot = 0; slot < size; ++slot) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir()) {
                continue;
            }
            ItemStack processed = processItem(item, pending);
            inventory.setItem(slot, processed);
        }
    }

    private ItemStack processItem(ItemStack item, List<ItemStack> pending) {
        Material type = item.getType();
        if (isBlacklisted(type)) {
            return null;
        }
        if (Tag.SHULKER_BOXES.isTagged(type)) {
            processShulkerContents((ShulkerBox) ((BlockStateMeta) item.getItemMeta()).getBlockState(), pending);
            return null;
        }
        if (Tag.ITEMS_BUNDLES.isTagged(type)) {
            return processBundle(item, (BundleMeta) item.getItemMeta(), 0);
        }
        return stripNonCurseEnchantments(item);
    }

    private ItemStack processBundle(ItemStack bundle, BundleMeta meta, int depth) {
        if (depth >= MAX_BUNDLE_DEPTH) {
            return bundle;
        }
        List<ItemStack> processedItems = new ArrayList<>();
        for (ItemStack content : meta.getItems()) {
            if (content == null || content.getType().isAir() || isBlacklisted(content.getType())) {
                continue;
            }
            if (content.getItemMeta() instanceof BundleMeta childMeta) {
                content = processBundle(content, childMeta, depth + 1);
            } else {
                content = stripNonCurseEnchantments(content);
            }
            processedItems.add(content);
        }
        meta.setItems(processedItems);
        bundle.setItemMeta(meta);
        return bundle;
    }

    private void processShulkerContents(ShulkerBox box, List<ItemStack> pending) {
        for (ItemStack content : box.getInventory().getContents()) {
            if (content == null) {
                continue;
            }
            Material type = content.getType();
            if (type.isAir() || isBlacklisted(type)) {
                continue;
            }
            ItemStack leaf = content.clone();
            if (leaf.getItemMeta() instanceof BundleMeta bundleMeta) {
                leaf = processBundle(leaf, bundleMeta, 0);
            } else {
                leaf = stripNonCurseEnchantments(leaf);
            }
            pending.add(leaf);
        }
    }

    private boolean isBlacklisted(Material material) {
        return blacklist.contains(material);
    }

    private ItemStack stripNonCurseEnchantments(ItemStack item) {
        if (!getConfig().getBoolean("remove-non-curse-enchant", true)) {
            return item;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        boolean hasMending = meta.getEnchants().containsKey(Enchantment.MENDING);
        if (meta instanceof EnchantmentStorageMeta storedMeta
                && storedMeta.getStoredEnchants().containsKey(Enchantment.MENDING)) {
            hasMending = true;
        }
        if (!hasMending) {
            return item;
        }
        if (meta instanceof EnchantmentStorageMeta storedMeta) {
            for (Enchantment enchantment : new HashSet<>(storedMeta.getStoredEnchants().keySet())) {
                if (!isCurse(enchantment)) {
                    storedMeta.removeStoredEnchant(enchantment);
                }
            }
            if (item.getType() == Material.ENCHANTED_BOOK && storedMeta.getStoredEnchants().isEmpty()) {
                ItemStack book = ItemStack.of(Material.BOOK, item.getAmount());
                book.copyDataFrom(item, type -> type != DataComponentTypes.STORED_ENCHANTMENTS
                        && type != DataComponentTypes.REPAIR_COST
                        && type != DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE
                        && type != DataComponentTypes.RARITY);
                return book;
            }
        } else {
            for (Enchantment enchantment : new HashSet<>(meta.getEnchants().keySet())) {
                if (!isCurse(enchantment)) {
                    meta.removeEnchant(enchantment);
                }
            }
        }
        item.setItemMeta(meta);
        item.setData(DataComponentTypes.REPAIR_COST, 0);
        return item;
    }

    private boolean isCurse(Enchantment enchantment) {
        String name = enchantment.getKey().getKey();
        return name.equals("binding_curse") || name.equals("vanishing_curse");
    }

    private List<ItemStack> mergeIfConfigured(List<ItemStack> items) {
        if (!getConfig().getBoolean("burst.merge-similar", true)) {
            return items;
        }
        List<ItemStack> merged = new ArrayList<>();
        for (ItemStack item : items) {
            int remaining = item.getAmount();
            for (ItemStack existing : merged) {
                if (!existing.isSimilar(item)) {
                    continue;
                }
                int capacity = existing.getMaxStackSize() - existing.getAmount();
                int added = Math.min(capacity, remaining);
                existing.setAmount(existing.getAmount() + added);
                remaining -= added;
                if (remaining == 0) {
                    break;
                }
            }
            while (remaining > 0) {
                ItemStack part = item.clone();
                int amount = Math.min(item.getMaxStackSize(), remaining);
                part.setAmount(amount);
                merged.add(part);
                remaining -= amount;
            }
        }
        return merged;
    }

    private void burst(Player player, Location location, List<ItemStack> items) {
        World world = location.isWorldLoaded() ? location.getWorld() : null;
        if (world == null) {
            throw new IllegalStateException("Cannot release " + items.size() + " pending item stack(s) for "
                    + player.getName() + ": respawn location " + location + " has no loaded world");
        }
        if (!world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            world.getChunkAt(location);
        }
        double configuredRadius = Math.max(0.0, getConfig().getDouble("burst.radius", 0.5));
        double radius = Math.min(configuredRadius, 0.5);
        if (configuredRadius > 0.5 && !loggedBurstRadiusClamp) {
            loggedBurstRadiusClamp = true;
            getLogger().warning("burst.radius=" + configuredRadius
                    + " exceeds the in-block limit 0.5 and was clamped to 0.5 "
                    + "so items stay inside the respawn block and do not clip into adjacent walls");
        }
        int pickupDelay = Math.max(0, getConfig().getInt("burst.pickup-delay-ticks", 10));
        double centerX = location.getBlockX() + 0.5;
        double centerZ = location.getBlockZ() + 0.5;
        double dropY = location.getY() + 1.0;
        for (ItemStack item : items) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double distance = Math.sqrt(random.nextDouble()) * radius;
            double offsetX = Math.cos(angle) * distance;
            double offsetZ = Math.sin(angle) * distance;
            Location dropLocation = new Location(world, centerX + offsetX, dropY, centerZ + offsetZ);
            Item entity = world.dropItem(dropLocation, item);
            entity.setPickupDelay(pickupDelay);
            entity.setVelocity(new Vector((random.nextDouble() - 0.5) * 0.08, 0, (random.nextDouble() - 0.5) * 0.08));
        }
        getLogger().info("Released " + items.size() + " pending item stack(s) for " + player.getName());
    }
}
