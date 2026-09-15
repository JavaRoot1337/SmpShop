# SmpShop API

Guide to integrating a third-party plugin with the SmpShop API v1.

The API is included in the main SmpShop JAR. No separate API plugin is required. The service is available through Bukkit's `ServicesManager`.

## Available through the API

- read shops by ID, coordinates, and owner UUID;
- read a player's balance;
- add and deduct ore;
- transfer ore between two players;
- create shops, change prices, and remove shops;
- open the balance and shop list menus;
- receive balance, shop, and purchase events;
- add purchase fees or cancel a purchase before payment is deducted.

The API uses two currency types:

- `CurrencyType.DIAMOND_ORE`;
- `CurrencyType.DEEPSLATE_DIAMOND_ORE`.

## Consumer plugin setup

Add SmpShop as a required dependency in `plugin.yml`:

```yaml
name: Plugin
main: primer.Plugin
version: 1.0.0
api-version: '1.21.11'
depend: [SmpShop]
```

In the consumer project, add the API from the SmpShop JAR as a compile-only dependency:

```kotlin
dependencies {
    compileOnly(files("libs/SmpShop-1.0.jar"))
}
```

The SmpShop plugin must be in the `plugins` folder alongside your plugin. Do not bundle SmpShop inside your JAR or install the test `SmpShopApiConsumer`: it is only an API consumer example.

## Getting the service

Get the service in `onEnable`:

```java
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;
import ru.javaroot.api.SmpShopApi;

public class Plugin extends JavaPlugin {
    private SmpShopApi shopApi;
    private NamespacedKey source;

    @Override
    public void onEnable() {
        shopApi = getServer().getServicesManager().load(SmpShopApi.class);
        if (shopApi == null) {
            getLogger().severe("SmpShop API is unavailable");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        source = new NamespacedKey(this, "plugin");
    }
}
```

Check the version before using the API:

```java
if (shopApi.version() != SmpShopApi.VERSION) {
    getLogger().severe("Unsupported SmpShop API version: " + shopApi.version());
    getServer().getPluginManager().disablePlugin(this);
    return;
}
```

The service is registered before SQLite finishes loading. Therefore, the API may not be ready for operations immediately after you obtain the service.

## State and readiness

The state is available through `state()`:

| State | Meaning |
| --- | --- |
| `STARTING` | the plugin is loading the database and shops |
| `READY` | the API accepts operations |
| `FAILED` | database startup or data loading failed |
| `SHUTTING_DOWN` | the plugin is shutting down |
| `STOPPED` | the plugin has stopped |

Use `awaitReady()` to wait:

```java
shopApi.awaitReady().thenRun(() -> {
    // API is ready
    addReward(playerId, 5);
}).exceptionally(error -> {
    getLogger().warning("SmpShop API did not become ready: " + error.getMessage());
    return null;
});
```

Do not call the Bukkit API from this callback without returning to the appropriate server scheduler. The `CompletionStage` completion thread is not part of the API contract.

If you call a mutation before the API is ready, it returns `ApiStatus.NOT_READY`. In this case, `balance(UUID)` completes with a `CompletionStage` exception.

## General operation rules

All mutating requests contain:

- `UUID operationId` - a unique operation identifier;
- `NamespacedKey source` - the operation source, usually your plugin's namespace and key.

Create a new `operationId` for every new operation:

```java
NamespacedKey source = new NamespacedKey(this, "daily_reward");
UUID operationId = UUID.randomUUID();
```

Repeating the same request with the same `operationId` and `source` does not apply the change a second time. This lets you safely retry a request after a network or server error.

Do not use one `operationId` for different actions. If you pass different parameters for an operation that is already recorded, the API returns `CONFLICT`.

The API does not check player permissions. If an operation is started by another plugin's command or GUI, check permissions yourself.

## Reading shops

### Lookup by ID

```java
Optional<ShopSnapshot> shop = shopApi.shop(42L);

shop.ifPresent(value -> {
    getLogger().info("Shop owner: " + value.ownerName());
    getLogger().info("Price: " + value.price());
});
```

