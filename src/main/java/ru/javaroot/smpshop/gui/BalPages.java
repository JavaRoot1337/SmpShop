package ru.javaroot.smpshop.gui;

import org.bukkit.inventory.ItemStack;
import ru.javaroot.smpshop.shop.Bal;
import ru.javaroot.smpshop.shop.CurType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

final class BalPages {
    static final int SLOTS = 45;
    private static final int MAX_STACK = 64;

    private BalPages() {
    }

    static EnumMap<CurType, Long> changedBalance(Bal original, Map<Long, ItemStack[]> pages) {
        EnumMap<CurType, Long> cur = new EnumMap<>(CurType.class);
        for (CurType type : CurType.values()) {
            cur.put(type, amount(original, type));
        }
        for (Map.Entry<Long, ItemStack[]> entry : pages.entrySet()) {
            ItemStack[] origPage = itemPage(original, entry.getKey());
            EnumMap<CurType, Long> before = count(Collections.singletonList(origPage));
            EnumMap<CurType, Long> after = count(Collections.singletonList(entry.getValue()));
            for (CurType type : CurType.values()) {
                long val = Math.subtractExact(cur.get(type), before.getOrDefault(type, 0L));
                cur.put(type, Math.addExact(val, after.getOrDefault(type, 0L)));
            }
        }
        return cur;
    }

    static List<ItemStack> invalidItems(Iterable<ItemStack[]> pages) {
        List<ItemStack> invalid = new ArrayList<>();
        for (ItemStack[] page : pages) {
            for (ItemStack item : page) {
                if (item != null && !item.getType().isAir() && CurType.from(item.getType()) == null) {
                    invalid.add(item.clone());
                }
            }
        }
        return invalid;
    }

    static boolean full(ItemStack[] page) {
        for (ItemStack item : page) {
            if (item == null || item.getType().isAir()) {
                return false;
            }
        }
        return true;
    }

    static ItemStack[] itemPage(Bal bal, long page) {
        ItemStack[] items = new ItemStack[SLOTS];
        long firstStack = Math.multiplyExact(page, SLOTS);
        long diamondStacks = stackCount(bal.diamondOre());
        long deepslateStacks = stackCount(bal.deepslateDiamondOre());
        for (int slot = 0; slot < SLOTS; slot++) {
            long stack = firstStack + slot;
            if (stack < diamondStacks) {
                items[slot] = stack(bal.diamondOre(), stack, CurType.DIAMOND_ORE);
            } else if (stack - diamondStacks < deepslateStacks) {
                items[slot] = stack(bal.deepslateDiamondOre(), stack - diamondStacks, CurType.DEEPSLATE_DIAMOND_ORE);
            }
        }
        return items;
    }

    static long pageCount(Bal bal) {
        long d = stackCount(bal.diamondOre());
        long ds = stackCount(bal.deepslateDiamondOre());
        long stacks = d > Long.MAX_VALUE - ds ? Long.MAX_VALUE : d + ds;
        return stacks == 0 ? 1 : (stacks - 1) / SLOTS + 1;
    }

    private static EnumMap<CurType, Long> count(Iterable<ItemStack[]> pages) {
        EnumMap<CurType, Long> amounts = new EnumMap<>(CurType.class);
        for (ItemStack[] page : pages) {
            for (ItemStack item : page) {
                CurType cur = item == null ? null : CurType.from(item.getType());
                if (cur != null) {
                    amounts.merge(cur, (long) item.getAmount(), Math::addExact);
                }
            }
        }
        return amounts;
    }

    private static ItemStack stack(long amount, long index, CurType cur) {
        long rem = amount - index * MAX_STACK;
        return new ItemStack(cur.mat(), (int) Math.min(MAX_STACK, rem));
    }

    private static long amount(Bal bal, CurType cur) {
        return cur == CurType.DIAMOND_ORE ? bal.diamondOre() : bal.deepslateDiamondOre();
    }

    private static long stackCount(long amount) {
        return amount == 0 ? 0 : (amount - 1) / MAX_STACK + 1;
    }
}
