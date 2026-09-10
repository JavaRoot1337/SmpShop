package ru.javaroot.api.event;

import org.bukkit.NamespacedKey;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import ru.javaroot.api.CurrencyAmounts;
import ru.javaroot.api.ShopSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class ShopPurchasePrepareEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID transactionId;
    private final UUID buyerId;
    private final ShopSnapshot shop;
    private final CurrencyAmounts basePayment;
    private final List<PurchaseAdjustment> adjustments = new ArrayList<>();
    private boolean cancelled;

    public ShopPurchasePrepareEvent(UUID transactionId, UUID buyerId, ShopSnapshot shop,
                                    CurrencyAmounts basePayment) {
        this.transactionId = Objects.requireNonNull(transactionId, "идентификатор транзакции");
        this.buyerId = Objects.requireNonNull(buyerId, "идентификатор покупателя");
        this.shop = Objects.requireNonNull(shop, "магазин");
        this.basePayment = Objects.requireNonNull(basePayment, "базовый платёж");
    }

    public UUID transactionId() {
        return transactionId;
    }

    public UUID buyerId() {
        return buyerId;
    }

    public ShopSnapshot shop() {
        return shop;
    }

    public CurrencyAmounts basePayment() {
        return basePayment;
    }

    public List<PurchaseAdjustment> adjustments() {
        return List.copyOf(adjustments);
    }

    public boolean addAdjustment(NamespacedKey key, AdjustmentType type, CurrencyAmounts amounts) {
        Objects.requireNonNull(key, "ключ корректировки");
        if (adjustments.stream().anyMatch(adjustment -> adjustment.key().equals(key))) {
            return false;
        }
        adjustments.add(new PurchaseAdjustment(key, type, amounts));
        return true;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
