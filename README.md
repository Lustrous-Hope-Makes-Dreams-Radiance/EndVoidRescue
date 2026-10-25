# 末地虚空救援

> 本次重写依据：当前 HEAD `c1f07b642185e0a73c88c17b46643152bc0e575f`，核对报告见上。

一个 Minecraft Paper 插件，为 [CrCraft](https://github.com/LHM056awa/CrCraft-Info) 服务器实现**末地虚空救援方案**。

玩家在末地因虚空死亡时，插件会保留背包中的普通物品，并在玩家重生后将待返还物品散落在重生点附近。

## 适用环境

- Java 25
- Paper 26.1.2, 26.2

## 功能

- **触发条件**：仅在配置的世界环境和死亡原因同时匹配时触发，默认是末地（`THE_END`）和虚空伤害（`VOID`）。两者任一不匹配就不改写任何东西；死因缺失（例如原版 `/kill`、或从未受过伤害）同样不触发。
- **黑名单**：丢弃玩家背包中的黑名单物品，包括藏在收纳袋和潜影盒里的——默认名单为鞘翅、潜影壳、末影箱、黑曜石、末影珍珠、末影水晶。
- **砂轮式祛魔**：仅对带有经验修补（`MENDING`）的物品执行——移除全部非诅咒附魔、保留绑定诅咒与消失诅咒、`repair_cost` 归零。附魔书祛到一个附魔都不剩时退化为普通书，数量与显示属性（自定义名称、lore、`custom_model_data`）保留。该规则由 `remove-non-curse-enchant` 开关控制。
- **收纳袋**：保留在原槽位，只重写其内部物品列表；嵌套收纳袋递归净化，前 16 个袋会被处理，更深的袋整袋原样保留。
- **潜影盒**：盒体本身丢弃，盒内物品逐件净化后转入待返还列表；盒中盒不递归展开，按普通物品整体保留。
- **合并**：按配置合并相同的可堆叠物品，超出单堆上限时自动拆成多堆。
- **散落**：玩家重生时同步在重生点所在方块内随机散落（不使用调度任务）。散落半径默认 0.5 格且不允许更大，配置成负值按 0 处理。
- **持久化语义**：待返还物品以内存为主副本，`pending-drops.yml` 只在插件关闭时写入。同一服务器进程内，玩家在**死亡之后、重生事件触发之前**离线，重连并重生后仍可继续领取；发放失败时记录保留，下次死亡合并后重试。跨进程的保证见「[持久化](#持久化)」。
- 光标和合成栏里的物品不做处理。

## 构建

运行：

```bash
mvn clean package
```

## 安装

将构建好的 JAR 复制到服务器的 `plugins` 目录，然后启动服务器。

首次启动后编辑：

```text
plugins/EndVoidRescue/config.yml
```

修改配置后需要重启插件或服务器才能生效。

## 配置

完整默认配置（与 `src/main/resources/config.yml` 逐键一致）：

```yaml
trigger-world: THE_END
trigger-cause: VOID
pending-drops:
  save-mode: immediate
blacklist:
  - ELYTRA
  - SHULKER_SHELL
  - ENDER_CHEST
  - OBSIDIAN
  - ENDER_PEARL
  - END_CRYSTAL
remove-non-curse-enchant: true
burst:
  radius: 0.5
  merge-similar: true
  pickup-delay-ticks: 10
```

### 选项说明

- `trigger-world`：触发救援的世界环境，使用 Bukkit 的 `World.Environment` 名称。
- `trigger-cause`：触发救援的死亡原因，使用 Bukkit 的 `DamageCause` 名称。
- `pending-drops.save-mode`：待返还物品的保存时机。`immediate`（默认）在死亡或重生处理后立即保存；`shutdown` 仅在插件关闭时保存。
- `blacklist`：死亡时直接丢弃的材料名称列表。
- `remove-non-curse-enchant`：是否启用经验修补触发的砂轮式祛魔规则。
- `burst.radius`：重生点所在格内的水平散落程度（格）。有效上限 `0.5`，更大的值会被截断，避免物品落到邻格卡墙。
- `burst.merge-similar`：是否在发放前合并相同的可堆叠物品。
- `burst.pickup-delay-ticks`：生成物品实体后的拾取延迟，单位为 tick。

## 持久化

默认情况下，死亡和重生时更新内存中的待返还物品并立即写入 `plugins/EndVoidRescue/pending-drops.yml`；将 `pending-drops.save-mode` 设为 `shutdown` 后，改为仅在插件关闭时写入。启动时读取该文件；`shutdown` 模式下服务器崩溃或被强制终止时，尚未写盘的变更不会保存。旧配置缺少新增键时，插件会补入默认值并记录警告。

## 文档

- [插件原理图](docs/plugin-diagram.md)
- [测试说明](docs/testing.md)
