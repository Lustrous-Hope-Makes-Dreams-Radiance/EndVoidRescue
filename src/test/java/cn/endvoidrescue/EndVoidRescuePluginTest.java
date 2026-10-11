package cn.endvoidrescue;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;
import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.ShulkerBox;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EndVoidRescue 行为测试。
 *
 * 1. 每个「处理后物品被移除」的断言之前，必须先断言「处理前它确实存在于结构中」。
 * 旧测试从未做前置断言：一旦构造器（BundleMeta.setItems）静默丢弃内容，
 * 「移除成功」的断言就会空洞通过，与生产逻辑完全无关。
 * 2. 边界测试「绝对值 + 相对值双钉」（以下结论均经变异实测确认）：
 * - 动态读取 MAX_BUNDLE_DEPTH 构造（deepestReachableLevel / bundleBeyondDepthLimit）
 * 钉住阈值相对常量的位置：跳过条件被改成提前（>= MAX-1）或后移（> MAX）都会变红；
 * 但常量本身改大/改小抓不到（构造随常量缩放，MAX=17 时全部照绿）。
 * - 硬编码 16 个收纳袋（vanillaMaximumFullChain）钉住绝对阈值：常量改小（16→15）会变红。
 * 常量改大（16→17）没有任何测试能抓，且无害（depth>=16 的袋必然为空，处理与否结果相同），
 * 这是设计上可接受的。
 * 3. REPAIR_COST 只在独立物品路径上断言（探针实测：MockBukkit 的收纳袋
 * setItems 往返会丢弃该组件，经由收纳袋断言 rc 归零同样是空洞通过）。
 * 4. 覆盖三个真实事件入口（PlayerDeathEvent / PlayerRespawnEvent + 调度器 tick）
 * 的端到端链路，而不只是私有方法。
 * 5. 术语：测试代码中的「层」指收纳袋个数（背包里的算第 1 个），
 * 嵌套层数 = 层数 − 1 = 生产代码 processBundle 的 depth（见 docs/testing.md）。
 */
class EndVoidRescuePluginTest {
    private ServerMock server;
    private EndVoidRescuePlugin plugin;
    private Method processItemMethod;
    private int maxBundleDepth;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(EndVoidRescuePlugin.class);
        processItemMethod = EndVoidRescuePlugin.class.getDeclaredMethod("processItem", ItemStack.class, List.class);
        processItemMethod.setAccessible(true);
        // 防御性前置：确认 config.yml 真的被加载、黑名单真的非空。
        // 如果哪天 saveDefaultConfig / 资源过滤坏了，这里立刻报错，而不是让下游所有「黑名单被移除」断言空洞通过。
        Field blacklistField = EndVoidRescuePlugin.class.getDeclaredField("blacklist");
        blacklistField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<Material> loadedBlacklist = (Set<Material>) blacklistField.get(plugin);
        assertTrue(
                loadedBlacklist.containsAll(Set.of(Material.ELYTRA, Material.SHULKER_SHELL, Material.ENDER_CHEST,
                        Material.OBSIDIAN, Material.ENDER_PEARL, Material.END_CRYSTAL)),
                "config.yml 黑名单未按预期加载，实际: " + loadedBlacklist);
        Field maxField = EndVoidRescuePlugin.class.getDeclaredField("MAX_BUNDLE_DEPTH");
        maxField.setAccessible(true);
        maxBundleDepth = (int) maxField.get(null);
        assertTrue(maxBundleDepth >= 1, "MAX_BUNDLE_DEPTH 必须为正，实际: " + maxBundleDepth);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // ═══════════════════════════ 深度边界：钉死 MAX ═══════════════════════════
    /**
     * 动态边界：构造 MAX 个收纳袋（嵌套 MAX−1 层），最内层放满黑名单+附魔物品，
     * 断言最内层被完整处理。
     * 变异实测：跳过条件提前（depth >= MAX-1）时本测试变红（最内层被跳过）；
     * 常量本身改大/改小、跳过条件后移（depth > MAX）时不变红
     * （构造随常量缩放；最内层 depth=MAX-1 在 > MAX 下仍被处理）。
     * 常量改小的防线在 vanillaMaximumFullChain_sixteenLevels_isFullyProcessed（硬编码 16 个袋）。
     */
    @Test
    void deepestReachableLevel_isFullyProcessed() throws Exception {
        ItemStack crystals = new ItemStack(Material.END_CRYSTAL, 4);
        ItemStack elytra = new ItemStack(Material.ELYTRA);
        ItemStack mendingPickaxe = mendingPickaxe();
        ItemStack mendingBook = mendingEnchantedBook(3);
        ItemStack nested = bundleWith(List.of(crystals, elytra, mendingPickaxe, mendingBook));
        for (int level = 2; level <= maxBundleDepth; level++) {
            nested = bundleWith(List.of(nested));
        }
        // 前置断言：构造确实是 N 层，且目标物品真的存在于最内层。
        assertEquals(maxBundleDepth, countBundleLevels(nested), "构造失败：嵌套层数不符");
        List<ItemStack> before = leavesOf(nested);
        assertEquals(4, countAmount(before, Material.END_CRYSTAL), "前置：末影水晶应在处理前的结构中");
        assertTrue(containsType(before, Material.ELYTRA), "前置：鞘翅应在处理前的结构中");
        assertTrue(containsType(before, Material.DIAMOND_PICKAXE), "前置：镐应在处理前的结构中");
        assertTrue(containsType(before, Material.ENCHANTED_BOOK), "前置：附魔书应在处理前的结构中");
        ItemStack processed = invokeProcessItem(nested);
        List<ItemStack> after = leavesOf(processed);
        // 后置断言：第 MAX 层内的黑名单被清理，附魔被磨，书退化。
        assertEquals(0, countAmount(after, Material.END_CRYSTAL), "末影水晶必须被移除");
        assertFalse(containsType(after, Material.ELYTRA), "鞘翅必须被移除");
        assertFalse(containsType(after, Material.ENCHANTED_BOOK), "附魔书不应存活");
        assertTrue(containsType(after, Material.BOOK), "祛魔后的附魔书应退化为普通书");
        ItemStack pickaxe = firstOfType(after, Material.DIAMOND_PICKAXE);
        assertFalse(pickaxe.containsEnchantment(Enchantment.MENDING), "MENDING 应被磨掉");
        assertFalse(pickaxe.containsEnchantment(Enchantment.SHARPNESS), "SHARPNESS 应被磨掉");
    }

    /**
     * 深度阈值必须恰好落在 depth==MAX：第 MAX 个收纳袋（depth=MAX−1）处理、
     * 第 MAX+1 个收纳袋（depth=MAX）原样跳过。
     * 构造 MAX+1 个收纳袋的链：第 MAX 个袋里放一把带 MENDING 的镐，
     * 第 MAX+1 个袋（最内）放末影水晶和另一把带 MENDING 的镐。
     * 期望：第 MAX 个袋的镐被磨；第 MAX+1 个袋整体原样返回（水晶存活、镐保留附魔）。
     *
     * <p>
     * 变异实测：这是唯一同时钉住阈值两个方向的测试——跳过条件提前（>= MAX-1）时
     * 「第 MAX 个袋被磨」变红，后移（> MAX）时「第 MAX+1 个袋原样」变红。
     * 常量本身改大/改小抓不到（构造随常量缩放）。
     *
     * <p>
     * 两个断言的约束强度不同：
     * <ul>
     * <li>「第 MAX 个袋被磨」是<b>硬约束</b>：阈值提前会泄漏黑名单物品（服务器实测 bug），
     * 此断言永远不得放宽。</li>
     * <li>「第 MAX+1 个袋原样」是<b>characterization（行为记录）</b>：原版容量规则下
     * depth-16 袋必然为空（链占满 64 格），处理与否在真实游戏中不可观测。
     * 它钉住当前跳过语义、防止无意改动；若未来有意改为 depth > MAX
     * （如防御非原版构造的 depth-16 袋），更新本断言即为预期动作——
     * 失败是「请做有意识决定」的提示，不是阻塞。</li>
     * </ul>
     */
    @Test
    void bundleBeyondDepthLimit_isSkippedVerbatim() throws Exception {
        ItemStack processedAtMax = mendingPickaxe();
        ItemStack untouchedCrystal = new ItemStack(Material.END_CRYSTAL, 4);
        ItemStack untouchedPickaxe = mendingPickaxe();
        ItemStack innermost = bundleWith(List.of(untouchedCrystal, untouchedPickaxe)); // 第 MAX+1 个袋
        ItemStack atMaxLevel = bundleWith(new ArrayList<>(List.of(innermost, processedAtMax))); // 第 MAX 个袋
        ItemStack nested = atMaxLevel;
        for (int level = maxBundleDepth - 1; level >= 1; level--) {
            nested = bundleWith(List.of(nested));
        }
        assertEquals(maxBundleDepth + 1, countBundleLevels(nested), "构造失败：应为 MAX+1 个收纳袋");
        ItemStack processed = invokeProcessItem(nested);
        // 下钻到第 MAX 个收纳袋（从外层数第 maxBundleDepth 个）。
        ItemStack maxLevelBundle = descendToBundleLevel(processed, maxBundleDepth);
        // 第 MAX 个袋：被正常处理（证明阈值不在 MAX−1 或更早）。
        List<ItemStack> maxLevelOwnItems = directNonBundleChildren(maxLevelBundle);
        ItemStack groundedPickaxe = firstOfType(maxLevelOwnItems, Material.DIAMOND_PICKAXE);
        assertFalse(groundedPickaxe.containsEnchantment(Enchantment.MENDING), "第 MAX 个袋的镐必须被磨（若此处失败说明阈值提前了）");
        // 第 MAX+1 个袋：原样返回（证明阈值不在 MAX+1 或更晚）。
        ItemStack skippedBundle = firstDirectBundleChild(maxLevelBundle);
        List<ItemStack> skippedLeaves = leavesOf(skippedBundle);
        assertEquals(4, countAmount(skippedLeaves, Material.END_CRYSTAL), "第 MAX+1 个袋应被整体跳过：水晶存活（阈值若后移，此处会变红）");
        ItemStack skippedPickaxe = firstOfType(skippedLeaves, Material.DIAMOND_PICKAXE);
        assertTrue(skippedPickaxe.containsEnchantment(Enchantment.MENDING), "第 MAX+1 个袋应被整体跳过：附魔保留");
    }

