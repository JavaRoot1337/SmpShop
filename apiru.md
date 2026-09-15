# SmpShop API

Руководство по интеграции стороннего плагина с SmpShop API v1.

API находится в основном JAR плагина SmpShop. Отдельный API-плагин устанавливать не нужно. Сервис доступен через Bukkit `ServicesManager`.

## Что доступно через API

- чтение магазинов по ID, координатам и UUID владельца;
- чтение баланса игрока;
- начисление и списание руды;
- перевод руды между двумя игроками;
- создание, изменение цены и удаление магазинов;
- открытие меню баланса и списка магазинов;
- события изменения баланса, магазинов и покупок;
- добавление комиссий к покупке или отмена покупки до списания оплаты.

API использует два типа валюты:

- `CurrencyType.DIAMOND_ORE`;
- `CurrencyType.DEEPSLATE_DIAMOND_ORE`.

## Подключение плагина-потребителя

Добавьте SmpShop как обязательную зависимость в `plugin.yml`:

```yaml
name: Plugin
main: primer.Plugin
version: 1.0.0
api-version: '1.21.11'
depend: [SmpShop]
```

В проекте-потребителе API можно подключить из JAR SmpShop как compile-only зависимость:

```kotlin
dependencies {
    compileOnly(files("libs/SmpShop-1.0.jar"))
}
```

Плагин SmpShop должен находиться в папке `plugins` рядом с вашим плагином. Не добавляйте SmpShop внутрь своего JAR и не устанавливайте тестовый `SmpShopApiConsumer`: это только пример потребителя API.

## Получение сервиса

Получите сервис в `onEnable`:

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
            getLogger().severe("API SmpShop недоступен");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        source = new NamespacedKey(this, "plugin");
    }
}
```

Проверьте версию перед использованием API:

```java
if (shopApi.version() != SmpShopApi.VERSION) {
    getLogger().severe("Неподдерживаемая версия API SmpShop: " + shopApi.version());
    getServer().getPluginManager().disablePlugin(this);
    return;
}
```

Сервис регистрируется до завершения загрузки SQLite. Поэтому сразу после получения сервиса API может быть ещё не готов к операциям.

## Состояние и готовность

Состояние доступно через `state()`:

| Состояние | Значение |
| --- | --- |
| `STARTING` | плагин загружает базу и магазины |
| `READY` | API принимает операции |
| `FAILED` | запуск базы или загрузка данных завершились ошибкой |
| `SHUTTING_DOWN` | плагин останавливается |
| `STOPPED` | плагин остановлен |

Для ожидания используйте `awaitReady()`:

```java
shopApi.awaitReady().thenRun(() -> {
    // API готов к работе
    addReward(playerId, 5);
}).exceptionally(error -> {
    getLogger().warning("API SmpShop не перешёл в состояние готовности: " + error.getMessage());
    return null;
});
```

Не вызывайте Bukkit API из этого callback без возврата на нужный серверный scheduler. Поток завершения `CompletionStage` не является частью контракта API.

Если вызвать мутацию до готовности, она вернёт `ApiStatus.NOT_READY`. Метод `balance(UUID)` в этом случае завершится исключением `CompletionStage`.

## Общие правила операций

Все изменяющие запросы содержат:

- `UUID operationId` - уникальный идентификатор операции;
- `NamespacedKey source` - источник операции, обычно namespace и ключ вашего плагина.

Создавайте новый `operationId` для каждой новой операции:

```java
NamespacedKey source = new NamespacedKey(this, "daily_reward");
UUID operationId = UUID.randomUUID();
```

Повтор того же запроса с теми же `operationId` и `source` не применяет изменение второй раз. Это позволяет безопасно повторить запрос после сетевой или серверной ошибки.

Не используйте один `operationId` для разных действий. Если для уже записанной операции передать другие параметры, API вернёт `CONFLICT`.

API не проверяет права игрока. Если операцию запускает команда или GUI другого плагина, проверяйте permissions самостоятельно.

## Чтение магазинов

### Поиск по ID

```java
Optional<ShopSnapshot> shop = shopApi.shop(42L);

