package ru.javaroot.gui;

import org.junit.jupiter.api.Test;
import ru.javaroot.currency.Balance;
import ru.javaroot.currency.Currency;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BalanceInvTest {
    @Test
    void packsOreInto64StacksAnd45SlotPages() {
        List<BalanceInv.OreStack[]> pages = BalanceInv.pack(new Balance(64L * 45 + 1, 3));

        assertEquals(2, pages.size());
        assertEquals(45, pages.get(0).length);
        assertEquals(45, count(pages.get(0)));
        assertEquals(2, count(pages.get(1)));
        assertEquals(64, pages.get(0)[0].amount());
        assertEquals(1, pages.get(1)[0].amount());
        assertEquals(Currency.DEEPSLATE_DIAMOND_ORE, pages.get(1)[1].currency());
        for (BalanceInv.OreStack[] page : pages) {
            for (BalanceInv.OreStack stack : page) {
                if (stack != null) {
                    assertNotNull(Currency.from(stack.currency().material()));
                    assertTrue(stack.amount() > 0 && stack.amount() <= 64);
                }
            }
        }
    }

    @Test
    void leavesOneEmptyPageForZeroBalance() {
        List<BalanceInv.OreStack[]> pages = BalanceInv.pack(new Balance(0, 0));

        assertEquals(1, pages.size());
        assertEquals(45, pages.get(0).length);
        assertTrue(java.util.Arrays.stream(pages.get(0)).allMatch(stack -> stack == null));
    }

    @Test
    void currencyMappingRejectsDiamondsAndIngots() {
        assertNotNull(Currency.from(Currency.DIAMOND_ORE.material()));
        assertNotNull(Currency.from(Currency.DEEPSLATE_DIAMOND_ORE.material()));
        assertNull(Currency.from(org.bukkit.Material.DIAMOND));
        assertNull(Currency.from(org.bukkit.Material.DIAMOND_BLOCK));
        assertNull(Currency.from(org.bukkit.Material.DIAMOND_SWORD));
    }

    private int count(BalanceInv.OreStack[] page) {
        return (int) java.util.Arrays.stream(page).filter(stack -> stack != null).count();
    }
}