### Lookup by coordinates

```java
ShopPosition position = new ShopPosition(world.getUID(), x, y, z);
Optional<ShopSnapshot> shop = shopApi.shop(position);
```

`ShopPosition` contains the world UUID and block coordinates. For a double chest, use the coordinates of either registered half.

### Player shops

```java
List<ShopSnapshot> shops = shopApi.shops(ownerId);
```

The list is returned as an immutable snapshot. It does not open the chest or teleport the player.

### ShopSnapshot

`ShopSnapshot` contains:

| Method | Meaning |
| --- | --- |
| `id()` | shop ID |
| `ownerId()` | owner UUID |
| `ownerName()` | owner name at registration time |
| `first()` | the first chest position |
| `second()` | the second double-chest position or `null` |
| `price()` | item stack price |
| `earnedTotal()` | total earnings |
| `soldSlots()` | number of sold item slots |
| `soldItems()` | number of items sold |
| `createdAt()` | creation time in Unix milliseconds |

The snapshot is not updated automatically. Call `shop(id)` or `shops(ownerId)` again to get current data.

## Balance

### Reading the balance

```java
shopApi.balance(playerId).thenAccept(balance -> {
    CurrencyAmounts amounts = balance.amounts();
    getLogger().info("Diamond ore: " + amounts.diamondOre());
    getLogger().info("Deepslate diamond ore: " + amounts.deepslateDiamondOre());
});
```

`balance(UUID)` returns `CompletionStage<BalanceSnapshot>` because the read is performed through SQLite.

### Changing the balance

A positive value adds ore; a negative value deducts it:

```java
shopApi.changeBalance(new BalanceChange(
        UUID.randomUUID(),
        source,
        playerId,
        CurrencyType.DIAMOND_ORE,
        5
)).thenAccept(result -> {
    if (!result.successful()) {
        getLogger().warning("Balance change rejected: " + result.status());
    }
});
```

A zero value is invalid. Deducting more than the current balance returns `INSUFFICIENT_FUNDS`. Balance overflow returns `CONFLICT`.

If the player is editing the balance through the in-game menu at that moment, the operation returns `BUSY`.

### Transfer between players

`transferBalance` atomically transfers a positive amount of one currency:

```java
shopApi.transferBalance(new BalanceTransfer(
        UUID.randomUUID(),
        source,
        fromPlayerId,
        toPlayerId,
        CurrencyType.DEEPSLATE_DIAMOND_ORE,
        3
)).thenAccept(result -> {
    if (result.successful()) {
        BalanceSnapshot from = result.source();
        BalanceSnapshot to = result.target();
    }
});
```

Transfers to yourself and transfers with insufficient funds are rejected.

### BalanceResult

| Field | Meaning |
| --- | --- |
| `status()` | operation result |
| `source()` | the source player's new balance, if available |
| `target()` | the recipient's new balance for the transfer |
| `successful()` | shorthand for checking `status() == SUCCESS` |

## Managing shops

The API works with physical chests in the world. It does not create a virtual shop without a block.

### Creating a shop

```java
ShopPosition chest = new ShopPosition(world.getUID(), x, y, z);

shopApi.createShop(new ShopCreate(
        UUID.randomUUID(),
        source,
        ownerId,
        ownerName,
        chest,
        null,
        12
)).thenAccept(result -> {
    if (result.successful()) {
        ShopSnapshot created = result.shop();
        getLogger().info("Shop created #" + created.id());
    } else {
        getLogger().warning("Failed to create shop: " + result.status());
    }
});
```

Leave `second` as `null` for a single chest. For a double chest, pass both positions in the same world.

Creation checks that:

- the world exists;
- a chest is at the specified coordinates;
- the positions match a single or double chest;
- the price is within the range from `config.yml`;
- the owner's limit has not been exceeded;
- the chest is not occupied by another shop.

API operations use the regular `shop.max-per-player` limit. The `smpshop.plus` permission does not automatically apply to API calls.