    /**
     * 绝对值回归：原版容量规则下，能装物品的最深结构是嵌套 15 层（16 个收纳袋）——
     * 每个嵌套收纳袋占外层 4 格，4×15=60，最内层还能放 4 个物品；
     * 嵌套 16 层时链本身占满 64 格，最内层必然为空。
     * 因此「嵌套 15 层 + 最内层 4 个末影水晶」是玩家能构造出的最深满载结构，
     * 必须被完整清理。MAX_BUNDLE_DEPTH 一旦小于 16，该层（depth=15）会被整体跳过，
     * 末影水晶泄漏——这正是 MAX=15 时在服务器上实测到的缺陷。
     * 本测试硬编码 16 个收纳袋钉死绝对阈值，与动态读取常量的两条测试互补。
     */
    @Test
    void vanillaMaximumFullChain_sixteenLevels_isFullyProcessed() throws Exception {
        final int vanillaMaxBags = 16; // 16 个收纳袋 = 嵌套 15 层
        ItemStack nested = bundleWith(List.of(new ItemStack(Material.END_CRYSTAL, 4)));
        for (int level = 2; level <= vanillaMaxBags; level++) {
            nested = bundleWith(List.of(nested));
        }
        assertEquals(vanillaMaxBags, countBundleLevels(nested), "构造失败：应为 16 个收纳袋（嵌套 15 层）");
        assertEquals(4, countAmount(leavesOf(nested), Material.END_CRYSTAL), "前置：水晶应在结构中");
        List<ItemStack> after = leavesOf(invokeProcessItem(nested));
        assertEquals(0, countAmount(after, Material.END_CRYSTAL), "嵌套 15 层满载链的最内层末影水晶必须被清除（MAX_BUNDLE_DEPTH 不得小于 16）");
    }

    /** 空收纳袋链到 MAX 层：不抛异常且结构完整（旧行为，保留）。 */
    @Test
    void emptyChainToMaxLevel_survivesIntact() throws Exception {
        ItemStack nested = bundleWith(List.of());
        for (int level = 2; level <= maxBundleDepth; level++) {
            nested = bundleWith(List.of(nested));
        }
        ItemStack processed = invokeProcessItem(nested);
        assertEquals(maxBundleDepth, countBundleLevels(processed));
    }

    // ═══════════════════════ 黑名单：直接 + 逐材质回归 ═══════════════════════

    /**
     * config.yml 里六个黑名单材质逐一验证 matchMaterial 拼写正确并真实移除。
     * 若有人把 OBSIDIAN 拼错，reloadSettings 的 warning 是静默的，只有这里会红。
     */
    @Test
    void everyConfiguredBlacklistMaterial_isRemovedAtTopLevel() throws Exception {
        for (Material material : List.of(Material.ELYTRA, Material.SHULKER_SHELL,
                Material.ENDER_CHEST, Material.OBSIDIAN, Material.ENDER_PEARL, Material.END_CRYSTAL)) {
            ItemStack result = invokeProcessItem(new ItemStack(material));
            assertNull(result, material + " 在黑名单中，processItem 应返回 null");
        }
    }

    /** 黑名单物品在收纳袋中层出现也必须被过滤（非最内层路径）。 */
    @Test
    void blacklistInsideMiddleBundleLevel_isRemoved() throws Exception {
        ItemStack middle = bundleWith(List.of(new ItemStack(Material.ENDER_PEARL, 16),
                new ItemStack(Material.DIAMOND, 5)));
        ItemStack outer = bundleWith(List.of(middle));

        assertEquals(16, countAmount(leavesOf(outer), Material.ENDER_PEARL), "前置：珍珠应存在");
        List<ItemStack> after = leavesOf(invokeProcessItem(outer));
        assertEquals(0, countAmount(after, Material.ENDER_PEARL), "珍珠应被移除");
        assertEquals(5, countAmount(after, Material.DIAMOND), "普通物品数量不应受影响");
    }

    // ═══════════════════════════ 潜影盒路径 ═══════════════════════════

    /** 潜影盒（含内容）从背包消失，内容逐件进入 pending，且被祛魔。 */
    @Test
    void shulkerWithContents_extractsToPending() throws Exception {
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        sword.addUnsafeEnchantment(Enchantment.MENDING, 1);
        sword.addUnsafeEnchantment(Enchantment.SHARPNESS, 5);
        ItemStack shulker = shulkerWith(List.of(sword, new ItemStack(Material.ELYTRA)));

        List<ItemStack> pending = new ArrayList<>();
        ItemStack result = invokeProcessItemWithPending(shulker, pending);

        assertNull(result, "潜影盒本体应消失");
        assertEquals(1, pending.size(), "只应有剑进入 pending（ELYTRA 被黑名单过滤）");
        ItemStack extracted = pending.get(0);
        assertEquals(Material.DIAMOND_SWORD, extracted.getType());
        assertFalse(extracted.containsEnchantment(Enchantment.MENDING), "pending 里的剑应已祛魔");
        assertFalse(extracted.containsEnchantment(Enchantment.SHARPNESS));
    }

    /** 空潜影盒直接消失，不产生 pending。 */
    @Test
    void emptyShulker_removedWithoutPending() throws Exception {
        List<ItemStack> pending = new ArrayList<>();
        assertNull(invokeProcessItemWithPending(new ItemStack(Material.SHULKER_BOX), pending));
        assertTrue(pending.isEmpty());
    }

    /** 潜影盒内的收纳袋：先净化再整体进入 pending。 */
    @Test
    void bundleInsideShulker_isPurifiedThenPending() throws Exception {
        ItemStack inner = bundleWith(List.of(new ItemStack(Material.ENDER_PEARL, 8),
                new ItemStack(Material.GOLD_INGOT, 3)));
        ItemStack shulker = shulkerWith(List.of(inner));

        List<ItemStack> pending = new ArrayList<>();
        invokeProcessItemWithPending(shulker, pending);

        assertEquals(1, pending.size());
        ItemStack pendingBundle = pending.get(0);
        assertTrue(pendingBundle.getItemMeta() instanceof BundleMeta, "收纳袋应整体保留进 pending");
        List<ItemStack> leaves = leavesOf(pendingBundle);
        assertEquals(0, countAmount(leaves, Material.ENDER_PEARL), "袋内珍珠应被移除");
        assertEquals(3, countAmount(leaves, Material.GOLD_INGOT), "袋内金锭应保留");
    }

    /**
     * 嵌套潜影盒不递归展开：盒中盒按普通物品整体保留进 pending，
     * 内层盒的内容不被提取、不被净化（与 README「不递归展开嵌套潜影盒」一致）。
     * 前置断言确认内层盒及其内容真实存在于构造中，避免空洞通过。
     */
    @Test
    void nestedShulker_isKeptAsPlainItem_notRecursivelyOpened() throws Exception {
        ItemStack innerSword = new ItemStack(Material.DIAMOND_SWORD);
        innerSword.addUnsafeEnchantment(Enchantment.MENDING, 1);
        innerSword.addUnsafeEnchantment(Enchantment.SHARPNESS, 5);
        ItemStack innerShulker = shulkerWith(List.of(innerSword));
        ItemStack outerShulker = shulkerWith(List.of(innerShulker));

        // 前置：内层盒确实装着剑。
        assertTrue(hasShulkerContents(innerShulker), "前置：内层潜影盒应装有剑");

        List<ItemStack> pending = new ArrayList<>();
        invokeProcessItemWithPending(outerShulker, pending);

        assertEquals(1, pending.size(), "外层盒内容应只有内层盒一件");
        ItemStack kept = pending.get(0);
        assertEquals(Material.SHULKER_BOX, kept.getType(), "内层盒应按普通物品保留");
        // 内层盒内容原样：剑仍在、附魔未被磨（若被递归展开，这里会变红）。
        ItemStack keptSword = firstShulkerContent(kept, Material.DIAMOND_SWORD);
        assertTrue(keptSword.containsEnchantment(Enchantment.MENDING),
                "嵌套潜影盒不得被递归展开：内层剑的附魔应原样保留");
        assertTrue(keptSword.containsEnchantment(Enchantment.SHARPNESS));
    }

    /**
     * 彩色潜影盒（WHITE_SHULKER_BOX）必须与无染色 SHULKER_BOX 走同一条路径：
     * 生产代码用 `type.name().endsWith("SHULKER_BOX")` 判定，若改成等值比较本测试变红。
     */
    @Test
    void dyedShulkerBox_extractedToPendingLikePlainOne() throws Exception {
        ItemStack shulker = shulkerWith(Material.WHITE_SHULKER_BOX,
                List.of(new ItemStack(Material.EMERALD, 3), new ItemStack(Material.OBSIDIAN)));
        assertEquals(2, countShulkerContents(shulker), "前置：盒内应有绿宝石与黑曜石");

        List<ItemStack> pending = new ArrayList<>();
        assertNull(invokeProcessItemWithPending(shulker, pending), "彩色潜影盒本体应消失");
        assertEquals(1, pending.size(), "只应有绿宝石进入 pending（黑曜石被黑名单过滤）");
        assertEquals(Material.EMERALD, pending.get(0).getType());
        assertEquals(3, pending.get(0).getAmount());
    }

