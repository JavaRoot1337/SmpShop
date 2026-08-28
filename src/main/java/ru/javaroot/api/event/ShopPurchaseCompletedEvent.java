package ru.javaroot.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import ru.javaroot.api.CurrencyAmounts;
import ru.javaroot.api.ShopSnapshot;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class ShopPurchaseCompletedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID transactionId;
    private final UUID buyerId;
    private final ShopSnapshot shop;
    private final long originalPrice;
    private final CurrencyAmounts buyerPayment;
    private final CurrencyAmounts sellerPayout;
    private final List<PurchaseAdjustment> adjustments;

    public ShopPurchaseCompletedEvent(UUID transactionId, UUID buyerId, ShopSnapshot shop,
                                      long originalPrice, CurrencyAmounts buyerPayment,
                                      CurrencyAmounts sellerPayout,
                                      List<PurchaseAdjustment> adjustments) {
        super(true);
        this.transactionId = Objects.requireNonNull(transactionId, "transactionId");
        this.buyerId = Objects.requireNonNull(buyerId, "buyerId");
        this.shop = Objects.requireNonNull(shop, "shop");
        this.originalPrice = originalPrice;
        this.buyerPayment = Objects.requireNonNull(buyerPayment, "buyerPayment");
        this.sellerPayout = Objects.requireNonNull(sellerPayout, "sellerPayout");
        this.adjustments = List.copyOf(adjustments);
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

    public long originalPrice() {
        return originalPrice;
    }

    public CurrencyAmounts buyerPayment() {
        return buyerPayment;
    }

    public CurrencyAmounts sellerPayout() {
        return sellerPayout;
    }

    public List<PurchaseAdjustment> adjustments() {
        return adjustments;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
