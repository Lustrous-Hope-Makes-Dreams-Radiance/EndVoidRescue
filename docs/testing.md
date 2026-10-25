# EndVoidRescue 测试说明

> 本次重写依据：当前 HEAD `c1f07b642185e0a73c88c17b46643152bc0e575f`，核对报告见上。

本文档说明 `EndVoidRescuePluginTest` 覆盖的功能与边界情况、关键测试设计的依据，以及编写和维护本套件时必须遵守的规则。

## 运行环境

- 测试框架：JUnit 6（`junit-jupiter` 6.1.2）+ MockBukkit（`org.mockbukkit.mockbukkit:mockbukkit-v26.1.2` 4.115.0；artifactId 必须与 `paper-api` 的 Minecraft 版本精确匹配，当前 `paper-api` 为 `26.1.2.build.74-stable`，pom 的 `apiVersion` 属性为 `26.1.2`）。
- 插件通过 `MockBukkit.load(EndVoidRescuePlugin.class)` 以**真实插件实例**加载：`onEnable` 正常执行、`config.yml` 正常读取、监听器正常注册。
- 核心私有方法 `processItem` 通过反射调用，避免为了测试而扩大生产 API。
- 运行：`mvn test`。
- 最后一次核对（HEAD `c1f07b6`，`mvn -o test`）：**Tests run: 41, Failures: 0, Errors: 0, Skipped: 4**。41 条的分布见「测试覆盖矩阵」，4 条 skipped 的原因见「附魔书退化路径」与「真实服务器验收清单」。

### 标注约定

转述自既有探针、但本次核对（HEAD `c1f07b6`）**未重新运行探针**验证的条目，标为 **〔探针实测，本次未复跑〕**；无法从仓库源码独立复核的历史结论标为 **〔历史结论，本次未复跑〕**。这些条目按原文保留，但不代表本次核对确认过。

### 已知 MockBukkit 怪癖（编写断言时必须注意）

| 怪癖 | 影响 | 测试中的处理 |
| --- | --- | --- |
| `BundleMeta.setItems` 往返会**丢弃 `REPAIR_COST` 组件**（附魔保留）〔探针实测，本次未复跑〕 | 收纳袋内的物品无法断言 `repair_cost` | `repair_cost=0` 只在独立物品路径上断言 |
| 不强制原版收纳袋 64 格容量〔探针实测，本次未复跑〕 | 可以构造原版不可能出现的深链 | 边界测试按原版容量规则构造，见下文 |
| `player.respawn()` 传送到世界出生点而非自设重生点 | 端到端测试位置断言失败 | 用 `callEvent(new PlayerRespawnEvent(player, loc, false))` 模拟重生 |
| 插件类被 `ByteBuddy.subclass()` 代理加载〔探针实测，本次未复跑〕 | 插件类不能是 `final` | `EndVoidRescuePlugin` 保持非 `final`（当前声明已满足） |
| `unloadWorld` 在玩家仍在该世界内时返回 `false`〔探针实测，本次未复跑〕 | 无法直接卸载玩家所在世界 | 先把玩家传送到其他世界再卸载 |
| 世界/区块未加载时 `dropItem`/`getChunkAt` **仍会成功**〔探针实测，本次未复跑〕 | 「无掉落物」断言可能空洞通过 | 「世界未加载」场景已无「无掉落物」断言；「区块未加载」场景仍由 `burst_unloadedChunk_loadsChunkBeforeDropping` 用 `isChunkLoaded` + 实体数双断言守住 |
| 每个测试的 `getDataFolder()` 是**独立临时目录**〔探针实测，本次未复跑〕 | 无法跨测试共享文件状态 | 持久化测试可安全断言「文件不存在」 |
| 插件日志走 JUL（`plugin.getLogger()`），可挂 `Handler` 捕获 | — | 用自定义 `Handler` 收集 `LogRecord` 断言 warning 内容 |
| 潜影盒每次读取都返回**全新副本**（`getInventory().getItem` 两次调用都不返回同一对象）〔探针实测，本次未复跑〕 | 「`processItem` 不就地改写输入」这类断言恒真、无法证伪 | 不为该行为写测试，见「不可测项」 |
| `ItemMeta` setter 与数据组件 API（`ItemStack#setData`/`getData`）是**两套互不可见的存储**〔探针实测，本次未复跑〕 | 用 meta setter 设的名称/lore 读 `getData(...)` 得到 `null`，反之亦然 | 显示属性相关断言统一走 `getItemMeta()` 路线（与生产代码读取路径一致）；不要混用两套 API |
| **`ItemStack#copyDataFrom` 未实现**，抛 `UnimplementedOperationException`（继承 `org.opentest4j.TestAbortedException`） | 生产代码一旦调用它，相关测试被记为 **skipped 而非 failed**——`mvn test` 仍显示 BUILD SUCCESS，极易误读为通过。**当前 HEAD 正处在这一状态：4 条测试被静默跳过** | 见「附魔书退化路径」。**维护规则第 6 条：看测试结果必须核对 Skipped 计数，不能只看 Failures** |
| **`MockBukkit.unmock()` 不触发本插件的 `onDisable`**（`disablePlugins` 链路在本版本下不会调用它，`pending-drops.yml` 始终不被创建） | 用它验证「关服落盘」会得到「文件不存在」，断言恒真 | `shutdownHook_persistsPendingRecordToDisk` 显式调用生产代码的 `plugin.onDisable()`；**不要改回 `unmock()`** |
| `REPAIR_COST` 在**附魔书**上的 `setData`/`getData` 往返**正常**（与收纳袋丢组件的怪癖不同） | 可以正常写出「前置：repair_cost=39」的断言 | `degradedBook_resetsRepairCost` 的前置断言实测能读回 39——本次 surefire 记录显示该测试是在 `processItem` **之后**才中止的，前置断言确实通过 |

