# EndVoidRescue 测试说明

本文档说明 `EndVoidRescuePluginTest` 覆盖的功能与边界情况、关键测试设计的依据，以及编写和维护本套件时必须遵守的规则。

## 运行环境

- 测试框架：JUnit 6 + MockBukkit（`org.mockbukkit.mockbukkit:mockbukkit-v26.1.2`，版本必须与 `paper-api` 的 Minecraft 版本精确匹配）。
- 插件通过 `MockBukkit.load(EndVoidRescuePlugin.class)` 以**真实插件实例**加载：`onEnable` 正常执行、`config.yml` 正常读取、监听器正常注册。
- 核心私有方法 `processItem` 通过反射调用，避免为了测试而扩大生产 API。
- 运行：`mvn test`。

### 已知 MockBukkit 怪癖（编写断言时必须注意）

| 怪癖 | 影响 | 测试中的处理 |
| --- | --- | --- |
| `BundleMeta.setItems` 往返会**丢弃 `REPAIR_COST` 组件**（附魔保留） | 收纳袋内的物品无法断言 `repair_cost` | `repair_cost=0` 只在独立物品路径上断言 |
| 不强制原版收纳袋 64 格容量 | 可以构造原版不可能出现的深链 | 边界测试按原版容量规则构造，见下文 |
| `player.respawn()` 传送到世界出生点而非自设重生点 | 端到端测试位置断言失败 | 用 `callEvent(new PlayerRespawnEvent(player, loc, false))` 模拟重生 |
| 插件类被 `ByteBuddy.subclass()` 代理加载 | 插件类不能是 `final` | `EndVoidRescuePlugin` 保持非 `final` |
| `unloadWorld` 在玩家仍在该世界内时返回 `false` | 无法直接卸载玩家所在世界 | 先把玩家传送到其他世界再卸载（探针实测） |
| 世界/区块未加载时 `dropItem`/`getChunkAt` **仍会成功** | "无掉落物"断言可能空洞通过 | 「世界未加载」场景已无「无掉落物」断言（原断言随 warning 断言一并删除，暂无测试使用）；「区块未加载」场景仍由 `burst_unloadedChunk_loadsChunkBeforeDropping` 用 `isChunkLoaded` + 实体数双断言守住 |
| 每个测试的 `getDataFolder()` 是**独立临时目录** | 无法跨测试共享文件状态 | 持久化测试可安全断言"文件不存在"（探针实测） |
| 插件日志走 JUL（`plugin.getLogger()`），可挂 `Handler` 捕获 | — | 用自定义 `Handler` 收集 `LogRecord` 断言 warning 内容（探针实测） |
| 潜影盒每次读取都返回**全新副本**（`getInventory().getItem` 两次调用都不返回同一对象） | 「processItem 不就地改写输入」这类断言恒真、无法证伪 | 不为该行为写测试，见「已知限制 → 无法在 MockBukkit 下验证的项」（探针实测） |
| `ItemMeta` setter 与数据组件 API（`ItemStack#setData`/`getData`）是**两套互不可见的存储** | 用 meta setter 设的名称/lore 读 `getData(...)` 得到 `null`，反之亦然 | 显示属性相关断言统一走 `getItemMeta()` 路线（与生产代码读取路径一致）；不要混用两套 API（探针实测） |
| `ItemStack#copyDataFrom` **未实现**，抛 `UnimplementedOperationException`（继承 `TestAbortedException`） | 生产代码一旦调用它，相关测试被记为 **skipped 而非 failed**——`mvn test` 显示 BUILD SUCCESS，容易误读为通过。曾导致 4 条测试静默失效（含收纳袋深度边界的 `deepestReachableLevel`） | 生产代码已改回 `setType`，不再调用该方法。**维护规则：看测试结果必须核对 Skipped 计数，不能只看 Failures**；新增生产代码调用 MockBukkit 未实现的方法前，先确认不会把断言变成空跑（探针实测） |
| `REPAIR_COST` 在**附魔书**上的 `setData`/`getData` 往返**正常**（与收纳袋丢组件的怪癖不同） | 可以正常写出「前置：repair_cost=39」的断言 | `degradedBook_resetsRepairCost` 的前置断言实测能读回 39（探针实测） |

