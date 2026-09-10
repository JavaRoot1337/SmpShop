package ru.javaroot.api;

public record CurrencyAmounts(long diamondOre, long deepslateDiamondOre) {
    public CurrencyAmounts {
        if (diamondOre < 0 || deepslateDiamondOre < 0) {
            throw new IllegalArgumentException("Суммы валют не могут быть отрицательными");
        }
    }

    public long amount(CurrencyType currency) {
        return currency == CurrencyType.DIAMOND_ORE ? diamondOre : deepslateDiamondOre;
    }
}