发放在 `PlayerRespawnEvent` 里同步执行，生产代码不注册任何调度任务（不调用 `runTask` 一类 API），「调度任务异常从 `performTicks` 传播」这条怪癖**暂无测试使用**（原先的 `assertDoesNotThrow(() -> performTicks(...))` 断言已随之删除）。测试里残留的 `performTicks(2)` 调用是**空跑**——生产代码不注册调度器，这些调用不等待任何任务，保留不影响结论。

## 收纳袋嵌套：容量规则与处理深度

### 容量怎么算（原版规则）

- 一个收纳袋自身有 **64 格**空间。
- 把一个收纳袋放进另一个收纳袋时，内层收纳袋在外层**占用 4 格**（相当于外层 64 格的 1/16）。
- 内层收纳袋里的**物品同样计入外层的 64 格**——容量是递归累加的：链条里任何一格被占用，最外层都被占用。

| 嵌套层数 | 链条自身占用 | 最内层可放物品 |
| --- | --- | --- |
| 0（只有最外层 1 个袋） | 0 格 | 64 件 |
| 1 层 | 4 格 | 60 件 |
| 2 层 | 8 格 | 56 件 |
| … | 4×N 格 | 64 − 4×N 件 |
| **15 层** | 60 格 | **4 件 ← 能装物品的最深位置** |
| **16 层** | 64 格 | **0 件 ← 最内层袋必然为空，一件都放不下** |

**结论**：嵌套 15 层是玩家能构造出的最深层装物品位置（放 4 个末影水晶时恰好 64/64 满载）；嵌套 16 层时最内层收纳袋是空的，它里面再放什么都进不去。

### 插件怎么处理

`processBundle(bundle, meta, depth)` 中的 `depth` 就是嵌套层数（背包里那个袋 `depth = 0`）。处理规则：

- `depth < 16`：递归清理袋内所有物品——黑名单物品移除、带经验修补的物品执行砂轮祛魔。
- `depth >= 16`：**整袋原样保留，不做任何处理**。由上表可知这些袋必然是空的，跳过不会放过任何黑名单物品。

也就是说：**前 16 个袋（背包里的算第 1 个）会被处理，第 17 个袋起整袋跳过。**

`MAX_BUNDLE_DEPTH = 16` 即这个跳过阈值，取值依据：

1. **不能取小于 16**：否则嵌套 15 层（最深满载位置）被跳过，黑名单物品泄漏。MAX=15 时已在真实服务器实测：嵌套 15 层 + 最内层 4 个末影水晶（恰好占满 64 格），掉入虚空后水晶未被删除。〔历史结论，本次未复跑〕
2. **取大于 16 没有收益**：嵌套 16 层及更深必然是空袋，跳过分支返回原物即可。

测试代码里的 `level` 是收纳袋个数（背包里的算第 1 个），嵌套层数 = `level − 1` = 代码的 `depth`。

## 祛魔：单一实现与附魔书退化路径

祛魔只有**一条实现**：`stripNonCurseEnchantments(ItemStack)`（`EndVoidRescuePlugin.java:205`）。生产代码里**没有**原版砂轮反射路径，也没有对应的配置开关——`config.yml` 的全部键见 README「配置」。

### 处理规则

1. **配置开关**：`remove-non-curse-enchant` 为 `false` 时整条链路短路，任何物品都不动（`:206`）。
2. **触发条件**：物品必须带经验修补——普通物品看 `meta.getEnchants()`，附魔书额外看 `EnchantmentStorageMeta.getStoredEnchants()`（检测在 `:213`–`:217`，不带 `MENDING` 时在 `:218`–`:220` 原样返回）。不带 `MENDING` 的物品附魔完全不动（防误磨）。
3. **附魔处理**：
   - 附魔书（`EnchantmentStorageMeta` 分支）：逐个 `removeStoredEnchant` 掉所有非诅咒附魔；
   - 普通物品：逐个 `removeEnchant` 掉所有非诅咒附魔；
   - 两条分支都保留 `binding_curse` / `vanishing_curse`（`isCurse` 按附魔 key 名匹配，`:247`–`:250`）。
4. **`repair_cost` 归零**：普通物品分支与「保留附魔书形态」分支共用方法末尾的 `item.setItemMeta(meta); item.setData(REPAIR_COST, 0)`（`:242`–`:243`）。这两条路径的归零由同一条语句覆盖，但断言分散在两条独立路径上。
5. **显示属性**：非退化路径复用原 `meta`，名称 / lore / `custom_model_data` 天然保留。

