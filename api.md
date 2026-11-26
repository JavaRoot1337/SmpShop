# SmpShop API v1

Public contract in `ru.javaroot.api` and `ru.javaroot.api.event`, bundled inside `SmpShop-2.0.jar`.

## Entry points

```java
SmpShopApi api = Bukkit.getServicesManager().load(SmpShopApi.class);
// or
SmpShopApi api = ApiProvider.get(); // check ApiProvider.isAvailable() first
```

Check `api.version() == SmpShopApi.VERSION` and wait for DB startup if needed via `api.awaitReady()`.

## Dependency setup

`paper-plugin.yml`:

```yaml
dependencies:
  server:
    SmpShop:
      load: BEFORE
      required: true
      join-classpath: true
```

`build.gradle.kts`:

```kotlin
dependencies {
    compileOnly(files("libs/SmpShop-2.0.jar"))
}
```

## State & lifecycle

`api.state()` returns `STARTING`, `READY`, `FAILED`, `SHUTTING_DOWN`, or `STOPPED`.

Mutations return `CompletionStage` with `ApiStatus`: `SUCCESS`, `NOT_READY`, `NOT_FOUND`, `BUSY`, `LIMIT_REACHED`, `INSUFFICIENT_FUNDS`, `CONFLICT`, `INVALID_INPUT`, `SHUTTING_DOWN`.

## Operations

- **Read shops:** `shop(long id)`, `shop(ShopPosition pos)`, `shops(UUID ownerId)` -> immutable `ShopSnapshot`
- **Balances:** `balance(UUID)`, `changeBalance(BalanceChange)`, `transferBalance(BalanceTransfer)`
- **Shops:** `createShop(ShopCreate)`, `changePrice(ShopPriceChange)`, `removeShop(ShopRemove)`
- **Menus:** `openBalance(UUID)`, `openShopList(UUID viewerId, UUID ownerId)`

Every mutating request takes a unique `UUID operationId` and `NamespacedKey source` for idempotent retries; reusing the same `operationId` with different parameters returns `CONFLICT`.

## Events (`ru.javaroot.api.event`)

| Event | Type | Purpose |
| --- | --- | --- |
| `ShopCreatePrepareEvent` | `Cancellable` | Validate or cancel API shop creation |
| `ShopRemovePrepareEvent` | `Cancellable` | Validate or cancel API shop removal |
| `ShopPurchasePrepareEvent` | `Cancellable` | Cancel purchase or add `PurchaseAdjustment` (`SELLER_DEDUCTION`, `BUYER_SURCHARGE`) |
| `ShopPurchaseCompletedEvent` | Async | Post-sale notification after SQLite commit |
| `ShopTransactionRolledBackEvent` | Async | Compensation hook after purchase rollback |
| `BalanceChangedEvent` | Async | Post-change balance snapshot notification |
| `ShopChangedEvent` | Async | `CREATED`, `PRICE_CHANGED`, `REMOVED` notification |

## Threading contract

- API methods are safe to call from any thread on Paper and Folia
- `CompletionStage` completion thread is unspecified; dispatch Bukkit/player calls via Paper/Folia schedulers

[Русская версия API](apiru.md)