shop.ifPresent(value -> {
    getLogger().info("Владелец магазина: " + value.ownerName());
    getLogger().info("Цена: " + value.price());
});
```

### Поиск по координатам

```java
ShopPosition position = new ShopPosition(world.getUID(), x, y, z);
Optional<ShopSnapshot> shop = shopApi.shop(position);
```

`ShopPosition` содержит UUID мира и координаты блока. Для двойного сундука используйте координаты любой зарегистрированной половины.

### Магазины игрока

```java
List<ShopSnapshot> shops = shopApi.shops(ownerId);
```

Список возвращается как неизменяемый snapshot. Он не открывает сундук и не телепортирует игрока.

### ShopSnapshot

`ShopSnapshot` содержит:

| Метод | Значение |
| --- | --- |
| `id()` | ID магазина |
| `ownerId()` | UUID владельца |
| `ownerName()` | имя владельца на момент регистрации |
| `first()` | первая позиция сундука |
| `second()` | вторая позиция двойного сундука или `null` |
| `price()` | цена товарного стека |
| `earnedTotal()` | общий заработок |
| `soldSlots()` | число проданных товарных слотов |
| `soldItems()` | число проданных предметов |
| `createdAt()` | время создания в миллисекундах Unix |

Snapshot не обновляется автоматически. Для актуальных данных вызовите `shop(id)` или `shops(ownerId)` ещё раз.

## Баланс

### Чтение баланса

```java
shopApi.balance(playerId).thenAccept(balance -> {
    CurrencyAmounts amounts = balance.amounts();
    getLogger().info("Алмазная руда: " + amounts.diamondOre());
    getLogger().info("Алмазная руда из глубинного сланца: " + amounts.deepslateDiamondOre());
});
```

`balance(UUID)` возвращает `CompletionStage<BalanceSnapshot>`, потому что чтение выполняется через SQLite.

### Изменение баланса

Положительное значение начисляет руду, отрицательное списывает:

```java
shopApi.changeBalance(new BalanceChange(
        UUID.randomUUID(),
        source,
        playerId,
        CurrencyType.DIAMOND_ORE,
        5
)).thenAccept(result -> {
    if (!result.successful()) {
        getLogger().warning("Изменение баланса отклонено: " + result.status());
    }
});
```

Нулевое значение недопустимо. Списание больше текущего баланса вернёт `INSUFFICIENT_FUNDS`. Переполнение баланса вернёт `CONFLICT`.

Если игрок в этот момент редактирует баланс через игровое меню, операция вернёт `BUSY`.

### Перевод между игроками

`transferBalance` переводит положительное количество одной валюты атомарно:

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

Перевод самому себе и перевод с недостаточным балансом не выполняются.

### BalanceResult

| Поле | Значение |
| --- | --- |
| `status()` | результат операции |
| `source()` | новый баланс игрока-источника, если он доступен |
| `target()` | новый баланс получателя для перевода |
| `successful()` | сокращение для проверки `status() == SUCCESS` |

## Управление магазинами

API работает с физическими сундуками в мире. Оно не создаёт виртуальный магазин без блока.

### Создание

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
        getLogger().info("Создан магазин #" + created.id());
    } else {
        getLogger().warning("Не удалось создать магазин: " + result.status());
    }
});
```

Поле `second` оставьте `null` для обычного сундука. Для двойного сундука передайте обе позиции в одном мире.

Создание проверяет:

- что мир существует;
- что по указанным координатам находится сундук;
- что позиции соответствуют обычному или двойному сундуку;
- что цена попадает в диапазон из `config.yml`;
- что лимит владельца не превышен;
- что сундук не занят другим магазином.

Для операций API используется обычный лимит `shop.max-per-player`. Permission `smpshop.plus` на вызовы API автоматически не распространяется.

### Изменение цены

```java
shopApi.changePrice(new ShopPriceChange(
        UUID.randomUUID(),
        source,
        shopId,
        20
)).thenAccept(result -> {
    if (result.successful()) {
        getLogger().info("Новая цена: " + result.shop().price());
    }
});
```

Цена должна находиться между `shop.min-price` и `shop.max-price`.

### Удаление

```java
shopApi.removeShop(new ShopRemove(
        UUID.randomUUID(),
        source,
        shopId
)).thenAccept(result -> {
    if (result.status() == ApiStatus.BUSY) {
        getLogger().warning("Магазин используется покупателем");
    }
});
```

Удаление убирает регистрацию магазина, но не удаляет предметы из физического сундука.

Операция вернёт `BUSY`, если магазин используется покупателем. Перед удалением API также вызывает `ShopRemovePrepareEvent`, поэтому другой плагин может отменить операцию.

## Открытие меню

API может открыть стандартные меню SmpShop для игрока:

```java
shopApi.openBalance(playerId).thenAccept(result -> {
    if (result.status() != ApiStatus.SUCCESS) {
        getLogger().warning("Не удалось открыть баланс: " + result.status());
    }
});

shopApi.openShopList(viewerId, ownerId).thenAccept(result -> {
    if (!result.successful()) {
        getLogger().warning("Не удалось открыть список магазинов: " + result.status());
    }
});
```

Оба метода требуют, чтобы игрок был онлайн. Если игрок не найден, возвращается `NOT_FOUND`. Открытие выполняется через scheduler владельца сущности игрока.

## События

Все события находятся в пакете `ru.javaroot.api.event`. Регистрируйте их обычным Bukkit-слушателем:

```java
public class Plugin extends JavaPlugin implements Listener {
    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
    }

    @EventHandler
    public void onBalanceChanged(BalanceChangedEvent event) {
        getLogger().info("Баланс изменён для игрока " + event.balance().playerId());
    }
}
```

### События изменения баланса и магазинов

| Событие | Когда вызывается |
| --- | --- |
| `BalanceChangedEvent` | после успешного изменения или перевода баланса |
| `ShopChangedEvent` | после создания, изменения цены или удаления магазина |
| `ShopTransactionRolledBackEvent` | после отката незавершённой покупки |

`ShopChangedEvent.type()` принимает:

- `CREATED`;
- `PRICE_CHANGED`;
- `REMOVED`.

`ShopTransactionRolledBackEvent` содержит `transactionId()`, `shopId()` и `buyerId()`.

### Подготовка создания и удаления

`ShopCreatePrepareEvent` и `ShopRemovePrepareEvent` реализуют `Cancellable`. Используйте их для проверки собственных правил:

```java
@EventHandler
public void onShopRemove(ShopRemovePrepareEvent event) {
    if (isProtectedShop(event.shop().id())) {
        event.setCancelled(true);
    }
}
```

Отмена события возвращает результат `CONFLICT`.

### Подготовка покупки

`ShopPurchasePrepareEvent` вызывается до списания оплаты и изменения физического сундука. Событие можно отменить:

```java
@EventHandler
public void onPurchasePrepare(ShopPurchasePrepareEvent event) {
    if (isBlockedItem(event.shop())) {
        event.setCancelled(true);
    }
}
```

В событии доступны:

- `transactionId()`;
- `buyerId()`;
- `shop()`;
- `basePayment()`;
- `adjustments()`.

### Комиссии и наценки

Добавляйте корректировку через уникальный `NamespacedKey`:

```java
@EventHandler
public void onPurchasePrepare(ShopPurchasePrepareEvent event) {
    boolean added = event.addAdjustment(
            new NamespacedKey(this, "bank_fee"),
            AdjustmentType.SELLER_DEDUCTION,
            new CurrencyAmounts(1, 0)
    );

    if (!added) {
        getLogger().warning("Ключ корректировки уже используется");
    }
}
```

Типы корректировок:

| Тип | Результат |
| --- | --- |
| `SELLER_DEDUCTION` | удерживает руду из выплаты продавцу |
| `BUYER_SURCHARGE` | добавляет руду к оплате покупателя |

`CurrencyAmounts` не может содержать отрицательные значения. Для `PurchaseAdjustment` сумма не может быть пустой. Один ключ можно добавить только один раз. Если продавцу нечего удержать или покупателю не хватает средств на итоговую сумму, покупка не проходит.

### Завершение покупки

`ShopPurchaseCompletedEvent` вызывается после записи успешной продажи в базу:

```java
@EventHandler
public void onPurchaseCompleted(ShopPurchaseCompletedEvent event) {
    getLogger().info(
            "Транзакция " + event.transactionId()
                    + ", магазин " + event.shop().id()
                    + ", исходная цена " + event.originalPrice()
    );

    CurrencyAmounts buyerPayment = event.buyerPayment();
    CurrencyAmounts sellerPayout = event.sellerPayout();
}
```

Событие содержит исходную цену, фактическую оплату покупателя, выплату продавцу и применённые корректировки.

Используйте `ShopTransactionRolledBackEvent` для обработки внешних действий, которые нужно компенсировать после отката. Не выдавайте награду только по `ShopPurchasePrepareEvent`: на этом этапе покупка ещё может быть отменена.

## Результаты и статусы

Изменяющие методы возвращают `BalanceResult`, `ShopResult` или `OpenResult`. У них есть метод `successful()`.

| Статус | Значение |
| --- | --- |
| `SUCCESS` | операция выполнена |
| `NOT_READY` | API ещё загружается или не смогло запуститься |
| `NOT_FOUND` | игрок, мир, сундук или магазин не найден |
| `BUSY` | баланс или магазин занят другой операцией |
| `LIMIT_REACHED` | достигнут лимит магазинов владельца |
| `INSUFFICIENT_FUNDS` | недостаточно руды |
| `CONFLICT` | операция конфликтует с текущим состоянием или была отменена событием |
| `INVALID_INPUT` | недопустимые параметры запроса |
| `SHUTTING_DOWN` | SmpShop завершает работу |

Future может завершиться исключением при ошибке SQLite, остановке scheduler или другой системной ошибке. Обычный отказ операции возвращается через статус и не должен обрабатываться как исключение.

## Потоки и жизненный цикл

- Методы API можно вызывать из любого потока.
- Чтение баланса и изменяющие операции используют асинхронное хранилище.
- Операции с сундуками выполняются на owning region.
- Открытие инвентарей выполняется на scheduler игрока.
- Поток завершения `CompletionStage` не гарантируется.
- Bukkit-события не заменяют проверку результата операции.
- Перед остановкой SmpShop новые операции могут вернуть `SHUTTING_DOWN`.

Не храните `Player` как долгоживущую ссылку. Передавайте UUID и получайте актуальный объект игрока только на нужном scheduler.

## Полный минимальный пример

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
                    getLogger().info("Результат начисления награды: " + result.status()));
        });
    }

    @EventHandler
    public void onPurchaseCompleted(ShopPurchaseCompletedEvent event) {
        getLogger().info("Продажа завершена: " + event.transactionId());
    }
}
```

## Пакеты API

Основные классы находятся в:

- `ru.javaroot.api` - сервис, запросы, snapshots, результаты и типы валюты;
- `ru.javaroot.api.event` - Bukkit-события API.

Текущая версия публичного интерфейса: `SmpShopApi.VERSION == 1`.