### 附魔书退化路径（当前实现）

当附魔书祛魔后 `getStoredEnchants().isEmpty()` 且物品类型为 `ENCHANTED_BOOK` 时，新建一本普通书，再用 `copyDataFrom` 迁移数据组件（`EndVoidRescuePlugin.java:227`–`:234`）。以下代码块与 `EndVoidRescuePlugin.java:228`–`:233` 逐字一致（仅去掉方法体缩进）：

```java
ItemStack book = ItemStack.of(Material.BOOK, item.getAmount());
book.copyDataFrom(item, type -> type != DataComponentTypes.STORED_ENCHANTMENTS
        && type != DataComponentTypes.REPAIR_COST
        && type != DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE
        && type != DataComponentTypes.RARITY);
return book;
```

谓词是一张**排除表**：除下列 4 个组件外，其余组件全部从附魔书复制到普通书。

| 被排除的组件 | 为什么排除 |
| --- | --- |
| `STORED_ENCHANTMENTS` | 祛魔后不应再有任何存储附魔；普通书也不承载该组件 |
| `REPAIR_COST` | 砂轮式祛魔要求归零，不能把附魔书的 `repair_cost` 带过来 |
| `ENCHANTMENT_GLINT_OVERRIDE` | 祛魔后的书不得保留附魔闪光 |
| `RARITY` | 让普通书回到默认稀有度，不继承附魔书的稀有度组件 |

名称、lore、`custom_model_data` 等显示属性不在排除表内，因此经 `copyDataFrom` 保留。数量走的是 `ItemStack.of` 的第二个参数：源码传的就是 `item.getAmount()` **原样值**，不存在 `Math.max(1, …)` 之类的下限保护（全仓库 `Math.max` 仅出现在 `burst` 的 radius 与 pickup-delay 归零两处），也不依赖组件迁移。

### 4 条 skipped 测试及原因

`copyDataFrom` 在 MockBukkit 4.115.0 中**未实现**：`ItemStackMock.copyDataFrom(ItemStackMock.java:520)` 抛 `UnimplementedOperationException`，该异常继承 `org.opentest4j.TestAbortedException`，JUnit 因此把测试记为 **skipped 而不是 failed**。只要被处理的物品走到退化分支，测试就会在这一点中止。

| 被跳过的测试 | 触发路径（本次 surefire XML 实测栈） |
| --- | --- |
| `enchantedBook_degradesWhenEmpty_keepsAmount` | `copyDataFrom` → `stripNonCurseEnchantments:229` → `processItem:158` |
| `enchantedBook_degradeKeepsDisplayAttributes` | 同上 |
| `degradedBook_resetsRepairCost` | 同上 |
| `deepestReachableLevel_isFullyProcessed` | `copyDataFrom` → `stripNonCurseEnchantments:229` → **`processBundle:173`**（最内层放了一本附魔书，经收纳袋递归进入） |

surefire XML 的 `message` 属性为空字符串，完整信息在 `<skipped>` 的 CDATA 文本里，首行为：

```text
org.mockbukkit.mockbukkit.exception.UnimplementedOperationException: Not implemented
```

**这 4 条当前不是通过，而是没有执行。** 对它们的任何变异都不会变红；删除退化分支里的 `copyDataFrom` 调用也不会让它们失败，只会改变「是否被跳过」。也就是说，这 4 条覆盖的行为目前处于**空跑**状态。

#### 连带影响：深度边界的动态用例失效

`deepestReachableLevel_isFullyProcessed` 是 4 条深度边界测试中唯一「随 `MAX_BUNDLE_DEPTH` 常量缩放构造」的一条。它现在不执行，因此：

- 深度边界当前实际由 `vanillaMaximumFullChain_sixteenLevels_isFullyProcessed`（硬编码 16 个袋）、`bundleBeyondDepthLimit_isSkippedVerbatim`、`emptyChainToMaxLevel_survivesIntact` 守住；
- 变异矩阵中「跳过条件提前 → `deepestReachableLevel` 变红」这一格**当前不成立**（见下文修正后的矩阵）。

## 真实服务器验收清单（4 条 skipped 的行为）

以下行为在 MockBukkit 下**无法验证**（`copyDataFrom` 未实现，测试被跳过）。必须在真实 Paper 服务器上确认；确认之前，不应把这些行为当作已验证。

1. **退化后数量保留**：附魔书（只带 `MENDING` + 一个非诅咒附魔，数量 ≥ 2）在末地虚空死亡后重生，掉出的普通书数量应与原数量一致（对应 `enchantedBook_degradesWhenEmpty_keepsAmount`）。
2. **退化后显示属性保留**：带自定义名称 + 多行 lore + `custom_model_data` 的附魔书，退化为普通书后三项都应原样保留（对应 `enchantedBook_degradeKeepsDisplayAttributes`）。
3. **退化后 `repair_cost` 归零**：先给附魔书设 `repair_cost=39`，退化后的普通书该组件应为 0 或缺省（对应 `degradedBook_resetsRepairCost`）。排除表已把 `REPAIR_COST` 排除在外，理论上新建的普通书拿到的是默认值。
4. **深度边界动态用例**：构造 16 个收纳袋的嵌套链、最内层放「附魔书 + 黑名单物品」，确认最内层被完整处理（黑名单清除、附魔书退化）（对应 `deepestReachableLevel_isFullyProcessed`）。
5. **附带回归确认**：上述过程不得给玩家发放经验球。当前实现只做纯数据搬运，不触碰任何会发放经验的路径，本条是回归确认而非已知风险点。