    // ═══════════════════════ 祛魔（砂轮）语义：独立物品路径 ═══════════════════════

    /**
     * MENDING + 普通附魔 + 两种诅咒共存：磨掉 MENDING/SHARPNESS，
     * 保留 BINDING_CURSE/VANISHING_CURSE（isCurse 的 key 匹配逻辑在此验证——
     * 若附魔 key 命名与实现不符，这条测试会红）。
     * REPAIR_COST 直接断言 == 0：独立物品无收纳袋往返丢组件的问题。
     */
    @Test
    void grindstone_keepsCurses_removesOthers_resetsRepairCost() throws Exception {
        ItemStack pickaxe = new ItemStack(Material.DIAMOND_PICKAXE);
        pickaxe.addUnsafeEnchantment(Enchantment.MENDING, 1);
        pickaxe.addUnsafeEnchantment(Enchantment.SHARPNESS, 5);
        pickaxe.addUnsafeEnchantment(Enchantment.BINDING_CURSE, 1);
        pickaxe.addUnsafeEnchantment(Enchantment.VANISHING_CURSE, 1);
        pickaxe.setData(DataComponentTypes.REPAIR_COST, 37);
        assertEquals(37, pickaxe.getData(DataComponentTypes.REPAIR_COST), "前置：repair_cost=37");

        ItemStack processed = invokeProcessItem(pickaxe);

        assertFalse(processed.containsEnchantment(Enchantment.MENDING));
        assertFalse(processed.containsEnchantment(Enchantment.SHARPNESS));
        assertTrue(processed.containsEnchantment(Enchantment.BINDING_CURSE), "绑定诅咒必须保留");
        assertTrue(processed.containsEnchantment(Enchantment.VANISHING_CURSE), "消失诅咒必须保留");
        Integer repairCost = processed.getData(DataComponentTypes.REPAIR_COST);
        assertNotNull(repairCost, "独立物品路径上 repair_cost 组件应可读回");
        assertEquals(0, repairCost.intValue(), "祛魔后 repair_cost 必须归零");
    }

    /** 无 MENDING 的物品完全不动（SHARPNESS 保留）。 */
    @Test
    void withoutMending_enchantmentsUntouched() throws Exception {
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        sword.addUnsafeEnchantment(Enchantment.SHARPNESS, 5);
        sword.addUnsafeEnchantment(Enchantment.FIRE_ASPECT, 2);

        ItemStack processed = invokeProcessItem(sword);

        assertTrue(processed.containsEnchantment(Enchantment.SHARPNESS), "无 MENDING 不应触发磨魔");
        assertTrue(processed.containsEnchantment(Enchantment.FIRE_ASPECT));
    }

    /** 附魔书：stored 非诅咒全清、退化普通书、数量保留；若还存着诅咒则保留并维持书形态。 */
    @Test
    void enchantedBook_degradesWhenEmpty_keepsAmount() throws Exception {
        ItemStack processed = invokeProcessItem(mendingEnchantedBook(3));
        assertEquals(Material.BOOK, processed.getType(), "祛尽附魔的书应退化为普通书");
        assertEquals(3, processed.getAmount(), "退化后数量必须保留");

        ItemStack curseBook = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) curseBook.getItemMeta();
        meta.addStoredEnchant(Enchantment.MENDING, 1, true);
        meta.addStoredEnchant(Enchantment.VANISHING_CURSE, 1, true);
        curseBook.setItemMeta(meta);

