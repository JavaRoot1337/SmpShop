package ru.javaroot.api.event;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import ru.javaroot.api.ShopSnapshot;

import java.util.Objects;
import java.util.UUID;

public class ShopRemovePrepareEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID operationId;
    private final ShopSnapshot shop;
    private boolean cancelled;

    public ShopRemovePrepareEvent(UUID operationId, ShopSnapshot shop) {
        this.operationId = Objects.requireNonNull(operationId, "идентификатор операции");
        this.shop = Objects.requireNonNull(shop, "магазин");
    }

    public UUID operationId() {
        return operationId;
    }

    public ShopSnapshot shop() {
        return shop;
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
