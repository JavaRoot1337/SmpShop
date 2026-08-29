package ru.javaroot.api;

import org.bukkit.Bukkit;
import ru.javaroot.api.event.BalanceChangedEvent;
import ru.javaroot.currency.Currency;
import ru.javaroot.data.BalanceStore;
import ru.javaroot.gui.BalanceInv;
import ru.javaroot.scheduler.Sched;

import java.util.UUID;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

class ApiBalanceSvc {
    private final BalanceStore balances;
    private final BalanceInv balanceGui;
    private final ApiSnapshotMapper mapper;
    private final Sched sched;
    private final Supplier<ApiStatus> unavailable;

    ApiBalanceSvc(BalanceStore balances, BalanceInv balanceGui, ApiSnapshotMapper mapper, Sched sched,
                  Supplier<ApiStatus> unavailable) {
        this.balances = balances;
        this.balanceGui = balanceGui;
        this.mapper = mapper;
        this.sched = sched;
        this.unavailable = unavailable;
    }

    CompletionStage<BalanceSnapshot> balance(UUID playerId) {
        if (unavailable.get() != null) {
            return CompletableFuture.failedFuture(new IllegalStateException("SmpShop API is not ready"));
        }
        return balances.load(playerId).thenApply(balance -> mapper.balance(playerId, balance));
    }

    CompletionStage<BalanceResult> changeBalance(BalanceChange request) {
        ApiStatus unavailableStatus = unavailable.get();
        if (unavailableStatus != null) {
            return CompletableFuture.completedFuture(new BalanceResult(unavailableStatus, null, null));
        }
        if (balanceGui.isOpen(request.playerId())) {
            return CompletableFuture.completedFuture(new BalanceResult(ApiStatus.BUSY, null, null));
        }
        Currency currency = currency(request.currency());
        return balances.change(request.operationId(), request.source().asString(), request.playerId(),
                        currency, request.amount())
                .thenCompose(result -> {
                    BalanceSnapshot source = result.source() == null ? null
                            : mapper.balance(request.playerId(), result.source());
                    ApiStatus status = status(result.status());
                    BalanceResult response = new BalanceResult(status, source, null);
                    if (status != ApiStatus.SUCCESS || !result.applied()) {
                        return CompletableFuture.completedFuture(response);
                    }
                    return scheduleEvent(new BalanceChangedEvent(request.operationId(), request.source(), source),
                            response);
                });
    }

    CompletionStage<BalanceResult> transferBalance(BalanceTransfer request) {
        ApiStatus unavailableStatus = unavailable.get();
        if (unavailableStatus != null) {
            return CompletableFuture.completedFuture(new BalanceResult(unavailableStatus, null, null));
        }
        if (balanceGui.isOpen(request.fromPlayerId()) || balanceGui.isOpen(request.toPlayerId())) {
            return CompletableFuture.completedFuture(new BalanceResult(ApiStatus.BUSY, null, null));
        }
        return balances.transfer(request.operationId(), request.source().asString(), request.fromPlayerId(),
                        request.toPlayerId(), currency(request.currency()), request.amount())
                .thenCompose(result -> {
                    BalanceSnapshot source = result.source() == null ? null
                            : mapper.balance(request.fromPlayerId(), result.source());
                    BalanceSnapshot target = result.target() == null ? null
                            : mapper.balance(request.toPlayerId(), result.target());
                    ApiStatus status = status(result.status());
                    BalanceResult response = new BalanceResult(status, source, target);
                    if (status != ApiStatus.SUCCESS || !result.applied()) {
                        return CompletableFuture.completedFuture(response);
                    }
                    return scheduleEvents(List.of(
                            new BalanceChangedEvent(request.operationId(), request.source(), source),
                            new BalanceChangedEvent(request.operationId(), request.source(), target)), response);
                });
    }

    private CompletableFuture<BalanceResult> scheduleEvent(BalanceChangedEvent event, BalanceResult result) {
        return scheduleEvents(List.of(event), result);
    }

    private CompletableFuture<BalanceResult> scheduleEvents(List<BalanceChangedEvent> events, BalanceResult result) {
        CompletableFuture<BalanceResult> scheduled = new CompletableFuture<>();
        if (sched.global(() -> {
            events.forEach(Bukkit.getPluginManager()::callEvent);
            scheduled.complete(result);
        }) != Sched.ScheduleResult.SCHEDULED) {
            scheduled.completeExceptionally(new IllegalStateException("SmpShop API is shutting down"));
        }
        return scheduled;
    }

    private Currency currency(CurrencyType currency) {
        return currency == CurrencyType.DIAMOND_ORE ? Currency.DIAMOND_ORE : Currency.DEEPSLATE_DIAMOND_ORE;
    }

    private ApiStatus status(BalanceStore.MutationStatus status) {
        return ApiStatus.valueOf(status.name());
    }
}