## 测试覆盖矩阵

共 **41** 条（`@Test` 注解计数），本次执行为 37 条通过、**4 条 skipped**（下表标 ⏭）。计数以 surefire 的 `Tests run / Failures / Errors / Skipped` 为准，不能只看 BUILD 结果。

### 1. 深度边界（4 条，最关键，对应历史回归）

四个测试各司其职，职责边界由变异实测确定（见下表「能抓的变异」列）：

| 测试 | 构造 | 断言 | 能抓的变异 |
| --- | --- | --- | --- |
| `deepestReachableLevel_isFullyProcessed` ⏭ | 动态构造嵌套 MAX−1 层，最内层放黑名单/祛魔对象 | 最内层被完整处理 | **当前 skipped，抓不到任何变异** |
| `bundleBeyondDepthLimit_isSkippedVerbatim` | 嵌套 MAX 层 | 嵌套 MAX−1 层处理、嵌套 MAX 层原样返回 | 跳过条件**提前或后移**（`>= MAX-1` / `> MAX`） |
| `vanillaMaximumFullChain_sixteenLevels_isFullyProcessed` | **硬编码嵌套 15 层**（16 个收纳袋）+ 最内层 4 个末影水晶 | 水晶被清除 | 常量**改小**（16→15）、跳过条件提前 |
| `emptyChainToMaxLevel_survivesIntact` | 嵌套 MAX−1 层的空链 | 结构完整、不抛异常 | 递归在空内容时崩溃 |

**变异实测矩阵**（改坏生产代码后哪些测试变红）：

| 变异 | 变红的测试 |
| --- | --- |
| `MAX_BUNDLE_DEPTH` 16→15（常量改小） | 仅 `vanillaMaximumFullChain_sixteenLevels_isFullyProcessed` |
| `MAX_BUNDLE_DEPTH` 16→17（常量改大） | **无（全绿）** |
| 跳过条件 `depth >= MAX` → `depth >= MAX-1`（提前） | `vanillaMaximumFullChain_sixteenLevels_isFullyProcessed`、`bundleBeyondDepthLimit_isSkippedVerbatim`（`deepestReachableLevel_isFullyProcessed` **当前 skipped，不可能变红**） |
| 跳过条件 `depth >= MAX` → `depth > MAX`（后移） | 仅 `bundleBeyondDepthLimit_isSkippedVerbatim` |

**为什么常量改大抓不到、也不需要抓**：动态测试的构造随常量缩放（MAX=17 时构造 17 个袋，最内层 depth=16 仍被处理），所以常量本身改大改小都抓不到；硬编码测试只钉住「不得小于 16」。常量改大（16→17）在语义上无害——`depth >= 16` 的袋必然为空（容量规则），处理与否结果相同，因此没有测试覆盖它是设计上可接受的取舍。常量改小（16→15）会泄漏黑名单物品，由硬编码测试守住。

**`bundleBeyondDepthLimit_isSkippedVerbatim` 的断言强度分级**（避免未来改动被误认为阻塞）：

| 断言 | 强度 | 说明 |
| --- | --- | --- |
| 第 MAX 个袋的镐被磨 | **硬约束** | 阈值提前会泄漏黑名单物品（服务器实测 bug），永远不得放宽 |
| 第 MAX+1 个袋原样返回 | **characterization（行为记录）** | 原版容量规则下 depth-16 袋必然为空，处理与否在真实游戏中不可观测；钉住当前跳过语义、防止无意改动 |

若未来有意把跳过条件改为 `depth > MAX`（例如为防御 NBT 编辑构造的非原版 depth-16 袋），更新 characterization 断言即为**预期动作**——测试失败是「请做有意识决定」的提示，不是阻塞。改动时需同步更新本表与测试 javadoc。

> 修复 `copyDataFrom` 造成的跳过（或改用不依赖 MockBukkit 未实现 API 的写法）后，`deepestReachableLevel_isFullyProcessed` 会恢复执行，本节「能抓的变异」与矩阵中标注 skipped 的两格应一并还原。

### 2. 黑名单过滤（2 条）

- `everyConfiguredBlacklistMaterial_isRemovedAtTopLevel`：config.yml 里 6 个黑名单材质（鞘翅、潜影壳、末影箱、黑曜石、末影珍珠、末影水晶）逐一验证 `matchMaterial` 拼写正确且 `processItem` 返回 `null`——若有人把 `OBSIDIAN` 拼错，`reloadSettings` 的 warning 是静默的，只有这条会红。
- `blacklistInsideMiddleBundleLevel_isRemoved`：黑名单物品藏在收纳袋**中间层**时同样被移除（非最内层路径），且普通物品数量不受影响——防只清理顶层。

