package ru.javaroot.currency;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BalanceTest {
    @Test
    void calculatesTotal() {
        assertEquals(14, new Balance(6, 8).total());
    }

    @Test
    void rejectsNegativeAmount() {
        assertThrows(IllegalArgumentException.class, () -> new Balance(-1, 0));
    }
}
