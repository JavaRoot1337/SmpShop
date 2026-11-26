package ru.javaroot.smpshop.api;

import org.bukkit.Bukkit;
import ru.javaroot.api.ApiStatus;
import ru.javaroot.api.BalanceChange;
import ru.javaroot.api.BalanceResult;
import ru.javaroot.api.BalanceSnapshot;
import ru.javaroot.api.BalanceTransfer;
import ru.javaroot.api.CurrencyType;
import ru.javaroot.api.event.BalanceChangedEvent;
import ru.javaroot.smpshop.db.BalRepo;
import ru.javaroot.smpshop.gui.BalGui;
import ru.javaroot.smpshop.plat.Exec;
import ru.javaroot.smpshop.shop.CurType;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

final class ApiBalSvc {
    private final BalRepo balances;
    private final BalGui balGui;
    private final ApiMapper mapper;
    private final Exec exec;
    private final Supplier<ApiStatus> unavailable;

    ApiBalSvc(BalRepo balances, BalGui balGui, ApiMapper mapper, Exec exec, Supplier<ApiStatus> unavailable) {
        this.balances = balances;
        this.balGui = balGui;
        this.mapper = mapper;
        this.exec = exec;
        this.unavailable = unavailable;
    }

    CompletionStage<BalanceSnapshot> balance(UUID playerId) {
        if (unavailable.get() != null) {
            return CompletableFuture.failedFuture(new IllegalStateException("API SmpShop ещё не готово к работе"));
        }
        return balances.load(playerId).thenApply(b -> mapper.balance(playerId, b));
    }

    CompletionStage<BalanceResult> changeBalance(BalanceChange req) {
        ApiStatus st = unavailable.get();
        if (st != null) {
            return CompletableFuture.completedFuture(new BalanceResult(st, null, null));
        }
        if (balGui.isOpen(req.playerId())) {
            return CompletableFuture.completedFuture(new BalanceResult(ApiStatus.BUSY, null, null));
        }
        return balances.change(req.operationId(), req.source().asString(), req.playerId(),
                        currency(req.currency()), req.amount())
                .thenCompose(res -> {
                    BalanceSnapshot src = res.source() == null ? null : mapper.balance(req.playerId(), res.source());
                    ApiStatus apiSt = ApiStatus.valueOf(res.status().name());
                    BalanceResult resp = new BalanceResult(apiSt, src, null);
                    if (apiSt != ApiStatus.SUCCESS || !res.applied()) {
                        return CompletableFuture.completedFuture(resp);
                    }
                    return scheduleEvents(List.of(new BalanceChangedEvent(req.operationId(), req.source(), src)), resp);
                });
    }

    CompletionStage<BalanceResult> transferBalance(BalanceTransfer req) {
        ApiStatus st = unavailable.get();
        if (st != null) {
            return CompletableFuture.completedFuture(new BalanceResult(st, null, null));
        }
        if (balGui.isOpen(req.fromPlayerId()) || balGui.isOpen(req.toPlayerId())) {
            return CompletableFuture.completedFuture(new BalanceResult(ApiStatus.BUSY, null, null));
        }
        return balances.transfer(req.operationId(), req.source().asString(), req.fromPlayerId(),
                        req.toPlayerId(), currency(req.currency()), req.amount())
                .thenCompose(res -> {
                    BalanceSnapshot src = res.source() == null ? null : mapper.balance(req.fromPlayerId(), res.source());
                    BalanceSnapshot dst = res.target() == null ? null : mapper.balance(req.toPlayerId(), res.target());
                    ApiStatus apiSt = ApiStatus.valueOf(res.status().name());
                    BalanceResult resp = new BalanceResult(apiSt, src, dst);
                    if (apiSt != ApiStatus.SUCCESS || !res.applied()) {
                        return CompletableFuture.completedFuture(resp);
                    }
                    return scheduleEvents(List.of(
                            new BalanceChangedEvent(req.operationId(), req.source(), src),
                            new BalanceChangedEvent(req.operationId(), req.source(), dst)), resp);
                });
    }

    private CompletableFuture<BalanceResult> scheduleEvents(List<BalanceChangedEvent> events, BalanceResult res) {
        CompletableFuture<BalanceResult> fut = new CompletableFuture<>();
        if (exec.global(() -> {
            events.forEach(Bukkit.getPluginManager()::callEvent);
            fut.complete(res);
        }) != Exec.Result.SCHEDULED) {
            fut.completeExceptionally(new IllegalStateException("API SmpShop завершает работу"));
        }
        return fut;
    }

    private static CurType currency(CurrencyType cur) {
        return cur == CurrencyType.DIAMOND_ORE ? CurType.DIAMOND_ORE : CurType.DEEPSLATE_DIAMOND_ORE;
    }
}