该表末行的「已改回 `setType`」**截至本次提交与实际不符**：当前 HEAD 的 `stripNonCurseEnchantments` 仍调用 `ItemStack#copyDataFrom`，4 条 skipped（`deepestReachableLevel_isFullyProcessed`、`enchantedBook_degradesWhenEmpty_keepsAmount`、`degradedBook_resetsRepairCost`、`enchantedBook_degradeKeepsDisplayAttributes`）即源于此。本次提交只适配 PendingDropStore 语义，未修该问题。

本节后续提到的 `vanillaGrindstone` / `use-vanilla-grindstone` / `degradeToPlainBook` / `extractShulkerContents` 在当前 HEAD 中**不存在**（属历史版本），相应表格与测试名（`vanillaGrindstone_unavailableUnderMockBukkit`、`useVanillaGrindstoneFlag_readByReloadSettings`）为过时描述。

发放在 `PlayerRespawnEvent` 里同步执行后，生产代码不再注册调度任务，「调度任务异常从 `performTicks` 传播」这条怪癖**暂无测试使用**（原先的 `assertDoesNotThrow(() -> performTicks(...))` 断言已随之删除）。测试里残留的 `performTicks(2)` 调用是空跑，保留不影响结论。

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

`processBundle(bundle, depth)` 中的 `depth` 就是嵌套层数（最外层 `depth = 0`）。处理规则：

- `depth < 16`（嵌套 0~15 层）：递归清理袋内所有物品——黑名单物品移除、带经验修补的物品执行砂轮祛魔。
- `depth >= 16`（嵌套 16 层及更深）：**整袋原样保留，不做任何处理**。由上表可知这些袋必然是空的，跳过不会放过任何黑名单物品。

`MAX_BUNDLE_DEPTH = 16` 即这个跳过阈值，取值依据：

1. **不能取小于 16**：否则嵌套 15 层（最深满载位置）被跳过，黑名单物品泄漏。MAX=15 时已在真实服务器实测：嵌套 15 层 + 最内层 4 个末影水晶（恰好占满 64 格），掉入虚空后水晶未被删除。
2. **取大于 16 没有收益**：嵌套 16 层及更深必然是空袋，跳过分支返回原物即可。

## 祛魔引擎：原版反射优先，内置实现兜底

祛魔有两条实现，由 `use-vanilla-grindstone`（默认 true）选择：

| 引擎 | 实现 | 何时生效 |
| --- | --- | --- |
| 原版砂轮 | `VanillaGrindstone` 反射调 NMS 砂轮配方 | 反射解析成功（真实 Paper） |
| 内置 | `stripNonCurseEnchantmentsBuiltIn` | 配置关闭，或反射解析/调用失败 |

**为什么要反射**：公开 API 没有砂轮计算能力——`GrindstoneInventory` 是被动容器（结果由服务端在容器 tick 时写入 `setResult`），不存在 `GrindstoneRecipe` 接口，`RecipeType` 也没有 `GRINDSTONE`。想让原版算，只能走 NMS。收益是输出堆叠由原版构造，#8 那类「退化时丢显示属性」的问题从根上不会发生。

**为什么必须留兜底**：反射依赖内部映射，MC 小版本升级即可能打碎；且 MockBukkit 是 API 的纯 Java 重实现，classpath 上没有 NMS 类。因此解析失败时判定不可用并回退内置实现（只记一次 warning，不逐物品重试），而不是让祛魔整体失效——后者会让物品带着 MENDING 存活，与插件声明冲突。

**反射的实现要点**：

- 类名/方法名**不做硬编码断言**。`VanillaGrindstone` 按「形状」发现目标方法（静态、返回 NMS `ItemStack`、参数为 `(ItemStack, boolean keepCurses)`），映射改名时仍可能工作；形状不符即判定不可用。本仓库无法离线核对 26.1.2 的确切签名，这是刻意的取舍。
- 桥接优先 `asCraftMirror`（保留 `CraftItemStack` 身份），退化到 `asBukkitCopy`。
- 只调纯计算方法，**绝不触碰 `takeResult` 一类会发放经验球的路径**——死亡净化场景绝不能给玩家经验。
- 捕获 `Throwable`（含 `NoSuchMethodError`）：映射变了也不能让 `onPlayerDeath` 炸掉。

**测试边界（重要）**：MockBukkit 下原版路径恒不可用，`vanillaGrindstone_unavailableUnderMockBukkit` 钉住这一前提。也就是说**本套件的全部祛魔断言只覆盖内置回退路径**；原版路径的行为等价性无法在 CI 验证，必须真实服务器验收：

