package cn.endvoidrescue;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

final class PendingDropStore {
    private final File file;
    private final YamlConfiguration data;
    private final Logger logger;

    PendingDropStore(File dataFolder, Logger logger) {
        this.file = new File(dataFolder, "pending-drops.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
        this.logger = logger;
    }

    synchronized void put(UUID playerId, List<ItemStack> items) {
        String path = "players." + playerId;
        // 空列表且玩家无记录时，数据无变化，跳过写盘。
        if (items.isEmpty() && !data.contains(path)) {
            return;
        }
        data.set(path, null);
        for (int index = 0; index < items.size(); index++) {
            data.set(path + "." + index, items.get(index));
        }
        if (items.isEmpty()) {
            // 空列表不留下空记录，否则会被序列化成 "players.<uuid>: {}"。
            pruneEmptyPlayersSection();
        }
        save();
    }

    synchronized List<ItemStack> get(UUID playerId) {
        String path = "players." + playerId;
        ConfigurationSection section = data.getConfigurationSection(path);
        if (section == null) {
            return new ArrayList<>();
        }
        List<ItemStack> result = new ArrayList<>();
        // getKeys(false) 返回 Set，顺序不保证；但本场景中物品最终随机散落，顺序无关紧要。
        for (String key : section.getKeys(false)) {
            ItemStack item = section.getItemStack(key);
            if (item != null && !item.getType().isAir()) {
                result.add(item);
            }
        }
        return result;
    }

    synchronized void remove(UUID playerId) {
        String path = "players." + playerId;
        if (!data.contains(path)) {
            // 没有记录可清理（例如死亡时背包为空），不重写文件：重生/登录事件很频繁。
            // 仅当残留 "players: {}" 空壳时才清理一次并写盘。
            if (pruneEmptyPlayersSection()) {
                save();
            }
            return;
        }
        data.set(path, null);
        pruneEmptyPlayersSection();
        save();
    }

    /** 所有玩家记录都清空后，把空的 players 段一并删除，避免文件里留下 "players: {}"。返回是否发生了清理。 */
    private boolean pruneEmptyPlayersSection() {
        ConfigurationSection players = data.getConfigurationSection("players");
        if (players != null && players.getKeys(false).isEmpty()) {
            data.set("players", null);
            return true;
        }
        return false;
    }

    /**
     * 内存中的 data 是主副本，磁盘只是尽力持久化。
     * 写盘失败时记 severe 并留下内存数据：本次进程内仍能领取；
     * 重启后未落盘的记录丢失，是磁盘失败时的必然取舍。
     * 不得把 IOException 抛回 onPlayerDeath：此时背包已经改完，
     * 异常会让调用方丢失这份 pending，玩家两头落空。
     */
    private void save() {
        try {
            data.save(file);
        } catch (IOException exception) {
            logger.log(Level.SEVERE, "Unable to save pending drops to " + file.getAbsolutePath()
                    + "; in-memory records are kept for this process and will be lost on restart", exception);
        }
    }
}