### Changing the price

```java
shopApi.changePrice(new ShopPriceChange(
        UUID.randomUUID(),
        source,
        shopId,
        20
)).thenAccept(result -> {
    if (result.successful()) {
        getLogger().info("New price: " + result.shop().price());
    }
});
```

The price must be between `shop.min-price` and `shop.max-price.

### Removing a shop

```java
shopApi.removeShop(new ShopRemove(
        UUID.randomUUID(),
        source,
        shopId
)).thenAccept(result -> {
    if (result.status() == ApiStatus.BUSY) {
        getLogger().warning("The shop is being used by a buyer");
    }
});
```

Removing a shop removes its registration but does not remove items from the physical chest.

The operation returns `BUSY` if the shop is being used by a buyer. Before removal, the API also calls `ShopRemovePrepareEvent`, so another plugin can cancel the operation.

## Opening menus

The API can open SmpShop's standard menus for a player:

```java
shopApi.openBalance(playerId).thenAccept(result -> {
    if (result.status() != ApiStatus.SUCCESS) {
        getLogger().warning("Failed to open balance: " + result.status());
    }
});

shopApi.openShopList(viewerId, ownerId).thenAccept(result -> {
    if (!result.successful()) {
        getLogger().warning("Failed to open shop list: " + result.status());
    }
});
```

Both methods require the player to be online. If the player is not found, `NOT_FOUND` is returned. Opening is performed through the player's entity-owner scheduler.

## Events

All events are in the `ru.javaroot.api.event` package. Register them with a regular Bukkit listener:

```java
public class Plugin extends JavaPlugin implements Listener {
    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
    }

    @EventHandler
    public void onBalanceChanged(BalanceChangedEvent event) {
        getLogger().info("Balance changed for player " + event.balance().playerId());
    }
}
```

### Balance and shop change events

| Event | When it is called |
| --- | --- |
| `BalanceChangedEvent` | after a balance change or transfer succeeds |
| `ShopChangedEvent` | after a shop is created, its price changes, or it is removed |
| `ShopTransactionRolledBackEvent` | after an unfinished purchase is rolled back |

`ShopChangedEvent.type()` accepts:

- `CREATED`;
- `PRICE_CHANGED`;
- `REMOVED`.

`ShopTransactionRolledBackEvent` contains `transactionId()`, `shopId()`, and `buyerId()`.

### Preparing creation and removal

`ShopCreatePrepareEvent` and `ShopRemovePrepareEvent` implement `Cancellable`. Use them to enforce your own rules:

```java
@EventHandler
public void onShopRemove(ShopRemovePrepareEvent event) {
    if (isProtectedShop(event.shop().id())) {
        event.setCancelled(true);
    }
}
```

Cancelling the event returns `CONFLICT`.

### Preparing a purchase

`ShopPurchasePrepareEvent` is called before payment is deducted and the physical chest is changed. The event can be cancelled:

```java
@EventHandler
public void onPurchasePrepare(ShopPurchasePrepareEvent event) {
    if (isBlockedItem(event.shop())) {
        event.setCancelled(true);
    }
}
```

The event provides:

- `transactionId()`;
- `buyerId()`;
- `shop()`;
- `basePayment()`;
- `adjustments()`.

### Fees and surcharges

Add an adjustment through a unique `NamespacedKey`:

```java
@EventHandler
public void onPurchasePrepare(ShopPurchasePrepareEvent event) {
    boolean added = event.addAdjustment(
            new NamespacedKey(this, "bank_fee"),
            AdjustmentType.SELLER_DEDUCTION,
            new CurrencyAmounts(1, 0)
    );

    if (!added) {
        getLogger().warning("The adjustment key is already in use");
    }
}
```

Adjustment types:

| Type | Result |
| --- | --- |
| `SELLER_DEDUCTION` | withholds ore from the seller's payout |
| `BUYER_SURCHARGE` | adds ore to the buyer's payment |

`CurrencyAmounts` cannot contain negative values. A `PurchaseAdjustment` amount cannot be empty. A key can be added only once. If there is nothing to withhold from the seller or the buyer cannot cover the total amount, the purchase is rejected.

### Completing a purchase

`ShopPurchaseCompletedEvent` is called after the successful sale is recorded in the database:

```java
@EventHandler
public void onPurchaseCompleted(ShopPurchaseCompletedEvent event) {
    getLogger().info(
            "Transaction " + event.transactionId()
                    + ", shop " + event.shop().id()
                    + ", original price " + event.originalPrice()
    );

    CurrencyAmounts buyerPayment = event.buyerPayment();
    CurrencyAmounts sellerPayout = event.sellerPayout();
}
```

The event contains the original price, the buyer's actual payment, the seller's payout, and the applied adjustments.

Use `ShopTransactionRolledBackEvent` to handle external actions that must be compensated after a rollback. Do not grant a reward based only on `ShopPurchasePrepareEvent`: the purchase can still be cancelled at that stage.

## Results and statuses

Mutating methods return `BalanceResult`, `ShopResult`, or `OpenResult`. They provide the `successful()` method.

| Status | Meaning |
| --- | --- |
| `SUCCESS` | the operation succeeded |
| `NOT_READY` | the API is still loading or failed to start |
| `NOT_FOUND` | the player, world, chest, or shop was not found |
| `BUSY` | the balance or shop is busy with another operation |
| `LIMIT_REACHED` | the owner's shop limit has been reached |
| `INSUFFICIENT_FUNDS` | insufficient ore |
| `CONFLICT` | the operation conflicts with the current state or was cancelled by an event |
| `INVALID_INPUT` | invalid request parameters |
| `SHUTTING_DOWN` | SmpShop is shutting down |

A future may complete exceptionally because of an SQLite error, scheduler shutdown, or another system error. A normal operation rejection is returned through its status and should not be handled as an exception.

## Threads and lifecycle

- API methods can be called from any thread.
- Balance reads and mutating operations use asynchronous storage.
- Chest operations run on the owning region.
- Inventory opening runs on the player's scheduler.
- The `CompletionStage` completion thread is not guaranteed.
- Bukkit events do not replace checking the operation result.
- Before SmpShop stops, new operations may return `SHUTTING_DOWN`.

Do not keep `Player` as a long-lived reference. Pass a UUID and obtain the current player object only on the appropriate scheduler.

## Complete minimal example

```java
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import ru.javaroot.api.BalanceChange;
import ru.javaroot.api.CurrencyType;
import ru.javaroot.api.SmpShopApi;
import ru.javaroot.api.event.ShopPurchaseCompletedEvent;