1. 启动日志应打印 `grindstone engine: vanilla`（若打印 `built-in (vanilla unavailable)` 说明反射没解析成功，需按 warning 修正形状匹配）。
2. 带自定义名称 + lore、只有 MENDING + 一个非诅咒附魔的附魔书，末地虚空死亡后重生，掉出的普通书应保留名称与 lore、无附魔、无附魔闪光。
3. 带 MENDING + 诅咒的附魔书应保持附魔书形态、诅咒保留、名称与 lore 保留。
4. 确认没有发放经验球。

## 测试覆盖矩阵

### 1. 深度边界（最关键，对应历史回归）

四个测试各司其职，职责边界由变异实测确定（见下表「能抓的变异」列）：

| 测试 | 构造 | 断言 | 能抓的变异 |
| --- | --- | --- | --- |
| `deepestReachableLevel_isFullyProcessed` | 动态构造嵌套 MAX−1 层，最内层放黑名单/祛魔对象 | 最内层被完整处理 | 跳过条件**提前**（`>= MAX-1`） |
| `bundleBeyondDepthLimit_isSkippedVerbatim` | 嵌套 MAX 层 | 嵌套 MAX−1 层处理、嵌套 MAX 层原样返回 | 跳过条件**提前或后移**（`>= MAX-1` / `> MAX`） |
| `vanillaMaximumFullChain_sixteenLevels_isFullyProcessed` | **硬编码嵌套 15 层**（16 个收纳袋）+ 最内层 4 个末影水晶 | 水晶被清除 | 常量**改小**（16→15）、跳过条件提前 |
| `emptyChainToMaxLevel_survivesIntact` | 嵌套 MAX−1 层的空链 | 结构完整、不抛异常 | 递归在空内容时崩溃 |

测试代码里的 `level` 是收纳袋个数（背包里的算第 1 个），嵌套层数 = `level − 1` = 代码的 `depth`。

**变异实测矩阵**（改坏生产代码后哪些测试变红）：

| 变异 | 变红的测试 |
| --- | --- |
| `MAX_BUNDLE_DEPTH` 16→15（常量改小） | 仅 `vanillaMaximumFullChain` |
| `MAX_BUNDLE_DEPTH` 16→17（常量改大） | **无（全绿）** |
| 跳过条件 `depth >= MAX` → `depth >= MAX-1`（提前） | `vanillaMaximumFullChain`、`deepestReachableLevel`、`bundleBeyondDepthLimit` |
| 跳过条件 `depth >= MAX` → `depth > MAX`（后移） | 仅 `bundleBeyondDepthLimit` |

**为什么常量改大抓不到、也不需要抓**：动态测试的构造随常量缩放（MAX=17 时构造 17 个袋，最内层 depth=16 仍被处理），所以常量本身改大改小都抓不到；硬编码测试只钉住"不得小于 16"。常量改大（16→17）在语义上无害——`depth >= 16` 的袋必然为空（容量规则），处理与否结果相同，因此没有测试覆盖它是设计上可接受的取舍。常量改小（16→15）会泄漏黑名单物品，由硬编码测试守住。

**`bundleBeyondDepthLimit` 的断言强度分级**（避免未来改动被误认为阻塞）：

| 断言 | 强度 | 说明 |
| --- | --- | --- |
| 第 MAX 个袋的镐被磨 | **硬约束** | 阈值提前会泄漏黑名单物品（服务器实测 bug），永远不得放宽 |
| 第 MAX+1 个袋原样返回 | **characterization（行为记录）** | 原版容量规则下 depth-16 袋必然为空，处理与否在真实游戏中不可观测；钉住当前跳过语义、防止无意改动 |

若未来有意把跳过条件改为 `depth > MAX`（例如为防御 NBT 编辑构造的非原版 depth-16 袋），更新 characterization 断言即为**预期动作**——测试失败是"请做有意识决定"的提示，不是阻塞。改动时需同步更新本表与测试 javadoc。

### 2. 黑名单过滤

- 6 个配置材质（鞘翅、潜影壳、末影箱、黑曜石、末影珍珠、末影水晶）逐一在顶层被移除——防配置项拼写错误静默失效。
- 黑名单物品藏在收纳袋**中间层**时同样被移除——防只清理顶层。

### 3. 潜影盒

