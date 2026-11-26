package ru.javaroot.api;

/**
 * Точка доступа к {@link SmpShopApi}.
 */
public final class ApiProvider {
    private static volatile SmpShopApi instance;

    private ApiProvider() {
    }

    /**
     * Возвращает активный экземпляр API.
     *
     * @throws IllegalStateException если плагин ещё не включён или уже остановлен
     */
    public static SmpShopApi get() {
        SmpShopApi api = instance;
        if (api == null) {
            throw new IllegalStateException("SmpShop API ещё не зарегистрирован");
        }
        return api;
    }

    /**
     * Проверяет, зарегистрирован ли сервис API.
     */
    public static boolean isAvailable() {
        return instance != null;
    }

    public static void register(SmpShopApi api) {
        if (api == null) {
            throw new IllegalArgumentException("Экземпляр API не может быть null");
        }
        instance = api;
    }

    public static void unregister() {
        instance = null;
    }
}