### 3. 潜影盒（5 条）

- `shulkerWithContents_extractsToPending`：非空潜影盒内容逐件净化后进入 pending，盒本身消失（返回 `null`）。
- `emptyShulker_removedWithoutPending`：空潜影盒直接消失，不产生 pending 记录。
- `bundleInsideShulker_isPurifiedThenPending`：盒内收纳袋先按收纳袋规则递归净化，再整体进 pending。
- `nestedShulker_isKeptAsPlainItem_notRecursivelyOpened`：**盒中盒不递归展开**——内层盒按普通物品整体保留进 pending，内层内容不被提取、不被净化。含前置断言确认内层盒确实装着剑，避免空洞通过。
- `dyedShulkerBox_extractedToPendingLikePlainOne`：用 `WHITE_SHULKER_BOX` 走一遍同样的路径。生产代码判定用 `Tag.SHULKER_BOXES.isTagged(type)`，染色变体都在该标签内；若改成只认 `SHULKER_BOX` 的等值比较，本测试变红。

### 4. 砂轮式祛魔（8 条）

- `grindstone_keepsCurses_removesOthers_resetsRepairCost`：普通物品路径。`MENDING` + `SHARPNESS` 被磨掉，`BINDING_CURSE` / `VANISHING_CURSE` 保留，`repair_cost` 从 37 归零（含前置断言）。
- `withoutMending_enchantmentsUntouched`：不带 `MENDING` 的物品附魔完全不动——防误磨。
- `enchantedBook_curseOnlyKeepsDisplayAttributes`：**不退化路径**（仍有诅咒 → 保持附魔书形态）名称 / lore 保留，`MENDING` 被移除、诅咒保留。该路径复用原 meta，这条测试钉住「未来重构不得破坏这条正确行为」。
- `enchantedBook_degradesWhenEmpty_keepsAmount` ⏭：附魔书祛尽附魔后退化为普通书且数量保留——**当前 skipped**。
- `enchantedBook_degradeKeepsDisplayAttributes` ⏭：退化后自定义名称、lore、`custom_model_data` 三项保留——**当前 skipped**。
- `degradedBook_resetsRepairCost` ⏭：退化路径的 `repair_cost` 归零。它与 `grindstone_keepsCurses_removesOthers_resetsRepairCost` 钉的是两条不同路径：后者走普通物品的 `getEnchants` 分支，前者走 `EnchantmentStorageMeta` 分支里「新建普通书 + `copyDataFrom` 排除 `REPAIR_COST`」的代码——**当前 skipped**。
- `configDisablesGrinding_allEnchantmentsKept`：`remove-non-curse-enchant: false` 时普通物品全部保留。
- `configDisablesGrinding_enchantedBookKeepsStoredEnchants`：同一开关对附魔书同样生效。附魔书走 stored enchants 分支，与普通物品的 `getEnchants` 分支是两条独立代码路径，必须单独钉住；删掉 `stripNonCurseEnchantments` 开头的配置早退后，两条同时变红。

> 退化路径的三条断言在修好 `copyDataFrom` 的 MockBukkit 支持之前无法执行，其行为只能按上文「真实服务器验收清单」在真实服务器确认。

### 5. 持久化与合并（5 条）

> **写盘时机（决策 A）**：`put` 与 `remove` **都不写盘**，只改内存（内存是主副本）；唯一写盘时机是插件关闭时的 `onDisable → PendingDropStore.save()`。因此断言分两类：**内存视图**（`pluginStore()`，进程内真相）与**磁盘视图**（`diskStore()` / `pendingOf()`，只在显式 `save()` 或 `onDisable` 之后才可信）。断言磁盘前必须确认该路径已经落过盘，否则断言恒真。

- **YAML 往返**（`pendingDropStore_roundTripAndRemoval`）：`put` → `save()`（模拟关服）→ 新实例从磁盘读回物品（含附魔）；`remove` → `save()` → 读回为空。覆盖「正常关闭 → 重启」这条决策 A 唯一保证的路径。
- **空 put 且无记录不写盘**（`pendingDropStore_emptyPutWithoutRecord_doesNotWriteFile`）：文件不得被创建——防死亡时背包为空却反复重写文件。注意：`put` 不写盘后该断言恒真，真正守住的是 `put` 开头的早退分支（空列表且玩家无记录时直接 return），删掉该分支会由下一条的空段断言抓到。
- **remove 最后一条记录不留空段**（`pendingDropStore_removeLastRecord_leavesNoEmptyPlayersSection`）：钉住决策 A 的三个方向——(1) `put` 不写盘：put 后解析磁盘的 `players` 段为 `null`；(2) `remove` 不写盘：显式 `save()` 留下磁盘快照后，`remove` 之后磁盘上那条旧记录**必须仍在**（陈旧记录只活到关服，见「已知风险」）；(3) `save` 写的是内存快照：再次 `save()` 后该记录与空 `players` 段一起消失（文件不得残留 `players:` 空段）。变异：把 `save()` 加回 `PendingDropStore.remove()` 末尾，(2) 变红。
- **写盘失败不抛回调用方**（`pendingDropStore_saveFailure_keepsMemoryAndDoesNotThrow`）：数据目录被文件占住使 `save()` 必然 `IOException`，此时必须记一条 severe（含 `Unable to save pending drops`）且不抛异常，内存主副本仍可读。变异：`save()` 把 `IOException` 重新抛出后，`assertDoesNotThrow` 变红。
- **合并**（`mergeSplitsOversizedStacks_conservingTotal`）：100 个圆石 → 64+36，总量守恒——防合并丢数量。

