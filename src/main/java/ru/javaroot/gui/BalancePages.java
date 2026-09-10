package ru.javaroot.gui;

import org.bukkit.inventory.ItemStack;
import ru.javaroot.currency.Balance;
import ru.javaroot.currency.Currency;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

class BalancePages {
    static final int BALANCE_SLOTS = 45;
    private static final int MAX_STACK_SIZE = 64;

    static List<BalanceInv.OreStack[]> pack(Balance balance) {
        List<BalanceInv.OreStack[]> pages = new ArrayList<>();
        BalanceInv.OreStack[] page = new BalanceInv.OreStack[BALANCE_SLOTS];
        int slot = 0;
        for (Currency currency : Currency.values()) {
            long remaining = amount(balance, currency);
            while (remaining > 0) {
                if (slot == BALANCE_SLOTS) {
                    pages.add(page);
                    page = new BalanceInv.OreStack[BALANCE_SLOTS];
                    slot = 0;
                }
                int stackAmount = (int) Math.min(MAX_STACK_SIZE, remaining);
                page[slot++] = new BalanceInv.OreStack(currency, stackAmount);
                remaining -= stackAmount;
            }
        }
        pages.add(page);
        return pages;
    }

    static EnumMap<Currency, Long> changedBalance(Balance original, Map<Long, ItemStack[]> pages) {
        EnumMap<Currency, Long> current = new EnumMap<>(Currency.class);
        for (Currency currency : Currency.values()) {
            current.put(currency, amount(original, currency));
        }
        for (Map.Entry<Long, ItemStack[]> entry : pages.entrySet()) {
            ItemStack[] originalPage = itemPage(original, entry.getKey());
            EnumMap<Currency, Long> before = count(Collections.singletonList(originalPage));
            EnumMap<Currency, Long> after = count(Collections.singletonList(entry.getValue()));
            for (Currency currency : Currency.values()) {
                long value = Math.subtractExact(current.get(currency), before.getOrDefault(currency, 0L));
                current.put(currency, Math.addExact(value, after.getOrDefault(currency, 0L)));
            }
        }
        return current;
    }

    static List<ItemStack> invalidItems(Iterable<ItemStack[]> pages) {
        List<ItemStack> invalid = new ArrayList<>();
        for (ItemStack[] page : pages) {
            for (ItemStack item : page) {
                if (item != null && !item.getType().isAir() && !isCurrency(item)) {
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

    static ItemStack[] itemPage(Balance balance, long page) {
        ItemStack[] items = new ItemStack[BALANCE_SLOTS];
        long firstStack = Math.multiplyExact(page, BALANCE_SLOTS);
        long diamondStacks = stackCount(balance.diamondOre());
        long deepslateStacks = stackCount(balance.deepslateDiamondOre());
        for (int slot = 0; slot < BALANCE_SLOTS; slot++) {
            long stack = firstStack + slot;
            if (stack < diamondStacks) {
                items[slot] = stack(balance.diamondOre(), stack, Currency.DIAMOND_ORE);
            } else if (stack - diamondStacks < deepslateStacks) {
                items[slot] = stack(balance.deepslateDiamondOre(), stack - diamondStacks,
                        Currency.DEEPSLATE_DIAMOND_ORE);
            }
        }
        return items;
    }

    static long pageCount(Balance balance) {
        long diamondStacks = stackCount(balance.diamondOre());
        long deepslateStacks = stackCount(balance.deepslateDiamondOre());
        long stacks = diamondStacks > Long.MAX_VALUE - deepslateStacks
                ? Long.MAX_VALUE
                : diamondStacks + deepslateStacks;
        return stacks == 0 ? 1 : (stacks - 1) / BALANCE_SLOTS + 1;
    }

    private static EnumMap<Currency, Long> count(Iterable<ItemStack[]> pages) {
        EnumMap<Currency, Long> amounts = new EnumMap<>(Currency.class);
        for (ItemStack[] page : pages) {
            for (ItemStack item : page) {
                Currency currency = item == null ? null : Currency.from(item.getType());
                if (currency != null) {
                    amounts.merge(currency, (long) item.getAmount(), Math::addExact);
                }
            }
        }
        return amounts;
    }

    private static ItemStack stack(long amount, long index, Currency currency) {
        long remaining = amount - index * MAX_STACK_SIZE;
        return new ItemStack(currency.material(), (int) Math.min(MAX_STACK_SIZE, remaining));
    }

    private static long amount(Balance balance, Currency currency) {
        return currency == Currency.DIAMOND_ORE ? balance.diamondOre() : balance.deepslateDiamondOre();
    }

    private static long stackCount(long amount) {
        return amount == 0 ? 0 : (amount - 1) / MAX_STACK_SIZE + 1;
    }

    private static boolean isCurrency(ItemStack item) {
        return item != null && Currency.from(item.getType()) != null;
    }
}