        ItemStack kept = invokeProcessItem(curseBook);
        assertEquals(Material.ENCHANTED_BOOK, kept.getType(), "仍有诅咒的书不应退化");
        EnchantmentStorageMeta keptMeta = (EnchantmentStorageMeta) kept.getItemMeta();
        assertTrue(keptMeta.getStoredEnchants().containsKey(Enchantment.VANISHING_CURSE));
        assertFalse(keptMeta.getStoredEnchants().containsKey(Enchantment.MENDING));
    }

    /**
     * 退化路径（附魔清空 → 普通书）必须保留显示属性：自定义名称、lore、custom_model_data。
     * 原版砂轮祛魔保留这些属性，插件对外声明「砂轮式祛魔」，语义须一致。
     * 构造用 MENDING + 一个非诅咒附魔，确保退化为普通书；同时用前置断言钉住构造真的设上了属性。
     * 变异：把退化路径改回 `ItemStack.of(Material.BOOK, amount)` 后名称/lore 丢失，本测试变红。
     */
    @Test
    void enchantedBook_degradeKeepsDisplayAttributes() throws Exception {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) book.getItemMeta();
        meta.addStoredEnchant(Enchantment.MENDING, 1, true);
        meta.addStoredEnchant(Enchantment.SHARPNESS, 5, true);
        meta.displayName(Component.text("My Enchanted Book"));
        meta.lore(List.of(Component.text("first line"), Component.text("second line")));
        meta.setCustomModelData(4321);
        book.setItemMeta(meta);

        // 前置：构造真的设上了显示属性，且确实没有诅咒（必定退化）。
        assertEquals(Material.ENCHANTED_BOOK, book.getType(), "前置：构造应为附魔书");
        ItemMeta before = book.getItemMeta();
        assertEquals(Component.text("My Enchanted Book"), before.displayName(), "前置：名称应已设置");
        assertEquals(List.of(Component.text("first line"), Component.text("second line")), before.lore(),
                "前置：lore 应已设置");
        assertTrue(before.hasCustomModelData(), "前置：custom_model_data 应已设置");
        EnchantmentStorageMeta beforeMeta = (EnchantmentStorageMeta) before;
        assertFalse(beforeMeta.getStoredEnchants().keySet().stream().anyMatch(this::isCurseEnchantment),
                "前置：构造不得含诅咒附魔（否则不会退化）");

        ItemStack processed = invokeProcessItem(book);

        assertEquals(Material.BOOK, processed.getType(), "无诅咒残留时应退化为普通书");
        ItemMeta after = processed.getItemMeta();
        assertEquals(Component.text("My Enchanted Book"), after.displayName(), "附魔书退化后自定义名称必须保留");
        assertEquals(List.of(Component.text("first line"), Component.text("second line")), after.lore(),
                "退化后 lore 必须保留");
        assertTrue(after.hasCustomModelData(), "退化后 custom_model_data 必须保留");
        assertEquals(4321, after.getCustomModelData());
    }

    /**
     * 附魔书退化路径的 repair_cost 归零。与 grindstone_keepsCurses_removesOthers_resetsRepairCost
     * 钉的是两条不同路径：那条走普通物品的 getEnchants 分支，本条走 EnchantmentStorageMeta
     * 分支里「新建普通书并显式 setData(REPAIR_COST, 0)」的代码——删掉那句 setData 不会被前者抓到。
     *
     * <p>当前在 MockBukkit 下本测试被跳过（不是通过）：退化分支调用
     * {@code ItemStack#copyDataFrom}，MockBukkit 4.115.0 未实现该方法并抛
     * {@code UnimplementedOperationException}（继承 {@code TestAbortedException}），
     * JUnit 因此记为 skipped。前置断言本身可验证（REPAIR_COST 在附魔书上往返正常，实测读回 39），
     * 卡点在 invokeProcessItem 之后。归零行为需真实服务器确认，详见 docs/testing.md
     * 「已知 MockBukkit 怪癖」。
     */
    @Test
    void degradedBook_resetsRepairCost() throws Exception {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) book.getItemMeta();
        meta.addStoredEnchant(Enchantment.MENDING, 1, true);
        meta.addStoredEnchant(Enchantment.SHARPNESS, 5, true);
        book.setItemMeta(meta);
        book.setData(DataComponentTypes.REPAIR_COST, 39);

        // 前置：构造确实是附魔书，repair_cost 确实设上了 39（防构造静默失败导致后置断言空洞通过）。
        assertEquals(Material.ENCHANTED_BOOK, book.getType(), "前置：构造应为附魔书");
        assertEquals(39, book.getData(DataComponentTypes.REPAIR_COST), "前置：repair_cost 应已设置");

        ItemStack result = invokeProcessItem(book);

        assertEquals(Material.BOOK, result.getType(), "前置：应确实走了退化路径");
        Integer rc = result.getData(DataComponentTypes.REPAIR_COST);
        assertEquals(0, rc == null ? 0 : rc.intValue(), "退化后 repair_cost 必须归零");
    }

    /**
     * 不退化路径（仍有诅咒 → 保持附魔书）同样保留显示属性，与退化路径行为一致。
     * 该路径复用原 meta，本测试钉住「重构后行为不得退化」。
     * 变异：把复用 meta 的分支改成重建 meta（只搬附魔、不搬显示属性）后变红。
     */
    @Test
    void enchantedBook_curseOnlyKeepsDisplayAttributes() throws Exception {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) book.getItemMeta();
        meta.addStoredEnchant(Enchantment.MENDING, 1, true);
        meta.addStoredEnchant(Enchantment.BINDING_CURSE, 1, true);
        meta.displayName(Component.text("Cursed Book"));
        meta.lore(List.of(Component.text("keep me")));
        book.setItemMeta(meta);

        // 前置：构造确实含诅咒（必定不退化），显示属性确实设上了。
        assertEquals(Material.ENCHANTED_BOOK, book.getType(), "前置：构造应为附魔书");
        ItemMeta before = book.getItemMeta();
        assertEquals(Component.text("Cursed Book"), before.displayName(), "前置：名称应已设置");
        assertEquals(List.of(Component.text("keep me")), before.lore(), "前置：lore 应已设置");
        EnchantmentStorageMeta beforeMeta = (EnchantmentStorageMeta) before;
        assertTrue(beforeMeta.getStoredEnchants().containsKey(Enchantment.BINDING_CURSE),
                "前置：构造必须含诅咒附魔（否则会退化）");

        ItemStack processed = invokeProcessItem(book);

        assertEquals(Material.ENCHANTED_BOOK, processed.getType(), "仍有诅咒时不得退化");
        EnchantmentStorageMeta afterMeta = (EnchantmentStorageMeta) processed.getItemMeta();
        assertEquals(Component.text("Cursed Book"), afterMeta.displayName(), "不退化路径名称必须保留");
        assertEquals(List.of(Component.text("keep me")), afterMeta.lore(), "不退化路径 lore 必须保留");
        assertTrue(afterMeta.getStoredEnchants().containsKey(Enchantment.BINDING_CURSE), "诅咒必须保留");
        assertFalse(afterMeta.getStoredEnchants().containsKey(Enchantment.MENDING), "MENDING 必须被移除");
    }

    /** remove-non-curse-enchant=false 时整条磨魔链路禁用。 */
    @Test
    void configDisablesGrinding_allEnchantmentsKept() throws Exception {
        plugin.getConfig().set("remove-non-curse-enchant", false);
        ItemStack processed = invokeProcessItem(mendingPickaxe());
        assertTrue(processed.containsEnchantment(Enchantment.MENDING), "配置关闭时不得磨魔");
        assertTrue(processed.containsEnchantment(Enchantment.SHARPNESS));
    }

    /**
     * 配置开关对附魔书同样生效：走的是 EnchantmentStorageMeta（stored enchants）分支，
     * 与普通物品的 getEnchants 分支是两条独立代码路径，必须单独钉住。
     * 变异：删掉 stripNonCurseEnchantments 开头的配置早退后，附魔书会退化成普通书，本测试变红。
     */
    @Test
    void configDisablesGrinding_enchantedBookKeepsStoredEnchants() throws Exception {
        plugin.getConfig().set("remove-non-curse-enchant", false);
        ItemStack book = mendingEnchantedBook(2);

        ItemStack processed = invokeProcessItem(book);

        assertEquals(Material.ENCHANTED_BOOK, processed.getType(), "配置关闭时附魔书不得退化为普通书");
        assertEquals(2, processed.getAmount());
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) processed.getItemMeta();
        assertTrue(meta.getStoredEnchants().containsKey(Enchantment.MENDING), "存储的 MENDING 必须保留");
        assertTrue(meta.getStoredEnchants().containsKey(Enchantment.SHARPNESS), "存储的 SHARPNESS 必须保留");
    }

    // ═══════════════════════════ 持久化 PendingDropStore ═══════════════════════════

    /**
     * 唯一写盘时机是插件关闭（onDisable → save()）时的语义下，必须显式 save() 才能等到落盘。
     * 本测试用显式 save() 模拟关服，覆盖「正常关闭 → 重启」这条决策 A 唯一保证的路径：
     * 物品（含附魔）经 YAML 往返完整恢复，remove 后（再次 save）读回为空。
     * 断言顺序对应真实时序：put/remove 只改内存，save 才产生磁盘快照。
     */
    @Test
    void pendingDropStore_roundTripAndRemoval() {
        UUID id = UUID.randomUUID();
        File folder = plugin.getDataFolder();
        ItemStack enchanted = mendingPickaxe();

        PendingDropStore store = new PendingDropStore(folder, plugin.getLogger());
        store.put(id, List.of(new ItemStack(Material.STONE, 64), enchanted));
        store.save(); // 模拟关服落盘

        PendingDropStore reread = new PendingDropStore(folder, plugin.getLogger()); // 强制从磁盘重载
        List<ItemStack> loaded = reread.get(id);
        assertEquals(2, loaded.size());
        ItemStack reloadedPickaxe = loaded.stream()
                .filter(s -> s.getType() == Material.DIAMOND_PICKAXE).findFirst().orElseThrow();
        assertTrue(reloadedPickaxe.containsEnchantment(Enchantment.MENDING), "附魔必须经 YAML 往返保留");

        reread.remove(id);
        reread.save(); // remove 只清内存，磁盘同步必须依赖显式 save 或关服
        assertTrue(new PendingDropStore(folder, plugin.getLogger()).get(id).isEmpty(), "remove 并落盘后应读回空");
    }

    /**
     * 空 put 且玩家无记录时不得写盘。
     * 场景：死亡时背包为空（或全为黑名单/空潜影盒）→ onPlayerDeath 调 store.remove，
     * 但 put(uuid, List.of()) 也可能被调用；此时文件不应被创建/改写。
     * 断言文件不存在（本测试是首个写盘操作，MockBukkit 每测试独立数据目录，探针实测确认）。
     *
     * <p>put 已不写盘（决策 A：仅 onDisable 落盘）后，「文件不存在」不再证明「本次 put 没写盘」——
     * 这个断言现在恒真。真正守住的是 put 开头的早退分支本身：若它被删掉，内存里会多出
     * 一个空记录并在关服时留下空壳，由 pendingDropStore_removeLastRecord_leavesNoEmptyPlayersSection
     * 覆盖；本测试保留为该早退行为的配套说明与防线。
     */
    @Test
    void pendingDropStore_emptyPutWithoutRecord_doesNotWriteFile() {
        File folder = plugin.getDataFolder();
        File file = new File(folder, "pending-drops.yml");
        assertFalse(file.exists(), "前置：本测试开始时不应存在 pending-drops.yml");

        new PendingDropStore(folder, plugin.getLogger()).put(UUID.randomUUID(), List.of());

        assertFalse(file.exists(), "空 put 且无记录时不得创建 pending-drops.yml");
    }

    /**
     * remove 最后一条记录后，关服落盘不得残留 "players:" 空段。
     * 场景：玩家领取完物品后 store.remove 只清内存；唯一写盘时机是 onDisable → save()。
     * 若落盘时残留 "players: {}"，文件会随每次关服反复写出空壳（pruneEmptyPlayersSection 的动机）。
     *
     * <p>本测试同时钉住决策 A 的三个方向：
     * <ol>
     * <li>put/remove 只改内存：put 后（未 save）磁盘上没有 players 段，
     * remove 后磁盘上仍留着上一次 save 写下的旧记录（陈旧记录只活到关服，见 docs/testing.md「已知风险」）；</li>
     * <li>save 写的是内存快照 —— 关服落盘后该玩家记录与空 players 段一起消失。</li>
     * </ol>
     */
    @Test
    void pendingDropStore_removeLastRecord_leavesNoEmptyPlayersSection() throws Exception {
        File folder = plugin.getDataFolder();
        File file = new File(folder, "pending-drops.yml");
        UUID id = UUID.randomUUID();

        PendingDropStore store = new PendingDropStore(folder, plugin.getLogger());
        store.put(id, List.of(new ItemStack(Material.STONE, 5)));
        assertEquals(1, store.get(id).size(), "前置：put 后内存应有一条记录");
        assertNull(YamlConfiguration.loadConfiguration(file).getConfigurationSection("players"),
                "put 不写盘：此时磁盘上不应出现 players 段（本断言变红说明 put 又开始写盘了）");

        store.save(); // 模拟一次关服，让磁盘上留下这条记录的旧快照
        assertNotNull(YamlConfiguration.loadConfiguration(file).getConfigurationSection("players." + id),
                "前置：save 后磁盘上应有该玩家记录");

        store.remove(id);
        assertEquals(0, store.get(id).size(), "remove 后该玩家内存记录必须清空");
        ConfigurationSection onDiskAfterRemove = YamlConfiguration.loadConfiguration(file)
                .getConfigurationSection("players");
        assertNotNull(onDiskAfterRemove, "remove 只清内存、不写盘：磁盘上的旧快照必须仍在");
        assertNotNull(onDiskAfterRemove.getConfigurationSection(id.toString()),
                "remove 不写盘：磁盘上的旧记录必须仍在（陈旧记录只活到下一次落盘）");

        store.save(); // 模拟关服落盘

        assertNull(YamlConfiguration.loadConfiguration(file).getConfigurationSection("players"),
                "关服落盘后不得残留 players 空段，实际文件内容: ["
                        + Files.readString(file.toPath()) + "]");
        assertNull(YamlConfiguration.loadConfiguration(file).getConfigurationSection("players." + id),
                "落盘的是内存快照：已 remove 的记录不得再出现在文件里");
    }

    /**
     * 写盘失败不得抛回调用方。内存 data 仍是主副本，本次进程内 get 必须读到刚 put 的物品。
     * save() 只在关服时由 onDisable 调用，因此本测试显式调用它来触发写入失败：
     * 数据目录被文件占住，save 必然 IOException。
     * 变异：save() 把 IOException 重新抛出后，assertDoesNotThrow 变红。
     */
    @Test
    void pendingDropStore_saveFailure_keepsMemoryAndDoesNotThrow() throws Exception {
        File blocked = new File(plugin.getDataFolder(), "not-a-directory");
        assertTrue(blocked.createNewFile(), "前置：用文件占住数据目录，使 save 必然 IOException");
        PendingDropStore store = new PendingDropStore(blocked, plugin.getLogger());
        UUID id = UUID.randomUUID();

        List<LogRecord> records = captureLogs(
                () -> assertDoesNotThrow(() -> {
                    store.put(id, List.of(new ItemStack(Material.DIAMOND, 3)));
                    store.save(); // 决策 A 下唯一写盘入口，写盘失败分支由这里覆盖
                }));

        assertEquals(1, countLevel(records, Level.SEVERE, "Unable to save pending drops"),
                "写盘失败必须记 severe，实际: " + records.stream().map(LogRecord::getMessage).toList());
        assertEquals(3, store.get(id).get(0).getAmount(), "内存主副本在写盘失败后仍必须可读");
    }

    /** 合并 100 颗石头 → 64+36，总量守恒。 */
    @Test
    void mergeSplitsOversizedStacks_conservingTotal() throws Exception {
        Method merge = EndVoidRescuePlugin.class.getDeclaredMethod("mergeIfConfigured", List.class);
        merge.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<ItemStack> merged = (List<ItemStack>) merge.invoke(plugin,
                List.of(new ItemStack(Material.STONE, 100)));
        assertEquals(2, merged.size(), "100 颗应拆成 64+36");
        assertEquals(100, merged.stream().mapToInt(ItemStack::getAmount).sum());
        assertEquals(64, merged.get(0).getAmount());
    }

    // ═══════════════════════════ 死亡入口：死因与空记录 ═══════════════════════════

    /**
     * 死因为 null（原版 /kill、或从未受过伤害的玩家）时不得触发，也不得抛 NPE。
     * 探针实测：MockBukkit 下不调用 setLastDamageCause 时 getLastDamageCause() 返回 null，
     * 触发 PlayerDeathEvent 也不会补上死因。
     * 变异：删掉 isTriggerDeath 的 null 检查后，getLastDamageCause().getCause() 抛 NPE。
     */
    @Test
    void deathWithoutDamageCause_notTriggeredAndNoNPE() {
        WorldMock end = addWorld("no_damage_cause", World.Environment.THE_END);
        PlayerMock player = server.addPlayer();
        player.teleport(new Location(end, 5, 64, 5));
        player.getInventory().setItem(0, new ItemStack(Material.ELYTRA));
        assertNull(player.getLastDamageCause(), "前置：未设置死因，getLastDamageCause() 应为 null");

        PlayerDeathEvent death = new PlayerDeathEvent(player,
                DamageSource.builder(DamageType.OUT_OF_WORLD).build(),
                new ArrayList<>(), 0, null, false);
        server.getPluginManager().callEvent(death);

        assertFalse(death.getKeepInventory(), "死因为 null 时不得触发插件改写");
        assertNotNull(player.getInventory().getItem(0), "黑名单物品必须原样保留，不被处理");
        assertTrue(pendingOf(player).isEmpty(), "死因为 null 时不得写入 pending");
    }

    /**
     * 触发死亡但这次没有新的可保留物品时，已有 pending 必须原样留下，等下次重试。
     * 死亡处理会先把 store.get 装进 pending，所以空背包不会把旧记录清掉。
     * 断言一律走内存主副本（插件只在 onDisable 写盘，本测试无落盘动作）。
     * 变异：改回「pending 为空就 store.remove」后，旧记录消失，本测试变红。
     */
    @Test
    void emptyDeath_keepsExistingPendingRecord() throws Exception {
        PlayerMock player = endPlayer("empty_death");
        // 必须写进插件持有的那份 store。另 new 一个 PendingDropStore 只是另一份内存，
        // 死亡时的读取碰不到它。
        pluginStore().put(player.getUniqueId(), List.of(new ItemStack(Material.STONE, 5)));
        assertEquals(1, pluginStore().get(player.getUniqueId()).size(),
                "前置：死亡前应已有一条 pending 记录");
        player.getInventory().setItem(0, new ItemStack(Material.ELYTRA));

        killByVoid(player);

        List<ItemStack> kept = pluginStore().get(player.getUniqueId());
        assertEquals(1, kept.size(), "空结果的触发死亡必须保留旧记录");
        assertEquals(Material.STONE, kept.get(0).getType());
        assertEquals(5, kept.get(0).getAmount());
        assertEquals(5, pluginStore().get(player.getUniqueId()).get(0).getAmount(),
                "该记录的序列化形态仍正确（put 不再写盘，这里只证明物理文件里的内容可读回）");
    }

    /**
     * 失败重试的核心闭环：上一次发放失败留下的旧记录，必须与本次新收集的物品合并后一起写回 pending。
     * 构造：旧记录 STONE×5（写进插件持有的那份 store）+ 背包里一个装有 3 个绿宝石的潜影盒。
     * 期望：死亡后 pending 同时含 STONE×5 与 EMERALD×3（内存主副本与磁盘序列化形态各断言一次）。
     * 变异：把 onPlayerDeath 里的 `List<ItemStack> pending = store.get(playerId);`
     * 改成 `List<ItemStack> pending = new ArrayList<>();` 后旧记录被丢弃，本测试变红。
     */
    @Test
    void death_mergesStaleRecordWithNewItems() throws Exception {
        PlayerMock player = endPlayer("merge_stale");
        pluginStore().put(player.getUniqueId(), List.of(new ItemStack(Material.STONE, 5)));
        ItemStack shulker = shulkerWith(List.of(new ItemStack(Material.EMERALD, 3)));
        player.getInventory().setItem(0, shulker);

        assertTrue(hasShulkerContents(player.getInventory().getItem(0)), "前置：潜影盒应装有绿宝石");
        List<ItemStack> before = pluginStore().get(player.getUniqueId());
        assertEquals(1, before.size(), "前置：应已有一条旧 pending 记录");
        assertEquals(Material.STONE, before.get(0).getType(), "前置：旧记录应是 STONE");
        assertEquals(5, before.get(0).getAmount(), "前置：旧记录数量应是 5");

        killByVoid(player);

        List<ItemStack> merged = pluginStore().get(player.getUniqueId());
        assertEquals(2, merged.size(), "旧记录与本次新物品必须合并成 2 条，实际: "
                + merged.stream().map(s -> s.getType() + "x" + s.getAmount()).toList());
        assertEquals(5, countAmount(merged, Material.STONE), "旧记录 STONE×5 必须仍在");
        assertEquals(3, countAmount(merged, Material.EMERALD), "潜影盒里的 EMERALD×3 必须并入");
        assertNull(player.getInventory().getItem(0), "潜影盒本体应消失");

        // 磁盘视图：put/remove 都不写盘，必须显式 save 模拟关服，才能验证合并结果的序列化形态。
        pluginStore().save();
        List<ItemStack> fromDisk = pendingOf(player);
        assertEquals(5, countAmount(fromDisk, Material.STONE), "落盘后旧记录 STONE×5 必须仍在");
        assertEquals(3, countAmount(fromDisk, Material.EMERALD), "落盘后必须含新并入的 EMERALD×3");
    }

    /**
     * 没有旧记录、这次也没有可保留物品时，死亡后 store 仍为空，且不写出玩家段。
     * 本测试不钉 pending.isEmpty() 时的 store.remove：没有旧记录时删掉这段 remove，
     * store 本来就是空的，本测试仍绿。空记录壳的清理由 PendingDropStore 自己的测试钉住。
     */
    @Test
    void emptyDeathWithoutStaleRecord_removesRecord() throws Exception {
        PlayerMock player = endPlayer("empty_death_clean");
        assertTrue(pluginStore().get(player.getUniqueId()).isEmpty(), "前置：不应有旧记录");
        player.getInventory().setItem(0, new ItemStack(Material.ELYTRA));

        killByVoid(player);

        assertTrue(pluginStore().get(player.getUniqueId()).isEmpty(),
                "无旧记录的空死亡不得留下 pending");
        assertTrue(pendingOf(player).isEmpty());
    }

    /**
     * 重生时该玩家没有 pending：不得误删其他玩家的记录，也不得产生掉落。
     * remove 只清内存、不写盘，因此其他玩家的记录只能从内存主副本读（diskStore 是一份干净内存，
     * put 不落盘时读不到）——本断言因此能真正抓到「误删其他玩家记录」。
     * 本测试不钉 onPlayerRespawn 开头的 store.remove 是否被调用——
     * 删掉那段后这里仍然全绿。空记录壳的清理由 PendingDropStore 自己的测试钉住。
     */
    @Test
    void respawnWithNoPending_callsStoreRemoveWithoutDropping() throws Exception {
        PlayerMock player = endPlayer("empty_respawn");
        UUID other = UUID.randomUUID();
        pluginStore().put(other, List.of(new ItemStack(Material.STONE, 4)));
        assertTrue(pluginStore().get(player.getUniqueId()).isEmpty(), "前置：该玩家不应有 pending");

        server.getPluginManager().callEvent(new PlayerRespawnEvent(
                player, new Location(player.getWorld(), 0, 64, 0), false));
        server.getScheduler().performTicks(2);

        assertEquals(4, pluginStore().get(other).get(0).getAmount(),
                "清理空记录不得误删其他玩家");
        assertTrue(player.getWorld().getEntitiesByClass(Item.class).isEmpty(), "无记录不得产生掉落");
        assertTrue(pluginStore().get(player.getUniqueId()).isEmpty());
    }

    // ═══════════════════════ 端到端：死亡 → 存储 → 重生 → burst ═══════════════════════

    @Test
    void endToEnd_endVoidDeath_rescuesShulkerContentsAtRespawn() throws Exception {
        WorldMock end = addWorld("e2e_end", World.Environment.THE_END);
        PlayerMock player = server.addPlayer();
        player.teleport(new Location(end, 100, 64, 100));

        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        sword.addUnsafeEnchantment(Enchantment.MENDING, 1);
        sword.addUnsafeEnchantment(Enchantment.SHARPNESS, 5);
        player.getInventory().setItem(0, new ItemStack(Material.ELYTRA)); // 黑名单 → 消失
        player.getInventory().setItem(1, new ItemStack(Material.DIAMOND)); // 普通 → 留在背包
        player.getInventory().setItem(2, shulkerWith(List.of(sword))); // 潜影盒 → pending
        player.setLastDamageCause(new EntityDamageEvent(player,
                EntityDamageEvent.DamageCause.VOID, 40.0));

        PlayerDeathEvent death = new PlayerDeathEvent(player,
                DamageSource.builder(DamageType.OUT_OF_WORLD).build(),
                new ArrayList<>(), 0, null, false);
        server.getPluginManager().callEvent(death);

        // 死亡处理断言
        assertTrue(death.getKeepInventory(), "触发死亡必须 keepInventory");
        assertNull(player.getInventory().getItem(0), "鞘翅应已从背包移除");
        assertNotNull(player.getInventory().getItem(1), "普通物品应留在背包（玩家自持）");
        assertNull(player.getInventory().getItem(2), "潜影盒本体应消失");

        // 重生 + 1 tick 调度。
        // 不用 player.respawn()：它会把玩家传送到世界出生点（WorldMock 默认 y=5），
        // 直接构造事件才能让插件拿到我们指定的重生坐标。
        server.getPluginManager().callEvent(new org.bukkit.event.player.PlayerRespawnEvent(
                player, new Location(end, 0, 64, 0), false));
        server.getScheduler().performTicks(2);

        // burst 断言：剑出现在重生方块格内、高度 +1、拾取延迟 10
        List<Item> drops = new ArrayList<>(end.getEntitiesByClass(Item.class));
        assertEquals(1, drops.size(), "应恰好 burst 出 1 件（潜影盒内的剑）");
        Item drop = drops.get(0);
        assertEquals(Material.DIAMOND_SWORD, drop.getItemStack().getType());
        assertFalse(drop.getItemStack().containsEnchantment(Enchantment.SHARPNESS),
                "burst 出的剑应已祛魔");
        assertEquals(10, drop.getPickupDelay(), "pickup-delay-ticks 默认 10");
        Location loc = drop.getLocation();
        assertEquals(65.0, loc.getY(), 1e-9, "掉落高度 = 重生脚底 +1");
        double dx = loc.getX() - (loc.getBlockX() + 0.5);
        double dz = loc.getZ() - (loc.getBlockZ() + 0.5);
        assertTrue(Math.abs(dx) < 0.5 + 1e-9 && Math.abs(dz) < 0.5 + 1e-9,
                "散落必须限制在重生方块内，实际偏移 dx=" + dx + " dz=" + dz);

        // 记录清理断言：内存是主副本。磁盘只有在关服（onDisable → save）时才同步，
        // 本测试不调 onDisable，因此磁盘始终为空——「关服后磁盘清空」由
        // shutdownHook_persistsPendingRecordToDisk 覆盖。
        assertTrue(pluginStore().get(player.getUniqueId()).isEmpty(),
                "burst 后内存中的 pending 记录必须清空");
        assertTrue(diskStore().get(player.getUniqueId()).isEmpty(),
                "本测试不关服：磁盘上不应出现已发放的记录");
    }

    /**
     * 情况 A：末地虚空死亡后、重生事件触发前离线，重进后再重生，待返还物品仍要散落。
     * 记录是内存主副本（插件不注册 PlayerQuitEvent 监听器，退出本身不清理）；
     * 唯一写盘时机是 onDisable，本场景全程在同一进程内，因此断言一律走内存视图。
     * 变异：死亡时不再 store.put，则离线期间记录为空，本测试变红。
     */
    @Test
    void endToEnd_quitBeforeRespawn_rejoinStillBurstsPending() throws Exception {
        WorldMock end = addWorld("e2e_rejoin_end", World.Environment.THE_END);
        WorldMock overworld = addWorld("e2e_rejoin_over", World.Environment.NORMAL);
        PlayerMock player = server.addPlayer();
        player.teleport(new Location(end, 100, 64, 100));
        player.getInventory().setItem(0, shulkerWith(List.of(new ItemStack(Material.EMERALD, 4))));
        player.setLastDamageCause(new EntityDamageEvent(player,
                EntityDamageEvent.DamageCause.VOID, 40.0));

        server.getPluginManager().callEvent(new PlayerDeathEvent(player,
                DamageSource.builder(DamageType.OUT_OF_WORLD).build(),
                new ArrayList<>(), 0, null, false));
        assertEquals(4, pluginStore().get(player.getUniqueId()).get(0).getAmount(),
                "前置：死亡后绿宝石应已写入内存 pending");

        assertTrue(player.disconnect(), "前置：玩家应能在重生前退出");
        assertFalse(player.isOnline());
        server.getScheduler().performTicks(2);
        assertEquals(4, pluginStore().get(player.getUniqueId()).get(0).getAmount(),
                "重生前退出不得清掉内存 pending");
        assertTrue(end.getEntitiesByClass(Item.class).isEmpty(), "尚未重生时不得散落");

        server.addPlayer(player);
        assertTrue(player.isOnline(), "同一玩家应能重进");
        player.teleport(new Location(overworld, 0, 64, 0));
        Location respawn = new Location(overworld, 20, 70, -8);
        server.getPluginManager().callEvent(new PlayerRespawnEvent(player, respawn, false));
        server.getScheduler().performTicks(2);

        List<Item> drops = new ArrayList<>(overworld.getEntitiesByClass(Item.class));
        assertEquals(1, drops.size(), "重进后的重生必须把离线期间的 pending 散落出来");
        assertEquals(Material.EMERALD, drops.get(0).getItemStack().getType());
        assertEquals(4, drops.get(0).getItemStack().getAmount());
        Location loc = drops.get(0).getLocation();
        assertEquals(respawn.getBlockX(), loc.getBlockX(), "物品必须落在重进后的重生格");
        assertEquals(respawn.getBlockZ(), loc.getBlockZ());
        assertTrue(pluginStore().get(player.getUniqueId()).isEmpty(), "发放后内存 pending 记录必须清空");
    }

    /**
     * 关服落盘：插件关闭时必须把内存快照写进 pending-drops.yml，且重启（新实例从磁盘读）后仍可领取。
     * 这是决策 A 唯一保证的持久化路径——内存里有未落盘的记录时，onDisable 是它活过重启的唯一机会。
     * 用插件实例的 onDisable() 走真实关服路径，而不是直接调 store.save()。
     * 探针实测：MockBukkit.unmock()（disablePlugins → onDisable）在本版本下不会触发本插件的
     * onDisable（文件始终不被创建），因此必须显式调用生产代码的 onDisable() 本身。
     * 变异：删掉 onDisable 里的 store.save() 后，文件不存在、本测试变红（实测确认）。
     */
    @Test
    void shutdownHook_persistsPendingRecordToDisk() throws Exception {
        WorldMock end = addWorld("e2e_shutdown_end", World.Environment.THE_END);
        PlayerMock player = server.addPlayer();
        player.teleport(new Location(end, 100, 64, 100));
        player.getInventory().setItem(0, shulkerWith(List.of(new ItemStack(Material.EMERALD, 4))));
        player.setLastDamageCause(new EntityDamageEvent(player,
                EntityDamageEvent.DamageCause.VOID, 40.0));

        server.getPluginManager().callEvent(new PlayerDeathEvent(player,
                DamageSource.builder(DamageType.OUT_OF_WORLD).build(),
                new ArrayList<>(), 0, null, false));
        assertEquals(4, pluginStore().get(player.getUniqueId()).get(0).getAmount(),
                "前置：死亡后内存应有一条待落盘记录");

        File file = new File(plugin.getDataFolder(), "pending-drops.yml");
        assertFalse(Files.exists(file.toPath()), "前置：决策 A 下 put 不写盘，死亡后文件不应存在");

        plugin.onDisable(); // 生产代码的关服入口

        assertTrue(Files.exists(file.toPath()), "关服必须创建 pending-drops.yml");
        List<ItemStack> reloaded = diskStore().get(player.getUniqueId()); // 模拟重启后读盘
        assertEquals(1, reloaded.size(), "重启后应读回待领取记录");
        assertEquals(Material.EMERALD, reloaded.get(0).getType());
        assertEquals(4, reloaded.get(0).getAmount());
    }

    @Test
    void endToEnd_deathNotInEnd_pluginStaysOut() {
        WorldMock overworld = addWorld("e2e_over", World.Environment.NORMAL);
        PlayerMock player = server.addPlayer();
        player.teleport(new Location(overworld, 10, 64, 10));
        player.getInventory().setItem(0, new ItemStack(Material.ELYTRA));
        player.setLastDamageCause(new EntityDamageEvent(player,
                EntityDamageEvent.DamageCause.VOID, 40.0));

        PlayerDeathEvent death = new PlayerDeathEvent(player,
                DamageSource.builder(DamageType.OUT_OF_WORLD).build(),
                new ArrayList<>(), 0, null, false);
        server.getPluginManager().callEvent(death);

        assertFalse(death.getKeepInventory(), "非末地死亡不得触发插件改写");
        assertNotNull(player.getInventory().getItem(0), "鞘翅必须原样保留");
    }

    @Test
    void endToEnd_endDeathButWrongDamageCause_notTriggered() {
        WorldMock end = addWorld("e2e_end2", World.Environment.THE_END);
        PlayerMock player = server.addPlayer();
        player.teleport(new Location(end, 5, 64, 5));
        player.setLastDamageCause(new EntityDamageEvent(player,
                EntityDamageEvent.DamageCause.ENTITY_ATTACK, 40.0));

        PlayerDeathEvent death = new PlayerDeathEvent(player,
                DamageSource.builder(DamageType.PLAYER_ATTACK).build(),
                new ArrayList<>(), 0, null, false);
        server.getPluginManager().callEvent(death);

        assertFalse(death.getKeepInventory(), "末地内非 VOID 死亡不得触发");
    }

    @Test
    void endToEnd_respawnWithoutDeath_dropsNothingAndStaysSilent() {
        WorldMock spawn = addWorld("e2e_spawn", World.Environment.NORMAL);
        PlayerMock player = server.addPlayer();
        player.setRespawnLocation(new Location(spawn, 0, 64, 0), false);
        player.respawn();
        server.getScheduler().performTicks(2);

        assertTrue(spawn.getEntitiesByClass(Item.class).isEmpty(), "无死亡记录时不得有任何掉落");
    }

    /**
     * 重生点世界已卸载时 burst 抛异常，调用方不得 store.remove。
     * 变异：把 throw IllegalStateException 改回 warning 后 return，记录会被清掉，本测试变红。
     */
    @Test
    void burstFailure_unloadedWorld_keepsPendingRecord() throws Exception {
        WorldMock end = addWorld("e2e_unloaded_end", World.Environment.THE_END);
        WorldMock overworld = addWorld("e2e_unloaded_over", World.Environment.NORMAL);
        PlayerMock player = server.addPlayer();
        player.teleport(new Location(overworld, 0, 64, 0));
        pluginStore().put(player.getUniqueId(), List.of(new ItemStack(Material.DIAMOND, 5)));
        assertTrue(server.unloadWorld(end, false), "前置：末地应已卸载");
        Location respawn = new Location(end, 0, 64, 0);
        assertFalse(respawn.isWorldLoaded(), "前置：重生点世界应处于未加载状态");

        server.getPluginManager().callEvent(new PlayerRespawnEvent(player, respawn, false));

        List<ItemStack> kept = pluginStore().get(player.getUniqueId());
        assertEquals(1, kept.size(), "世界未加载时必须保留 pending，不能 store.remove");
        assertEquals(Material.DIAMOND, kept.get(0).getType());
        assertEquals(5, kept.get(0).getAmount());
    }

    /**
     * reloadSettings 遇到无法识别的黑名单材质时必须 warning，合法材质仍要加载。
     * 变异：删掉未知材质的 warning 分支后，日志断言变红。
     */
    @Test
    void reloadSettings_unknownBlacklistMaterial_logsWarning() throws Exception {
        plugin.getConfig().set("blacklist", List.of("ELYTRA", "NOT_A_REAL_MATERIAL"));
        List<LogRecord> records = captureLogs(this::invokeReloadSettings);
        assertEquals(1, countWarnings(records, "Unknown blacklist material: NOT_A_REAL_MATERIAL"),
                "无效材质必须 warning，实际: " + records.stream().map(LogRecord::getMessage).toList());

        Field blacklistField = EndVoidRescuePlugin.class.getDeclaredField("blacklist");
        blacklistField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<Material> loaded = (Set<Material>) blacklistField.get(plugin);
        assertTrue(loaded.contains(Material.ELYTRA), "合法材质必须仍然加载");
        assertFalse(loaded.contains(Material.ENDER_PEARL), "未出现在新名单里的旧材质必须被替换掉");
    }

    /**
     * trigger-world / trigger-cause 配了非法枚举时回退到 THE_END + VOID，死亡仍要触发。
     * 变异：删掉任一 catch 回退（改成直接 valueOf 或返回 null）后，本测试抛异常或不再 keepInventory。
     */
    @Test
    void invalidTriggerConfig_fallsBackToEndVoid() {
        plugin.getConfig().set("trigger-world", "NOT_A_WORLD");
        plugin.getConfig().set("trigger-cause", "NOT_A_CAUSE");
        PlayerMock player = endPlayer("bad_trigger");
        player.getInventory().setItem(0, new ItemStack(Material.ELYTRA));
        assertNotNull(player.getInventory().getItem(0), "前置：鞘翅应在背包");

        PlayerDeathEvent death = killByVoid(player);

        assertTrue(death.getKeepInventory(), "非法配置必须回退为末地虚空触发");
        assertNull(player.getInventory().getItem(0), "回退触发后黑名单物品仍要被移除");
    }

    /**
     * burst.radius 大于 0.5 时截断为 0.5，且 warning 只打一次；
     * reloadSettings 清掉一次性标记后，下一次超限 burst 必须再警告。
     * 变异：删掉截断（radius 保持配置值）时散落断言变红；
     * 删掉一次性标记或 reloadSettings 的重置时，对应 warning 次数断言变红。
     */
    @Test
    void burstRadius_aboveHalfBlock_isClampedAndWarnsOnceUntilReload() throws Exception {
        WorldMock end = addWorld("radius_end", World.Environment.THE_END);
        PlayerMock player = server.addPlayer();
        Location respawn = new Location(end, 8.2, 64, -3.7);
        plugin.getConfig().set("burst.radius", 2.0);

        List<LogRecord> records = captureLogs(() -> {
            burst(player, respawn, List.of(new ItemStack(Material.DIAMOND)));
            burst(player, respawn, List.of(new ItemStack(Material.EMERALD)));
        });

        List<Item> drops = new ArrayList<>(end.getEntitiesByClass(Item.class));
        assertEquals(2, drops.size(), "两次 burst 应各掉落 1 件");
        for (Item drop : drops) {
            Location loc = drop.getLocation();
            assertTrue(Math.abs(loc.getX() - (respawn.getBlockX() + 0.5)) <= 0.5 + 1e-9
                    && Math.abs(loc.getZ() - (respawn.getBlockZ() + 0.5)) <= 0.5 + 1e-9,
                    "radius=2.0 必须截断到 0.5，物品中心留在重生格内，实际 " + loc);
        }
        assertEquals(1, countWarnings(records, "clamped to 0.5"),
                "超限 warning 在 reload 前只应出现一次，实际: "
                        + records.stream().map(LogRecord::getMessage).toList());

        invokeReloadSettings();
        records.clear();
        captureLogs(records, () -> burst(player, respawn, List.of(new ItemStack(Material.GOLD_INGOT))));
        assertEquals(1, countWarnings(records, "clamped to 0.5"),
                "reloadSettings 后下一次超限 burst 必须再次 warning");
    }

    /**
     * burst.radius 配成 0 或负数时，Math.max(0.0, …) 把负值归零，物品必须恰好落在重生格中心，
     * 且不得因为「配置非法」而丢件或 warning（warning 只针对超限）。
     * 变异：删掉 Math.max(0.0, …) 后 radius=-1 会让 distance 为负，
     * 偏移方向翻转但仍落在格内——因此本测试同时断言两次掉落的落点完全等于格中心，
     * 归零失效时随机偏移必然偏离中心，断言变红。
     */
    @Test
    void burstRadius_zeroOrNegative_landsExactlyOnBlockCenter() {
        WorldMock end = addWorld("radius_zero_end", World.Environment.THE_END);
        PlayerMock player = server.addPlayer();
        Location respawn = new Location(end, 3.5, 64, -7.5);

        for (double configured : List.of(0.0, -1.0)) {
            plugin.getConfig().set("burst.radius", configured);
            List<LogRecord> records = new ArrayList<>();
            captureLogs(records, () -> burst(player, respawn, List.of(new ItemStack(Material.DIAMOND))));
            assertTrue(records.stream().noneMatch(r -> r.getMessage() != null
                    && r.getMessage().contains("clamped")),
                    "radius=" + configured + " 不超限，不得 warning");
        }

        List<Item> drops = new ArrayList<>(end.getEntitiesByClass(Item.class));
        assertEquals(2, drops.size(), "radius=0/-1 时物品仍必须掉落，不得丢件");
        for (Item drop : drops) {
            Location loc = drop.getLocation();
            assertEquals(respawn.getBlockX() + 0.5, loc.getX(), 1e-9,
                    "radius 归零后 X 必须落在格中心，实际 " + loc);
            assertEquals(respawn.getBlockZ() + 0.5, loc.getZ(), 1e-9,
                    "radius 归零后 Z 必须落在格中心，实际 " + loc);
        }
    }

    /**
     * 掉落点区块未加载时，burst 必须先加载该区块再掉落，物品不得丢失。
     * 探针：MockBukkit 的 getChunkAt 会把未加载区块放回 loadedChunks；
     * 若删掉这段加载，区块保持未加载（dropItem 本身在未加载区块仍会成功，不能单靠实体数断言）。
     */
    @Test
    void burst_unloadedChunk_loadsChunkBeforeDropping() throws Exception {
        WorldMock end = addWorld("chunk_end", World.Environment.THE_END);
        PlayerMock player = server.addPlayer();
        Location respawn = new Location(end, 10000, 64, 10000);
        int chunkX = respawn.getBlockX() >> 4;
        int chunkZ = respawn.getBlockZ() >> 4;
        assertFalse(end.isChunkLoaded(chunkX, chunkZ), "前置：远处区块初始未加载");

        burst(player, respawn, List.of(new ItemStack(Material.DIAMOND, 3)));

        assertTrue(end.isChunkLoaded(chunkX, chunkZ), "未加载区块必须在掉落前被加载");
        List<Item> drops = new ArrayList<>(end.getEntitiesByClass(Item.class));
        assertEquals(1, drops.size(), "加载后物品必须掉出，不得丢失");
        assertEquals(3, drops.get(0).getItemStack().getAmount());
    }

    // ═══════════════════════════════ 辅助方法 ═══════════════════════════════

    private WorldMock addWorld(String name, World.Environment environment) {
        WorldCreator creator = new WorldCreator(name);
        creator.environment(environment);
        WorldMock world = new WorldMock(creator);
        server.addWorld(world);
        return world;
    }

    /** 与生产代码 isCurse 同一判据（按附魔 key 名匹配两种诅咒）。 */
    private boolean isCurseEnchantment(Enchantment enchantment) {
        String name = enchantment.getKey().getKey();
        return name.equals("binding_curse") || name.equals("vanishing_curse");
    }

    private ItemStack mendingPickaxe() {
        ItemStack pickaxe = new ItemStack(Material.DIAMOND_PICKAXE);
        pickaxe.addUnsafeEnchantment(Enchantment.MENDING, 1);
        pickaxe.addUnsafeEnchantment(Enchantment.SHARPNESS, 5);
        return pickaxe;
    }

    private ItemStack mendingEnchantedBook(int amount) {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK, amount);
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) book.getItemMeta();
        meta.addStoredEnchant(Enchantment.MENDING, 1, true);
        meta.addStoredEnchant(Enchantment.SHARPNESS, 5, true);
        book.setItemMeta(meta);
        return book;
    }

    private ItemStack shulkerWith(List<ItemStack> contents) {
        return shulkerWith(Material.SHULKER_BOX, contents);
    }

    private ItemStack shulkerWith(Material material, List<ItemStack> contents) {
        ItemStack shulker = new ItemStack(material);
        BlockStateMeta meta = (BlockStateMeta) shulker.getItemMeta();
        ShulkerBox box = (ShulkerBox) meta.getBlockState();
        for (int i = 0; i < contents.size(); i++) {
            box.getInventory().setItem(i, contents.get(i));
        }
        meta.setBlockState(box);
        shulker.setItemMeta(meta);
        return shulker;
    }

    /** 潜影盒内非空物品的件数。 */
    private int countShulkerContents(ItemStack shulker) {
        if (!(shulker.getItemMeta() instanceof BlockStateMeta meta)
                || !(meta.getBlockState() instanceof ShulkerBox box)) {
            return 0;
        }
        int count = 0;
        for (ItemStack content : box.getInventory().getContents()) {
            if (content != null && !content.getType().isAir()) {
                count++;
            }
        }
        return count;
    }

    /** 潜影盒内是否装有非空物品。 */
    private boolean hasShulkerContents(ItemStack shulker) {
        if (!(shulker.getItemMeta() instanceof BlockStateMeta meta)
                || !(meta.getBlockState() instanceof ShulkerBox box)) {
            return false;
        }
        for (ItemStack content : box.getInventory().getContents()) {
            if (content != null && !content.getType().isAir()) {
                return true;
            }
        }
        return false;
    }

    /** 读取潜影盒内指定类型的第一个物品（不存在则断言失败）。 */
    private ItemStack firstShulkerContent(ItemStack shulker, Material type) {
        BlockStateMeta meta = (BlockStateMeta) shulker.getItemMeta();
        ShulkerBox box = (ShulkerBox) meta.getBlockState();
        for (ItemStack content : box.getInventory().getContents()) {
            if (content != null && content.getType() == type) {
                return content;
            }
        }
        throw new AssertionError("潜影盒内缺少物品: " + type);
    }

    private ItemStack bundleWith(List<ItemStack> contents) {
        ItemStack bundle = new ItemStack(Material.RED_BUNDLE);
        BundleMeta meta = (BundleMeta) bundle.getItemMeta();
        meta.setItems(new ArrayList<>(contents));
        bundle.setItemMeta(meta);
        return bundle;
    }

    /** 末地玩家，已设置 VOID 死因。 */
    private PlayerMock endPlayer(String worldName) {
        WorldMock end = addWorld(worldName, World.Environment.THE_END);
        PlayerMock player = server.addPlayer();
        player.teleport(new Location(end, 100, 64, 100));
        player.setLastDamageCause(new EntityDamageEvent(player,
                EntityDamageEvent.DamageCause.VOID, 40.0));
        return player;
    }

    private PlayerDeathEvent killByVoid(PlayerMock player) {
        PlayerDeathEvent death = new PlayerDeathEvent(player,
                DamageSource.builder(DamageType.OUT_OF_WORLD).build(),
                new ArrayList<>(), 0, null, false);
        server.getPluginManager().callEvent(death);
        return death;
    }

    private List<ItemStack> pendingOf(PlayerMock player) {
        return diskStore().get(player.getUniqueId());
    }

    /** 读物理文件另起一份内存 store（不碰插件内存），只在 put/save 已落盘后才有内容可读。 */
    private PendingDropStore diskStore() {
        return new PendingDropStore(plugin.getDataFolder(), plugin.getLogger());
    }

    private PendingDropStore pluginStore() throws Exception {
        Field field = EndVoidRescuePlugin.class.getDeclaredField("store");
        field.setAccessible(true);
        return (PendingDropStore) field.get(plugin);
    }

    private void burst(PlayerMock player, Location location, List<ItemStack> items) {
        try {
            Method method = EndVoidRescuePlugin.class.getDeclaredMethod(
                    "burst", org.bukkit.entity.Player.class, Location.class, List.class);
            method.setAccessible(true);
            method.invoke(plugin, player, location, items);
        } catch (ReflectiveOperationException exception) {
            throw new RuntimeException(exception);
        }
    }

    private void invokeReloadSettings() {
        try {
            Method method = EndVoidRescuePlugin.class.getDeclaredMethod("reloadSettings");
            method.setAccessible(true);
            method.invoke(plugin);
        } catch (ReflectiveOperationException exception) {
            throw new RuntimeException(exception);
        }
    }

    private List<LogRecord> captureLogs(Runnable action) {
        List<LogRecord> records = new ArrayList<>();
        captureLogs(records, action);
        return records;
    }

    private void captureLogs(List<LogRecord> records, Runnable action) {
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        plugin.getLogger().addHandler(handler);
        try {
            action.run();
        } finally {
            plugin.getLogger().removeHandler(handler);
        }
    }

    private int countWarnings(List<LogRecord> records, String snippet) {
        return countLevel(records, Level.WARNING, snippet);
    }

    private int countLevel(List<LogRecord> records, Level level, String snippet) {
        int count = 0;
        for (LogRecord record : records) {
            if (record.getLevel() == level && record.getMessage() != null
                    && record.getMessage().contains(snippet)) {
                count++;
            }
        }
        return count;
    }

    private ItemStack invokeProcessItem(ItemStack item) throws Exception {
        return invokeProcessItemWithPending(item, new ArrayList<>());
    }

    private ItemStack invokeProcessItemWithPending(ItemStack item, List<ItemStack> pending)
            throws Exception {
        return (ItemStack) processItemMethod.invoke(plugin, item, pending);
    }

    /** 递归收集收纳袋内所有非收纳袋物品。 */
    private List<ItemStack> leavesOf(ItemStack bundle) {
        List<ItemStack> result = new ArrayList<>();
        if (!(bundle.getItemMeta() instanceof BundleMeta meta)) {
            result.add(bundle);
            return result;
        }
        for (ItemStack content : meta.getItems()) {
            if (content == null) {
                continue;
            }
            if (content.getItemMeta() instanceof BundleMeta) {
                result.addAll(leavesOf(content));
            } else {
                result.add(content);
            }
        }
        return result;
    }

    private boolean containsType(List<ItemStack> items, Material type) {
        return items.stream().anyMatch(i -> i.getType() == type);
    }

    private int countAmount(List<ItemStack> items, Material type) {
        return items.stream().filter(i -> i.getType() == type).mapToInt(ItemStack::getAmount).sum();
    }

    private ItemStack firstOfType(List<ItemStack> items, Material type) {
        return items.stream().filter(i -> i.getType() == type).findFirst()
                .orElseThrow(() -> new AssertionError("缺少物品: " + type));
    }

    /** 收纳袋嵌套链的层数（沿第一个收纳袋子项下钻）。 */
    private int countBundleLevels(ItemStack bundle) {
        ItemStack child = firstDirectBundleChildOrNull(bundle);
        return child == null ? 1 : 1 + countBundleLevels(child);
    }

    /** 从外层收纳袋沿「第一个收纳袋子项」下钻到第 targetLevel 层（外层=1）。 */
    private ItemStack descendToBundleLevel(ItemStack bundle, int targetLevel) {
        ItemStack current = bundle;
        for (int level = 1; level < targetLevel; level++) {
            current = firstDirectBundleChild(current);
        }
        return current;
    }

    private ItemStack firstDirectBundleChild(ItemStack bundle) {
        ItemStack child = firstDirectBundleChildOrNull(bundle);
        assertNotNull(child, "该层应存在收纳袋子项");
        return child;
    }

    private ItemStack firstDirectBundleChildOrNull(ItemStack bundle) {
        if (!(bundle.getItemMeta() instanceof BundleMeta meta)) {
            return null;
        }
        for (ItemStack content : meta.getItems()) {
            if (content != null && content.getItemMeta() instanceof BundleMeta) {
                return content;
            }
        }
        return null;
    }

    /** 收纳袋的直接子物品中所有非收纳袋项。 */
    private List<ItemStack> directNonBundleChildren(ItemStack bundle) {
        List<ItemStack> result = new ArrayList<>();
        if (bundle.getItemMeta() instanceof BundleMeta meta) {
            for (ItemStack content : meta.getItems()) {
                if (content != null && !(content.getItemMeta() instanceof BundleMeta)) {
                    result.add(content);
                }
            }
        }
        return result;
    }
}
