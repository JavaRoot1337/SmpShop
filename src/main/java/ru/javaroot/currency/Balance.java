package ru.javaroot.currency;

public record Balance(long diamondOre, long deepslateDiamondOre) {
    public Balance {
        if (diamondOre < 0 || deepslateDiamondOre < 0) {
            throw new IllegalArgumentException("Баланс не может быть отрицательным");
        }
    }

    public long total() {
        return Math.addExact(diamondOre, deepslateDiamondOre);
    }
}