### 6. 死亡入口与记录清理（5 条）

- `deathWithoutDamageCause_notTriggeredAndNoNPE`：死因为 `null`（原版 `/kill`、或从未受过伤害的玩家）时不触发改写，也不抛 NPE。变异：删掉 `isTriggerDeath` 的 null 检查后，`getLastDamageCause().getCause()` 抛 NPE。
- `emptyDeath_keepsExistingPendingRecord`：触发死亡但这次没有可保留物品时，**已有 pending 必须原样留下**等下次重试（死亡处理先把 `store.get` 装进 pending，所以空背包不会清掉旧记录）。变异：改回「pending 为空就 `store.remove`」后旧记录消失，本测试变红。
- `death_mergesStaleRecordWithNewItems`：**失败重试的核心闭环**。旧记录 STONE×5 与本次潜影盒里的 EMERALD×3 必须合并后一起写回（内存视图断言合并结果；再显式 `pluginStore().save()` 后用 `pendingOf` 断言落盘形态）。变异：把 `onPlayerDeath` 里的 `store.get(playerId)` 改成 `new ArrayList<>()` 后旧记录被丢弃，本测试与 `emptyDeath_keepsExistingPendingRecord` 同时变红。
- `emptyDeathWithoutStaleRecord_removesRecord`：没有旧记录、这次也没有可保留物品时，死亡后 store 仍为空且不写出玩家段。本测试**不**钉 `pending.isEmpty()` 时的 `store.remove`（没有旧记录时删掉那段仍绿），空记录壳的清理由 `PendingDropStore` 自己的测试钉住。
- `respawnWithNoPending_callsStoreRemoveWithoutDropping`：重生时该玩家无 pending → 不得误删其他玩家的记录，也不得产生掉落。`put` 不写盘，因此「其他玩家的记录还在」只能从内存主副本读，本断言因此能真正抓到误删。本测试同样不钉 `onPlayerRespawn` 开头 `store.remove` 是否被调用。

### 7. 端到端事件流（7 条）

- `endToEnd_endVoidDeath_rescuesShulkerContentsAtRespawn`：末地 + VOID 死亡 → 背包入 pending → 重生事件内同步 burst；掉落数量、祛魔结果、拾取延迟、落点限制在重生方块内、记录清理全部断言。断言始终走内存主副本（本测试不调 `onDisable`，磁盘为空）。
- `endToEnd_quitBeforeRespawn_rejoinStillBurstsPending`：情况 A——死亡后、重生事件前离线，重进后再重生，待返还物品仍要散落；离线期间不得清掉内存 pending，未重生时不得散落。变异：死亡时不再 `store.put`，则离线期间记录为空，本测试变红。
- `shutdownHook_persistsPendingRecordToDisk`：死亡产生内存记录（此时文件不存在）→ 调生产代码 `onDisable()` → 文件必须被创建、重启后读回同一条物品。这是**唯一**钉住「关服写盘」的测试。变异：删掉 `onDisable` 里的 `store.save()` 后本测试变红。注意：必须显式调 `plugin.onDisable()`，`MockBukkit.unmock()` 不会触发它（见怪癖表）。
- `endToEnd_deathNotInEnd_pluginStaysOut`：非末地死亡（同死因）不得触发改写。
- `endToEnd_endDeathButWrongDamageCause_notTriggered`：末地内非 VOID 死亡不得触发。
- `endToEnd_respawnWithoutDeath_dropsNothingAndStaysSilent`：无死亡记录时直接重生，不得有任何掉落。
- `burstFailure_unloadedWorld_keepsPendingRecord`：重生点世界已卸载时 `burst` 抛异常，调用方**不得** `store.remove`。变异：把 `throw IllegalStateException` 改回 warning 后 return，记录会被清掉，本测试变红。

### 8. burst 物理参数（3 条）

- `burstRadius_aboveHalfBlock_isClampedAndWarnsOnceUntilReload`：`burst.radius` 配置 2.0 时截断为 0.5，物品中心留在重生格内；超限 warning 在 `reloadSettings` 之前只出现一次，reload 之后下一次超限必须再次 warning。变异：删掉截断时散落断言变红；删掉一次性标记或 `reloadSettings` 里的重置时，对应 warning 次数断言变红。
- `burstRadius_zeroOrNegative_landsExactlyOnBlockCenter`：配置 `0` 与 `-1` 时负值被 `Math.max(0.0, …)` 归零，物品恰好落在重生格中心、不丢件、不误报超限 warning。变异：删掉 `Math.max(0.0, …)` 后偏移方向翻转但仍落在格内，本测试用「落点精确等于格中心」变红。
- `burst_unloadedChunk_loadsChunkBeforeDropping`：掉落点区块未加载时先加载再掉落，物品不丢失（`isChunkLoaded` + 实体数双断言）。