import java.util.UUID;

public class Plugin extends JavaPlugin implements Listener {
    private SmpShopApi shopApi;
    private NamespacedKey source;

    @Override
    public void onEnable() {
        shopApi = getServer().getServicesManager().load(SmpShopApi.class);
        if (shopApi == null || shopApi.version() != SmpShopApi.VERSION) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        source = new NamespacedKey(this, "plugin");
        getServer().getPluginManager().registerEvents(this, this);

        shopApi.awaitReady().thenRun(() -> {
            UUID playerId = UUID.fromString("00000000-0000-0000-0000-000000000000");
            shopApi.changeBalance(new BalanceChange(
                    UUID.randomUUID(),
                    source,
                    playerId,
                    CurrencyType.DIAMOND_ORE,
                    5
            )).thenAccept(result ->
                    getLogger().info("Reward grant result: " + result.status()));
        });
    }

    @EventHandler
    public void onPurchaseCompleted(ShopPurchaseCompletedEvent event) {
        getLogger().info("Sale completed: " + event.transactionId());
    }
}
```

## API packages

The main classes are in:

- `ru.javaroot.api` - the service, requests, snapshots, results, and currency types;
- `ru.javaroot.api.event` - API Bukkit events:

The current public interface version is `SmpShopApi.VERSION == 1`
