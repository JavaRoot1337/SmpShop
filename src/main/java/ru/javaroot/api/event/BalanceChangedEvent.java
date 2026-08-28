package ru.javaroot.api.event;

import org.bukkit.NamespacedKey;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import ru.javaroot.api.BalanceSnapshot;

import java.util.Objects;
import java.util.UUID;

public class BalanceChangedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID operationId;
    private final NamespacedKey source;
    private final BalanceSnapshot balance;

    public BalanceChangedEvent(UUID operationId, NamespacedKey source, BalanceSnapshot balance) {
        super(true);
        this.operationId = Objects.requireNonNull(operationId, "operationId");
        this.source = Objects.requireNonNull(source, "source");
        this.balance = Objects.requireNonNull(balance, "balance");
    }

    public UUID operationId() {
        return operationId;
    }

    public NamespacedKey source() {
        return source;
    }

    public BalanceSnapshot balance() {
        return balance;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