### 9. 配置加载（2 条）

- `reloadSettings_unknownBlacklistMaterial_logsWarning`：无法识别的黑名单材质必须 warning（`Unknown blacklist material: …`），合法材质仍加载，且**旧名单被整体替换**（不在新名单里的旧材质必须消失）。变异：删掉未知材质的 warning 分支后日志断言变红。
- `invalidTriggerConfig_fallsBackToEndVoid`：`trigger-world` / `trigger-cause` 配成非法枚举值时回退到 `THE_END` + `VOID`，死亡仍要触发。变异：删掉任一 catch 回退（改成直接 `valueOf` 或返回 `null`）后，本测试抛异常或不再 `keepInventory`。

### 10. 不可测项（设计说明，非行为）

- **`stripNonCurseEnchantments` 签名为返回 `ItemStack`**：当前签名确为 `private ItemStack stripNonCurseEnchantments(ItemStack)`，仅插件内 2 处调用、无外部 API 影响。这是设计决策说明，不是可观测行为，**无法用测试验证**，不属于测试覆盖目标。〔历史结论，本次未复跑〕关于「该签名由 `void` 变更而来、以及曾用 `setType(...)` 实现」的历史说明，无法从仓库源码复核；如未来确实需要改用 `ItemStack#setType`，注意它在 paper-api 26.1.2 上标注为 `@Deprecated`（本次 javap 实测确认），行为等价性由上述祛魔测试覆盖。
- **「`processItem` 不就地改写输入物品」无法在 MockBukkit 下验证**〔探针实测，本次未复跑〕：对同一个 `ShulkerBox` handle 连续两次 `getInventory().getItem(0)` 返回的是两个不同对象，即潜影盒内容每次读取都是全新副本。因此「盒内原物未被祛魔」这类断言恒真。该测试已删除，不保留假覆盖。`clone()` 在真实 Paper 上是必需的（真实 `ShulkerBox` 返回同一容器实例），要钉住它得改用真实服务器验证。

### 11. 设计说明归档

生产代码里的注释非常克制，详细讨论归档到本文档。实测注释分布：`EndVoidRescuePlugin.java` **全文件无注释**；`PendingDropStore.java` 有 6 处（4 行注释 + 2 段 javadoc）。

| 主题 | 生产代码注释位置 | 详细讨论 |
| --- | --- | --- |
| 空列表与空段的处理策略 | `PendingDropStore.put`（`:28`、`:37`） | §5（三条测试钉住） |
| 读取顺序不保证但本场景无关 | `PendingDropStore.get`（`:49`） | §5 |
| 无记录可清时的空壳处理 | `PendingDropStore.remove`（`:62`）、`pruneEmptyPlayersSection`（`:70`） | §5 |
| 写盘失败保留内存主副本 | `PendingDropStore.save`（`:80`–`:84` javadoc） | 「已知限制 → 跨进程持久化不再保证」 |
| 附魔书退化分支 | 生产代码无注释 | §4（3 条断言当前 skipped，须按「真实服务器验收清单」确认） |
| burst 中途失败整单保留 | 生产代码无注释 | 「已知风险」 |

**原则**：生产代码注释只保留「为什么这样写」的结论 + 指向本文档；推导过程、探针数据、变异矩阵等细节留在本文档，避免生产代码被讨论性注释淹没。

## 已知限制

### 死亡后离线再领取

发放在 `PlayerRespawnEvent` 里同步执行，没有登录补发，插件也不注册 `PlayerQuitEvent` 监听器。因此「重连后仍可领取」只覆盖情况 A：死亡之后、重生事件触发之前离线，重进并触发重生后散落——**前提是服务器进程没有结束**：这条路径完全依赖内存副本，记录此时尚未写盘。

### 跨进程持久化不再保证（决策 A）

`put` / `remove` 只改内存，唯一写盘时机是关服 `onDisable → save()`。因此：

| 终止方式 | 磁盘状态 | 后果 |
| --- | --- | --- |
| 正常关闭（`stop` / 重启） | 内存快照完整落盘 | 待领取记录仍在；已发放记录的陈旧副本被清掉 |
| 异常终止（崩溃 / `kill -9` / OOM / 容器强杀 / 断电） | 停在最后一次写盘 | 自那以后的所有变更丢失，包括刚死亡产生的待领取记录；「发放失败重试」的旧记录若从未落盘也一并失效 |

玩家死亡后离线、服务器在离线期间异常终止、玩家重连的场景会**永久丢失**该次待领取物品。这是决策 A 明确的取舍：以「关服时一次性写盘」换取死亡/重生路径零写盘。若将来要恢复跨进程保证，做法是把 `save()` 加回 `PendingDropStore.put`（`remove` 是否写盘是独立决定，见「已知风险」）。

### 世界未加载守卫

