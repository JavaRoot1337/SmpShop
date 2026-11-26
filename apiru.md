# SmpShop API v1

Публичный контракт в пакетах `ru.javaroot.api` и `ru.javaroot.api.event`, встроен в `SmpShop-2.0.jar`.

## Точки входа

```java
SmpShopApi api = Bukkit.getServicesManager().load(SmpShopApi.class);
// или
SmpShopApi api = ApiProvider.get(); // предварительно проверьте ApiProvider.isAvailable()
```

Проверяйте `api.version() == SmpShopApi.VERSION` и при необходимости дождитесь запуска БД через `api.awaitReady()`.

## Подключение

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

## Состояние и статусы

`api.state()` возвращает `STARTING`, `READY`, `FAILED`, `SHUTTING_DOWN` или `STOPPED`.

Мутации возвращают `CompletionStage` со статусом `ApiStatus`: `SUCCESS`, `NOT_READY`, `NOT_FOUND`, `BUSY`, `LIMIT_REACHED`, `INSUFFICIENT_FUNDS`, `CONFLICT`, `INVALID_INPUT`, `SHUTTING_DOWN`.

## Операции

- **Чтение магазинов:** `shop(long id)`, `shop(ShopPosition pos)`, `shops(UUID ownerId)` -> неизменяемый `ShopSnapshot`
- **Баланс:** `balance(UUID)`, `changeBalance(BalanceChange)`, `transferBalance(BalanceTransfer)`
- **Магазины:** `createShop(ShopCreate)`, `changePrice(ShopPriceChange)`, `removeShop(ShopRemove)`
- **Меню:** `openBalance(UUID)`, `openShopList(UUID viewerId, UUID ownerId)`

Каждая изменяющая операция принимает уникальный `UUID operationId` и `NamespacedKey source` для идемпотентности; повтор того же `operationId` с другими параметрами вернёт `CONFLICT`.

## События (`ru.javaroot.api.event`)

| Событие | Тип | Назначение |
| --- | --- | --- |
| `ShopCreatePrepareEvent` | `Cancellable` | Проверка или отмена создания магазина через API |
| `ShopRemovePrepareEvent` | `Cancellable` | Проверка или отмена удаления магазина через API |
| `ShopPurchasePrepareEvent` | `Cancellable` | Отмена покупки или добавление `PurchaseAdjustment` (`SELLER_DEDUCTION`, `BUYER_SURCHARGE`) |
| `ShopPurchaseCompletedEvent` | Async | Уведомление после записи продажи в SQLite |
| `ShopTransactionRolledBackEvent` | Async | Уведомление после отката незавершённой сделки |
| `BalanceChangedEvent` | Async | Уведомление об изменении баланса |
| `ShopChangedEvent` | Async | Уведомление `CREATED`, `PRICE_CHANGED`, `REMOVED` |

## Контракт потоков

- Методы API можно вызывать из любого потока на Paper и Folia
- Поток завершения `CompletionStage` не фиксирован; вызовы Bukkit/игрока переводите на нужный планировщик Paper/Folia

[English API guide](api.md)
