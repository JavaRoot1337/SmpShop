package ru.javaroot.smpshop.shop;

public record Bal(long diamondOre, long deepslateDiamondOre) {
    public Bal {
        if (diamondOre < 0 || deepslateDiamondOre < 0) {
            throw new IllegalArgumentException("Баланс не может быть отрицательным");
        }
    }

    public long total() {
        return Math.addExact(diamondOre, deepslateDiamondOre);
    }
}