`isWorldLoaded()` 只防 `getWorld()` 在世界已卸载时抛异常，以及位置没有世界时的 NPE。同步发放后，重生事件发生时世界已经加载，这个分支在正常流程里不可达，保留为防御。

测试 `burstFailure_unloadedWorld_keepsPendingRecord` 手动卸载世界后触发重生，钉住的不是这个守卫本身可达性，而是「`burst` 抛异常 → 调用方 catch → 记录保留」这条异常路径：把 `throw IllegalStateException` 改成 warning 后 return，记录会被 `store.remove` 清掉，该测试变红。

### 光标与合成栏不做处理

`onPlayerDeath` 只处理 `player.getInventory()`，不碰光标（`getItemOnCursor`）和顶部界面（`getTopInventory`，生存模式即 2×2 合成栏）。这两处完全交给原版死亡结算：背包放得下时退回背包，背包满时可能直接消失，与 README「功能」章节的说明一致。

早期版本曾对光标和合成栏做黑名单/潜影盒处理，现已整体移除，对应的 6 个测试一并删除。不要再按「光标/合成栏进 pending」的旧前提评估行为或补测试——当前语义就是原版接管。

## 已知风险

### 已发放记录在下一次落盘前仍留在文件里

`remove` 不写盘，所以玩家领取（`burst` 成功）之后 `pending-drops.yml` 直到下一次关服才被改写。磁盘上的陈旧记录与内存已经不同步。

后果：若在「发放成功」与「下一次落盘」之间服务端异常终止，重启后该记录会被读回来，玩家下次在末地死亡时**再领一份**（重复发放）。

评估：窗口在正常关闭时关闭，只有异常终止才暴露；本插件对整单记录本就有同类重复风险（`burst` 中途失败会整单重发，见下一条）。若要收紧，把 `save()` 同时加回 `PendingDropStore.remove` 即可——代价是每次重生/空死亡都重写整个文件，这正是决策 A 想省掉的写盘。

`pendingDropStore_removeLastRecord_leavesNoEmptyPlayersSection` 用「remove 后磁盘仍含该记录」这一条断言把本行为钉住（变异：把 `save()` 加回 `remove()` 后该断言变红）。

### 发放循环中途失败会重发已落地物品

`onPlayerRespawn` 在 `burst` 正常返回后才 `store.remove`。`burst` 抛异常时 catch 后直接 return，整单记录留下，下次死亡把旧记录读回来合并再发。

若 5 件里前 3 件 `dropItem` 已落地、第 4 件抛异常：记录仍是 5 件。玩家捡起地上 3 件后，下次重试会把 5 件再掉一次，前 3 件变成两份。

真实 Paper 上重生点世界和区块已经加载，`dropItem` 中途抛异常的概率很低（非法位置、发放途中卸世界，或其他插件取消掉落）。**接受这个风险，不改 burst。** 不返回「已掉落数量」：实体生成不等于玩家还没捡，合并拆堆后下标也对不上原始记录；`setVelocity` 才抛时物品已经落地，下标仍会算错。也不改成失败即 `store.remove`：那会把「一次都没落地」的失败一起丢弃。

## 维护规则（防止假阳性）

1. **前置存在性断言**：任何「处理后被移除」的断言之前，必须先断言「处理前该物品确实存在且数量正确」。没有前置断言的移除断言可能是空洞通过（旧测试曾在 `BundleMeta.setItems` 静默丢弃内容时空洞通过）。
2. **绝对值 + 相对值双钉**：边界测试不能只动态读取常量（构造随常量缩放，常量改错时测试会跟着一起绿），必须同时存在硬编码绝对层数的用例（当前为嵌套 15 层）。动态用例钉住跳过条件相对常量的位置（提前/后移），硬编码用例钉住常量不得改小；常量改大无害且无测试覆盖，属可接受取舍（见上文变异实测矩阵）。
3. **变异验证**：新增或修改边界测试后，把生产常量改坏（如 16→15），测试**必须变红**；恢复后必须全绿。未通过变异验证的边界测试视为无效。
4. **不为测试改生产逻辑**：仅允许可见性调整（如反射调用私有方法）；生产行为问题应修生产代码，而不是迁就测试。
5. **持久化断言先确认写盘时机**：`put` / `remove` 都不写盘，`diskStore()` / `pendingOf()` 只在显式 `save()` 或 `onDisable()` 之后才有内容可读。写磁盘断言前先问「这一刻谁写过盘」，否则断言会恒真（曾出现：`put` 不写盘后，`diskStore().get(...)` 恒为空，多条断言在失败与通过之间失去区分度）。
6. **看测试结果必须核对 Skipped 计数**：只读 `BUILD SUCCESS` 与 `Failures` 会漏掉被跳过的测试。生产代码一旦调用 MockBukkit 未实现的 API（当前为 `ItemStack#copyDataFrom`），断言会变成 **skipped 而非 failed**，测试看似通过实则没有执行。每次跑完 `mvn test` 都要核对 `Tests run / Failures / Errors / Skipped` 四个数字，任何**非预期**的 skipped 都必须当失败处理；新增调用 MockBukkit API 的生产代码后，必须先确认它不会把断言变成空跑。