- 非空潜影盒：内容逐件净化后进入 pending，盒本身消失。
- 空潜影盒：直接消失，不产生 pending 记录。
- 盒内收纳袋：先按收纳袋规则递归净化，再进 pending。
- 嵌套潜影盒不递归展开：盒中盒按普通物品整体保留进 pending，内层内容不被提取、不被净化（`nestedShulker_isKeptAsPlainItem_notRecursivelyOpened`，与 README 声明一致）。
- 彩色潜影盒：`dyedShulkerBox_extractedToPendingLikePlainOne` 用 `WHITE_SHULKER_BOX` 走一遍同样的路径——生产代码判定是 `type.name().endsWith("SHULKER_BOX")`，改成等值比较后本测试变红（变异实测确认）。

### 4. 砂轮式祛魔

> 本节所有断言覆盖的都是**内置回退路径**。`use-vanilla-grindstone` 为 true 时生产代码优先走原版砂轮反射，而 MockBukkit 无 NMS，该路径在测试里恒不可用（`vanillaGrindstone_unavailableUnderMockBukkit` 钉住这一前提）。原版路径的行为只能在真实服务器验收，见「祛魔引擎」。

- 带 MENDING 的物品：非诅咒附魔全部移除，绑定/消失诅咒保留。`repair_cost` 归零分两条独立代码路径，各自由一条测试钉住：
  - **普通物品路径**（`meta.getEnchants()` 分支，末尾 `item.setData(REPAIR_COST, 0)`）：由 `grindstone_keepsCurses_removesOthers_resetsRepairCost` 钉住（镐上先设 `repair_cost=37`，处理后断言归零）。
  - **附魔书退化路径**（`degradeToPlainBook` 里 `setType(BOOK)` 后 `setData(REPAIR_COST, 0)`）：由 `degradedBook_resetsRepairCost` 钉住。`setType` 会**保留**原物品的 `repair_cost`，所以这句归零不能省。
- 不带 MENDING 的物品：附魔完全不动——防误磨。
- 附魔书祛魔后无附魔：退化为普通书且数量保留。
- **附魔书退化路径的 `repair_cost` 归零**（`degradedBook_resetsRepairCost`）：附魔书先设 `repair_cost=39`，退化后必须归零（`null` 视作通过，默认值即归零）。
  - 变异方式与结果：删掉 `degradeToPlainBook` 里的 `item.setData(DataComponentTypes.REPAIR_COST, 0)`，本测试变红 `expected: <0> but was: <39>`（变异实测确认）。
  - 历史教训：该测试一度**无法证伪**——当时退化分支用 `ItemStack#copyDataFrom`，MockBukkit 未实现该方法，测试被记为 skipped 而非 failed，删掉归零语句仍 `BUILD SUCCESS`。改用 `setType` 后 4 条被跳过的测试恢复执行，这条变异才重新可抓。
  - 后续计划（本次不做）：「保留为附魔书」的不退化路径同样以 `item.setData(REPAIR_COST, 0)` 收尾，目前只有普通物品路径的断言间接覆盖它，可另补一条附魔书不退化时的 `repair_cost` 测试。
- **附魔书退化后保留显示属性**（`enchantedBook_degradeKeepsDisplayAttributes`）：自定义名称、lore、`custom_model_data` 三项在退化为普通书后必须原样保留。原版砂轮祛魔保留这些属性，插件对外声明「砂轮式祛魔」，语义必须一致。实现上不能用 `ItemStack.of(Material.BOOK, amount)` 新建物品（会丢光显示属性），也不能把附魔书的 `EnchantmentStorageMeta` 塞给普通书（`setItemMeta` 因 meta 类型不匹配失败）。当前做法是 `setType(Material.BOOK)` 复用原物品。变异：改回 `ItemStack.of(Material.BOOK, amount)` 后，本测试第一条名称断言即变红，不退化测试仍绿（变异实测确认）。
- **不退化路径同样保留显示属性**（`enchantedBook_curseOnlyKeepsDisplayAttributes`）：附魔书残留诅咒、维持附魔书形态时，名称/lore 保留，MENDING 被移除、诅咒保留。该路径复用原 meta，本测试钉住「未来重构不得破坏这条正确行为」——两条路径的显示属性保留由此成对守住。
- **祛魔引擎开关**：`use-vanilla-grindstone` 由 `reloadSettings` 读取（`useVanillaGrindstoneFlag_readByReloadSettings` 钉住，含默认值必须为 true）。它是 MC 升级打碎反射映射时的热停用手段，读不到就等于没有保险。变异：删掉 `reloadSettings` 里对该键的读取后本测试变红（变异实测确认）。
- `remove-non-curse-enchant: false` 时全部保留——防配置开关失效。附魔书单独有一条（`configDisablesGrinding_enchantedBookKeepsStoredEnchants`）：附魔书走 `EnchantmentStorageMeta` 的 stored enchants 分支，与普通物品的 `getEnchants` 分支是两条独立路径，各自钉住。变异：删掉 `stripNonCurseEnchantments` 开头的配置早退后，两条同时变红（变异实测确认）。

