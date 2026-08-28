package ru.javaroot.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import ru.javaroot.api.ShopSnapshot;

import java.util.Objects;
import java.util.UUID;

public class ShopChangedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID operationId;
    private final ChangeType type;
    private final ShopSnapshot shop;

    public ShopChangedEvent(UUID operationId, ChangeType type, ShopSnapshot shop) {
        super(true);
        this.operationId = Objects.requireNonNull(operationId, "operationId");
        this.type = Objects.requireNonNull(type, "type");
        this.shop = Objects.requireNonNull(shop, "shop");
    }

    public UUID operationId() {
        return operationId;
    }

    public ChangeType type() {
        return type;
    }

    public ShopSnapshot shop() {
        return shop;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    public enum ChangeType {
        CREATED,
        PRICE_CHANGED,
        REMOVED
    }
}
