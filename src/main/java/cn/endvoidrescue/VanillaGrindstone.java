package cn.endvoidrescue;

import org.bukkit.inventory.ItemStack;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 通过反射调用原版（NMS）砂轮祛魔逻辑。
 *
 * 公开 API 没有砂轮计算能力：{@code GrindstoneInventory} 是被动容器，结果由服务端在容器 tick 时
 * 自己算好写入，且不存在 GrindstoneRecipe 接口、RecipeType 也没有 GRINDSTONE。所以只能走 NMS。
 *
 * 类名与方法名不做硬编码断言：MC 映射会变，且本仓库无法在离线环境核对 26.1.2 的确切签名。
 * 这里按「形状」发现目标方法（静态、返回 NMS ItemStack、参数含 boolean keepCurses），
 * 任一环节失败即判定不可用，由调用方回退手写实现。
 *
 * 只调用纯计算方法，绝不触碰 takeResult 一类会发放经验球的路径。
 */
final class VanillaGrindstone {
    private static final String CRAFT_ITEM_STACK = "org.bukkit.craftbukkit.CraftItemStack";
    private static final String RECIPE_CLASS = "net.minecraft.world.item.crafting.GrindstoneRecipe";
    private static final String NMS_ITEM_STACK = "net.minecraft.world.item.ItemStack";

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    private VanillaGrindstone() {
    }

    /**
     * 解析结果。三态：未解析 / 已解析但不可用 / 已解析且可用。
     * 用显式 attempted 标记，避免「不可用」被当成「未解析」而每次调用都重新 Class.forName
     * 并重复打 warning（祛魔是按物品调用的，背包满时一次死亡就能刷几十条）。
     */
    private record Resolution(boolean attempted, Strategy strategy) {
        static final Resolution UNRESOLVED = new Resolution(false, null);
        static final Resolution UNAVAILABLE = new Resolution(true, null);
    }

    private static volatile Resolution resolution = Resolution.UNRESOLVED;

    /**
     * 原版砂轮是否可用。首次调用时解析，之后复用结果。
     * 在 MockBukkit 等没有 NMS 的环境恒为 false——测试只能覆盖回退路径。
     */
    static boolean available(Logger logger) {
        return resolve(logger).strategy() != null;
    }

    /**
     * 用原版砂轮逻辑祛魔（保留诅咒）。
     *
     * @return 祛魔后的物品；不可用或调用失败时返回 {@code null}，调用方必须回退手写实现
     */
    static ItemStack disenchant(ItemStack item, Logger logger) {
        Strategy resolved = resolve(logger).strategy();
        if (resolved == null) {
            return null;
        }
        try {
            Object nmsItem = resolved.toNms.invoke(item);
            if (nmsItem == null) {
                return null;
            }
            Object result = resolved.removeEnchantments.invoke(nmsItem, true);
            if (result == null) {
                return null;
            }
            Object back = resolved.toBukkit.invoke(result);
            return back instanceof ItemStack converted ? converted : null;
        } catch (Throwable failure) {
            // 含 NoSuchMethodError / linkage 错误：映射变了也不能让死亡事件炸掉。
            // 直接判定不可用并只记一次，不逐物品重试。
            logger.log(Level.WARNING, "Vanilla grindstone call failed, using built-in logic for this session",
                    failure);
            resolution = Resolution.UNAVAILABLE;
            return null;
        }
    }

    private static Resolution resolve(Logger logger) {
        Resolution cached = resolution;
        if (cached.attempted()) {
            return cached;
        }
        synchronized (VanillaGrindstone.class) {
            if (!resolution.attempted()) {
                resolution = doResolve(logger);
            }
            return resolution;
        }
    }

    private static Resolution doResolve(Logger logger) {
        try {
            Class<?> craftItemStack = Class.forName(CRAFT_ITEM_STACK);
            Class<?> nmsItemStack = Class.forName(NMS_ITEM_STACK);
            Class<?> recipe = Class.forName(RECIPE_CLASS);

            MethodHandle toNms = findConverter(craftItemStack, "asNMSCopy",
                    org.bukkit.inventory.ItemStack.class, nmsItemStack);
            // asCraftMirror 保留 CraftItemStack 身份，避免 asBukkitCopy 在往返中丢数据组件。
            MethodHandle toBukkit = findConverter(craftItemStack, "asCraftMirror",
                    nmsItemStack, org.bukkit.inventory.ItemStack.class);
            if (toBukkit == null) {
                toBukkit = findConverter(craftItemStack, "asBukkitCopy",
                        nmsItemStack, org.bukkit.inventory.ItemStack.class);
            }
            if (toNms == null || toBukkit == null) {
                logUnavailable(logger, "CraftItemStack converter methods not found in " + CRAFT_ITEM_STACK);
                return Resolution.UNAVAILABLE;
            }

            MethodHandle removeEnchantments = findRemoveEnchantments(recipe, nmsItemStack);
            if (removeEnchantments == null) {
                logUnavailable(logger, "No static " + NMS_ITEM_STACK + " -> (ItemStack, boolean) method on "
                        + RECIPE_CLASS + "; MC mapping likely changed");
                return Resolution.UNAVAILABLE;
            }
            logger.info("Using vanilla grindstone logic via " + RECIPE_CLASS);
            return new Resolution(true, new Strategy(toNms, toBukkit, removeEnchantments));
        } catch (Throwable failure) {
            // 没有 NMS（MockBukkit / 非 Paper）是正常情况，不用 severe。
            logUnavailable(logger, "Vanilla grindstone unavailable: " + failure);
            return Resolution.UNAVAILABLE;
        }
    }

    /**
     * 找 CraftItemStack 上的桥接方法：静态、单参数、参数可接受 from 类型、返回类型可赋给 to 类型。
     * 用可赋值性而非精确相等匹配——{@code asCraftMirror} 的返回类型是 {@code CraftItemStack}
     * （ItemStack 的子类），精确相等会漏匹配。
     */
    private static MethodHandle findConverter(Class<?> craftItemStack, String name,
                                              Class<?> from, Class<?> to) {
        for (Method method : craftItemStack.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers()) || !method.getName().equals(name)) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1 || !params[0].isAssignableFrom(from)) {
                continue;
            }
            if (!to.isAssignableFrom(method.getReturnType())) {
                continue;
            }
            return unreflect(method);
        }
        return null;
    }

    /**
     * 按形状找祛魔方法：静态、返回 NMS ItemStack、恰好两个参数且第二参数为 boolean（keepCurses）。
     * 不校验方法名——映射改名时仍能工作；形状不符则判定不可用，交回手写实现。
     */
    private static MethodHandle findRemoveEnchantments(Class<?> recipe, Class<?> nmsItemStack) {
        for (Method method : recipe.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            if (!method.getReturnType().equals(nmsItemStack)) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 2 || !params[0].equals(nmsItemStack)
                    || !params[1].equals(boolean.class)) {
                continue;
            }
            method.setAccessible(true);
            return unreflect(method);
        }
        return null;
    }

    private static MethodHandle unreflect(Method method) {
        try {
            return LOOKUP.unreflect(method);
        } catch (IllegalAccessException failure) {
            return null;
        }
    }

    private static void logUnavailable(Logger logger, String message) {
        logger.warning(message + " — disenchanting will use the built-in implementation");
    }

    private record Strategy(MethodHandle toNms, MethodHandle toBukkit, MethodHandle removeEnchantments) {
    }
}
