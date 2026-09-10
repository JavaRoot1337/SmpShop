package ru.javaroot.consumer;

import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import ru.javaroot.api.BalanceChange;
import ru.javaroot.api.CurrencyAmounts;
import ru.javaroot.api.CurrencyType;
import ru.javaroot.api.ShopCreate;
import ru.javaroot.api.ShopPosition;
import ru.javaroot.api.ShopRemove;
import ru.javaroot.api.SmpShopApi;
import ru.javaroot.api.event.AdjustmentType;
import ru.javaroot.api.event.ShopPurchaseCompletedEvent;
import ru.javaroot.api.event.ShopPurchasePrepareEvent;

import java.util.UUID;

public class ApiConsumer extends JavaPlugin implements Listener {
    private SmpShopApi api;
    private NamespacedKey source;

    @Override
    public void onEnable() {
        api = getServer().getServicesManager().load(SmpShopApi.class);
        if (api == null) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        source = new NamespacedKey(this, "consumer");
        getServer().getPluginManager().registerEvents(this, this);
    }

    public void addDiamondOre(UUID playerId, long amount) {
        api.changeBalance(new BalanceChange(UUID.randomUUID(), source, playerId,
                CurrencyType.DIAMOND_ORE, amount));
    }

    public void createShop(UUID ownerId, String ownerName, ShopPosition position, int price) {
        api.createShop(new ShopCreate(UUID.randomUUID(), source, ownerId, ownerName,
                position, null, price));
    }

    public void removeShop(long shopId) {
        api.removeShop(new ShopRemove(UUID.randomUUID(), source, shopId));
    }

    @EventHandler
    public void onPurchasePrepare(ShopPurchasePrepareEvent event) {
        event.addAdjustment(new NamespacedKey(this, "bank-fee"), AdjustmentType.SELLER_DEDUCTION,
                new CurrencyAmounts(1, 0));
    }

    @EventHandler
    public void onPurchaseCompleted(ShopPurchaseCompletedEvent event) {
        getLogger().info("Транзакция SmpShop завершена: " + event.transactionId());
    }
}
