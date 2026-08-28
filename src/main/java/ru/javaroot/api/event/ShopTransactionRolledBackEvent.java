package ru.javaroot.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Objects;
import java.util.UUID;

public class ShopTransactionRolledBackEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID transactionId;
    private final long shopId;
    private final UUID buyerId;

    public ShopTransactionRolledBackEvent(UUID transactionId, long shopId, UUID buyerId) {
        super(true);
        this.transactionId = Objects.requireNonNull(transactionId, "transactionId");
        this.shopId = shopId;
        this.buyerId = Objects.requireNonNull(buyerId, "buyerId");
    }

    public UUID transactionId() {
        return transactionId;
    }

    public long shopId() {
        return shopId;
    }

    public UUID buyerId() {
        return buyerId;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