### 5. 持久化与合并

> **写盘时机（决策 A）**：`put` 与 `remove` **都不写盘**，只改内存（内存是主副本）；唯一写盘时机是插件关闭时的 `onDisable → PendingDropStore.save()`。因此断言分两类：**内存视图**（`pluginStore()`，进程内真相）与**磁盘视图**（`diskStore()` / `pendingOf()`，只在显式 `save()` 或 `onDisable` 之后才可信）。断言磁盘前必须确认该路径已经落过盘，否则断言恒真。

- **YAML 往返**（`pendingDropStore_roundTripAndRemoval`）：`put` → `save()`（模拟关服）→ 新实例从磁盘读回物品（含附魔）；`remove` → `save()` → 读回为空。覆盖「正常关闭 → 重启」这条决策 A 唯一保证的路径。
- **空 put 且无记录不写盘**（`pendingDropStore_emptyPutWithoutRecord_doesNotWriteFile`）：文件不得被创建——防死亡时背包为空却反复重写文件。注意：`put` 不写盘后该断言恒真，真正守住的是 `put` 开头的早退分支（空列表且无记录时直接 return），删掉该分支会由场景 3 的空段断言抓到。
- **remove 最后一条记录不留空段**（`pendingDropStore_removeLastRecord_leavesNoEmptyPlayersSection`）：钉住决策 A 的三个方向——(1) `put` 不写盘：put 后解析磁盘的 `players` 段为 `null`；(2) `remove` 不写盘：显式 `save()` 留下磁盘快照后，`remove` 之后磁盘上那条旧记录**必须仍在**（陈旧记录只活到关服，见「已知风险」）；(3) `save` 写的是内存快照：再次 `save()` 后该记录与空 `players` 段一起消失（文件不得残留 `players:` 空段）。变异：把 `save()` 加回 `PendingDropStore.remove()` 末尾，(2) 变红（实测确认）。
- **关服落盘 + 重启读回**（`shutdownHook_persistsPendingRecordToDisk`）：死亡产生内存记录（此时文件不存在）→ 调生产代码 `onDisable()` → 文件必须被创建、重启后读回同一条物品。变异：删掉 `onDisable` 里的 `store.save()` 后本测试变红（实测确认）——这是唯一钉住「关服写盘」的测试。
  - 探针实测：`MockBukkit.unmock()`（`disablePlugins` → `onDisable`）**不会**触发本插件的 `onDisable`（文件始终不被创建），因此该测试显式调用 `plugin.onDisable()`。不要改回 `unmock()`，那会让断言恒真。
- `mergeIfConfigured`：100 个圆石 → 64+36，总量守恒——防合并丢数量。
- **旧记录与本次新物品合并**（`death_mergesStaleRecordWithNewItems`）：上次发放失败留下的 STONE×5 与本次潜影盒里的 EMERALD×3 必须一起写回 pending——这是「失败重试」机制的核心闭环。内存视图断言合并结果（2 条、STONE×5、EMERALD×3）；再显式 `pluginStore().save()` 后用 `pendingOf` 断言落盘形态（旧记录与新物品都在）。变异：`onPlayerDeath` 里 `store.get(playerId)` 改成 `new ArrayList<>()` 后旧记录被丢弃，本测试变红（变异实测确认，`emptyDeath_keepsExistingPendingRecord` 同时变红）。
- **空死亡不误删/不误造记录**：`emptyDeath_keepsExistingPendingRecord`（有旧记录 → 保留）、`emptyDeathWithoutStaleRecord_removesRecord`、`respawnWithNoPending_callsStoreRemoveWithoutDropping`（不误删其他玩家记录）。三条一律走 `pluginStore()` 内存视图：`put` 不写盘时 `diskStore()` 是一份干净内存，用它做「记录还在」的断言会恒假。

### 6. 端到端事件流

- 末地 + VOID 死亡 → 背包入 pending → 重生事件内同步 burst：掉落数量、祛魔结果、拾取延迟、散落范围、记录清理全部断言。
- 四条负路径：非末地死亡、非 VOID 死因、死因为 null（原版 `/kill` 或从未受过伤害）、无死亡直接重生——均不得触发任何处理。死因为 null 时还不得抛 NPE（`deathWithoutDamageCause_notTriggeredAndNoNPE`）。
- `burst.radius` 三档：超限截断（`burstRadius_aboveHalfBlock_isClampedAndWarnsOnceUntilReload`）、`0` 与 `-1` 归零（`burstRadius_zeroOrNegative_landsExactlyOnBlockCenter`，断言落点精确等于重生格中心且不误报 warning）。变异：删掉 `Math.max(0.0, …)` 后归零测试变红（变异实测确认）。

### 7. 不可测项（设计说明，非行为）

- **`stripNonCurseEnchantments` 签名从 `void` 改为返回 `ItemStack`**：这是设计决策说明（仅插件内 2 处调用、无外部 API 影响），不是可观测行为，**无法用测试验证**，不属于测试覆盖目标。若未来需要保留 `void` 签名，可退回 `item.setType(...)`（带 deprecation 警告），行为等价性由上述祛魔测试覆盖。
- **「`processItem` 不就地改写输入物品」无法在 MockBukkit 下验证**：探针实测——对同一个 `ShulkerBox` handle 连续两次 `getInventory().getItem(0)` 返回的是两个不同对象，改动其中一件另一件不受影响，即潜影盒内容每次读取都是全新副本。因此「盒内原物未被祛魔」这类断言恒真：把 `extractShulkerContents` 里的 `content.clone()` 删掉，测试仍全绿（变异实测确认）。该测试已删除，不保留假覆盖。`clone()` 在真实 Paper 上是必需的（真实 `ShulkerBox` 返回同一容器实例），要钉住它得改用真实服务器验证。

### 8. 设计说明归档

生产代码里的设计说明已精简为 1-2 行结论，详细讨论归档到本文档：

| 主题 | 生产代码注释 | 详细讨论 |
| --- | --- | --- |
| 附魔书退化分支已在真实服务器验证通过 | 无 javadoc，逻辑由测试覆盖 | §4 |
| 空记录形态与清理策略 | `onPlayerRespawn` 内 1 行 | §5（两个测试钉住） |
| 方法签名变更（不可测） | `stripNonCurseEnchantments` 内 1 行 | §7 |
| burst 中途失败整单保留（可能重发已落地物品） | `burst` 内 2 行 | 已知风险 |

**原则**：生产代码注释只保留"为什么这样写"的结论 + 指向本文档；推导过程、探针数据、变异矩阵等细节留在本文档，避免生产代码被讨论性注释淹没。

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

测试 `burstFailure_unloadedWorld_keepsPendingRecord` 手动卸载世界后触发重生，钉住的不是这个守卫本身可达性，而是「burst 抛异常 → 调用方 catch → 记录保留」这条异常路径：把 `throw IllegalStateException` 改成 warning 后 return，记录会被 `store.remove` 清掉，该测试变红（变异实测确认）。

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

1. **前置存在性断言**：任何"处理后被移除"的断言之前，必须先断言"处理前该物品确实存在且数量正确"。没有前置断言的移除断言可能是空洞通过。
2. **绝对值 + 相对值双钉**：边界测试不能只动态读取常量（构造随常量缩放，常量改错时测试会跟着一起绿），必须同时存在硬编码绝对层数的用例（当前为嵌套 15 层）。动态用例钉住跳过条件相对常量的位置（提前/后移），硬编码用例钉住常量不得改小；常量改大无害且无测试覆盖，属可接受取舍（见上文变异实测矩阵）。
3. **变异验证**：新增或修改边界测试后，把生产常量改坏（如 16→15），测试**必须变红**；恢复后必须全绿。未通过变异验证的边界测试视为无效。
4. **不为测试改生产逻辑**：仅允许可见性调整（如反射调用私有方法）；生产行为问题应修生产代码，而不是迁就测试。
5. **持久化断言先确认写盘时机**：`put` / `remove` 都不写盘，`diskStore()` / `pendingOf()` 只在显式 `save()` 或 `onDisable()` 之后才有内容可读。写磁盘断言前先问「这一刻谁写过盘」，否则断言会恒真（曾出现：`put` 不写盘后，`diskStore().get(...)` 恒为空，多条断言在失败与通过之间失去区分度）。
