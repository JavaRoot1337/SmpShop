# SmpShop

SmpShop — магазины игроков на обычных сундуках с оплатой алмазной рудой. Один JAR предназначен для Paper, Folia и Canvas от `1.21.11` до `26.2`.

- версия плагина: `1.1.0`
- основной файл: `SmpShop-1.1.0.jar`
- команда: `/smpshop`, алиас `/shop`
- хранилище: SQLite
- публичный API: v1

Spigot и CraftBukkit без Paper API не поддерживаются.

## Установка

| Сервер | Java |
| --- | --- |
| Paper или Folia `1.21.11` | 21 |
| Paper, Folia или Canvas `26.1+` | 25 |

1. Поместите `SmpShop-1.1.0.jar` в папку `plugins`.
2. Запустите сервер.
3. Настройте файлы в `plugins/SmpShop/`.
4. Выполните `/smpshop reload` или перезапустите сервер.

`SmpShopApiConsumer-1.1.0.jar` устанавливать не нужно. Это тестовый пример использования API.

`api-version: '1.21.11'` не позволяет загрузить плагин на более старом сервере. `folia-supported: true` соответствует используемой scheduler-модели.

## Команды и права

Команды выполняются только игроком. Для любой подкоманды требуется `smpshop.use`, которое по умолчанию доступно всем.

| Команда | Дополнительное право | Назначение |
| --- | --- | --- |
| `/smpshop` или `/smpshop help` | — | показать справку |
| `/smpshop set <цена>` | — | начать создание магазина |
| `/smpshop balance` | `smpshop.balance` | открыть баланс руды |
| `/smpshop delete` | — | удалить свой магазин после ПКМ по сундуку |
| `/smpshop remove` | `smpshop.admin.remove` | удалить любой магазин после ПКМ по сундуку |
| `/smpshop list` | — | показать свои магазины |
| `/smpshop list <игрок>` | `smpshop.admin.list` | показать магазины другого игрока |
| `/smpshop link tg` | `smpshop.link` | получить код или ссылку Telegram |
| `/smpshop link ds webhook <url>` | `smpshop.link` | сохранить личный Discord webhook |
| `/smpshop link max` | `smpshop.link` | показать настроенное специальное сообщение |
| `/smpshop reload` | `smpshop.admin.reload` | перечитать конфигурацию и сообщения |

Права `smpshop.balance` и `smpshop.link` по умолчанию доступны всем. Права `smpshop.admin.remove`, `smpshop.admin.list` и `smpshop.admin.reload` выдаются операторам. `smpshop.plus` включает увеличенный лимит магазинов и тоже по умолчанию доступен операторам.

## Магазины

### Создание

Игрок выполняет `/smpshop set <цена>` и в течение `shop.pending-placement-timeout-seconds` секунд нажимает ПКМ по обычному сундуку. Цена задаётся за весь товарный стек в выбранном слоте.

Плагин проверяет:

- диапазон `shop.min-price` — `shop.max-price`;
- лимит `shop.max-per-player` или `shop.plus-max-per-player`;
- незавершённые установки этого игрока;
- обе половины двойного сундука;
- соседние сундуки, которые могут образовать конфликтный двойной сундук.

Товар остаётся в физическом сундуке. Владелец пользуется им как обычно, пока магазин не открыт покупателем.

### Покупка

Валюта фиксирована:

- `DIAMOND_ORE` — 1 АР;
- `DEEPSLATE_DIAMOND_ORE` — 1 АР.

Руды учитываются отдельно, но их можно смешивать в одной оплате. Другие материалы валютой не считаются.

Покупатель открывает виртуальный снимок магазина. Физический сундук на время сессии блокируется для владельца и других покупателей. Руда помещается в payment-слоты, после чего клик по товару покупает весь стек из выбранного слота.

Покупка отменяется, если не хватает оплаты, товар изменился, магазин занят или подготовительное API-событие отменено. Закрытие меню возвращает неиспользованную руду. При выходе игрока она зачисляется на его баланс.

Сделка проходит состояния `PREPARED → COMPLETED → DELIVERED` либо `ROLLED_BACK`. Товар и оплата записываются до изменения inventory. Начисление продавцу и комиссии выполняются одной SQLite-транзакцией. Если после commit товар нельзя выдать, он остаётся в pending-delivery и выдаётся при следующем входе.

### Баланс

`/smpshop balance` открывает меню на 54 слота:

- слоты 0–44 содержат настоящие предметы руды;
- стрелки страниц находятся в слотах 45 и 53;
- слоты 46–52 пусты.

Из меню можно забрать только два поддерживаемых вида руды. Списание выполняется атомарно; при отказе предметы возвращаются на баланс.

### Список и удаление

`/smpshop list` показывает ID, владельца, координаты, цену и статистику магазинов. Карточки информационные: удалённо открыть физический сундук нельзя.

`/smpshop delete` снимает регистрацию собственного магазина, `/smpshop remove` — любого магазина. Содержимое сундука не удаляется. Магазин с активной покупкой удалить нельзя.

## Защита

Зарегистрированный сундук нельзя:

- сломать или уничтожить взрывом;
- сдвинуть поршнем;
- объединить с новым соседним сундуком;
- опустошить воронкой или вагонеткой с воронкой;
- использовать одновременно в нескольких покупательских сессиях.

Отдельные голограммы и сущности плагин не создаёт. Долгоживущее состояние хранится по UUID, а не через объекты `Player`.

## Настройка

Все файлы создаются в `plugins/SmpShop/`.

| Файл | Содержимое |
| --- | --- |
| `config.yml` | магазины, SQLite, звуки, уведомления и интеграции |
| `message.yml` | игровые сообщения и названия элементов GUI |
| `message_other.yml` | сообщения серверного лога и ошибки интеграций |
| `message_tg.yml` | ответы и уведомления Telegram |
| `message_ds.yml` | уведомления Discord |
| `logger_locate.yml` | русские названия предметов для внешних уведомлений |

Исходные файлы: [config.yml](src/main/resources/config.yml), [message.yml](src/main/resources/message.yml), [message_other.yml](src/main/resources/message_other.yml), [message_tg.yml](src/main/resources/message_tg.yml), [message_ds.yml](src/main/resources/message_ds.yml) и [logger_locate.yml](src/main/resources/logger_locate.yml).

Основные разделы `config.yml`:

| Раздел | Ключи |
| --- | --- |
| `shop` | `min-price`, `max-price`, `max-per-player`, `plus-max-per-player`, `menu-size`, `allowed-menu-sizes`, `pending-placement-timeout-seconds` |
| `storage` | `sqlite-file`, `transaction-timeout-seconds` |
| `sounds.seller-sale`, `sounds.buyer-purchase` | `enabled`, `sound`, `volume`, `pitch` |
| `notifications` | `offline-enabled`, `join-message-enabled` |
| `integrations.http` | `max-concurrent`, `connect-timeout-seconds`, `response-limit-bytes` |
| `integrations.telegram` | `enabled`, `bot-token`, `poll-interval-seconds`, `link-code-timeout-seconds`, `referral-bot`, `user-bot` |
| `integrations.discord` | `enabled` |

Размер `shop.menu-size` должен входить в `shop.allowed-menu-sizes`. Поддерживаются меню на 36 и 54 слота. Старый ключ `referal-bot` переносится в `referral-bot` с сохранением значения.

Reload загружает и проверяет YAML до замены текущих настроек. При ошибке плагин сохраняет прежнюю конфигурацию и не оставляет Telegram или HTTP частично перезапущенными. Для замены JAR нужен полный перезапуск сервера.

### Плейсхолдеры

| Файл | Плейсхолдеры |
| --- | --- |
| `message.yml` | `%min%`, `%max%`, `%limit%`, `%price%`, `%page%`, `%id%`, `%owner%`, `%x%`, `%y%`, `%z%`, `%earned%`, `%sold%`, `%shop%`, `%item%`, `%items%`, `%amount%` |
| `message_other.yml` | `%sound%`, `%status%`, `%error%`, `%resource%`, `%key%`, `%item%` |
| `message_tg.yml`, `message_ds.yml` | `%shop%`, `%item%`, `%items%`, `%amount%` |

Код привязки добавляется между `link-code-prefix` и `link-code-suffix`, поэтому `%code%` там не нужен. Символы `🟦`, `⬛` и `⬛️` в `message.yml` выводят встроенные спрайты двух видов руды без resource pack.

## Уведомления

Онлайн-продавец получает игровое сообщение и звук. Для офлайн-продавца уведомления можно сохранять и объединять до следующего входа через раздел `notifications`. Баланс начисляется независимо от доставки сообщения.

Названия звуков указываются в формате Minecraft, например `entity.player.levelup`. Неизвестный звук отключается отдельно и записывается в лог.

### Telegram

1. Включите `integrations.telegram.enabled`.
2. Укажите `bot-token` и `user-bot` без `@`.
3. Выполните `/smpshop reload`.
4. Игрок использует `/smpshop link tg`.

При `referral-bot: true` игрок получает ссылку `https://t.me/<bot>?start=<код>`. При `false` он получает одноразовый 16-значный код для `/code <код>`. В SQLite хранится SHA-256 хеш кода, а не исходное значение. Бот принимает `/start <код>` и `/code <код>`.

Telegram получает уведомления только о продажах. Ошибка или недоступность Bot API не отключает магазины.

### Discord

1. Включите `integrations.discord.enabled`.
2. Игрок выполняет `/smpshop link ds webhook <url>`.
3. Продажи его магазинов отправляются на сохранённый webhook.

Webhook хранится в SQLite по UUID игрока. Повторная команда заменяет адрес. Плагин запрещает Discord mentions и ограничивает размер HTTP-ответа. Token Telegram и webhook нельзя публиковать в Git, чатах или документации.

## Данные и восстановление

SQLite-файл по умолчанию находится в `plugins/SmpShop/shops.db`. В базе хранятся магазины, два баланса руды, ledger, социальные привязки, одноразовые коды, webhook, уведомления, операции API и журнал сделок.

- SQLite работает в отдельной последовательной очереди с WAL и `synchronous=FULL`;
- перед миграцией существующей базы создаётся согласованная резервная копия;
- баланс защищён от отрицательных значений и переполнения `long`;
- незавершённые сделки восстанавливаются при запуске идемпотентно;
- уведомления связаны с `transactionId`, поэтому recovery не создаёт дубликаты;
- записи с отсутствующим миром или сундуком отмечаются аудитом, но автоматически не удаляются.

Физический сундук остаётся источником товарного inventory. SQLite хранит снимок товара для активной сделки и recovery, но не может восстановить весь сундук после удаления файла мира.

## Paper и Folia

Операции с блоками и сундуками выполняются на owning region, действия с игроком и его inventory — через entity scheduler, SQLite и HTTP — асинхронно. Global scheduler используется для глобального состояния сервера. Retired и rejected задачи обрабатываются как отказ операции.

При выключении плагин прекращает новые покупки и API-mutations, останавливает HTTP и polling, сохраняет recovery-состояние сессий, ограниченно ждёт очередь SQLite и затем закрывает базу.

## Публичный API v1

API находится в пакете `ru.javaroot.api` основного JAR. Отдельный API-плагин не требуется.

Подключение локального JAR в Gradle:

```kotlin
dependencies {
    compileOnly(files("libs/SmpShop-1.1.0.jar"))
}
```

В `plugin.yml` consumer-плагина:

```yaml
depend: [SmpShop]
```

Для необязательной интеграции используйте `softdepend: [SmpShop]` и проверяйте наличие сервиса.

### Получение сервиса

```java
SmpShopApi api = Bukkit.getServicesManager().load(SmpShopApi.class);
if (api == null) {
    return;
}

api.awaitReady().thenRun(() -> {
});
```

`SmpShopApi.VERSION` равен `1`. Доступны:

- `state()`, `ready()` и `awaitReady()`;
- поиск магазина по ID или `ShopPosition`;
- immutable-список магазинов владельца;
- чтение, изменение и перевод баланса;
- создание, изменение цены и удаление магазина;
- открытие balance GUI и списка магазинов по UUID.

Методы разрешено вызывать с любого потока. SmpShop сам выбирает region, entity или async scheduler. `CompletionStage` не обещает конкретный completion-thread. Consumer самостоятельно проверяет permissions перед вызовом API.

Mutation-запросы содержат `UUID operationId` и `NamespacedKey source`. Повтор запроса с теми же данными не выполняет операцию второй раз. Ожидаемый отказ возвращается через `ApiStatus`:

`SUCCESS`, `NOT_READY`, `NOT_FOUND`, `BUSY`, `LIMIT_REACHED`, `INSUFFICIENT_FUNDS`, `CONFLICT`, `INVALID_INPUT`, `SHUTTING_DOWN`.

Исключительное завершение future означает системную ошибку, а не обычный отказ операции.

### Баланс

```java
NamespacedKey source = new NamespacedKey(plugin, "daily_reward");

api.changeBalance(new BalanceChange(
        UUID.randomUUID(),
        source,
        playerId,
        CurrencyType.DIAMOND_ORE,
        5
)).thenAccept(result -> {
    if (!result.successful()) {
        plugin.getLogger().warning("Balance change rejected: " + result.status());
    }
});
```

Положительный `amount` начисляет руду, отрицательный списывает. `transferBalance` переносит один вид руды между двумя UUID атомарно.

### События

| Событие | Поток | Назначение |
| --- | --- | --- |
| `ShopCreatePrepareEvent` | region | отмена создания |
| `ShopRemovePrepareEvent` | region или вызывающий поток для отсутствующего мира | отмена удаления |
| `ShopPurchasePrepareEvent` | region | отмена покупки и добавление комиссий |
| `ShopChangedEvent` | async | создание, новая цена или удаление |
| `BalanceChangedEvent` | async | завершённое изменение баланса |
| `ShopPurchaseCompletedEvent` | async | завершённая DB-часть покупки |
| `ShopTransactionRolledBackEvent` | async | откат покупки |

Комиссия добавляется в prepare-событии. Ключ должен быть уникален в пределах сделки.

```java
@EventHandler
public void onPurchase(ShopPurchasePrepareEvent event) {
    event.addAdjustment(
            new NamespacedKey(plugin, "bank_fee"),
            AdjustmentType.SELLER_DEDUCTION,
            new CurrencyAmounts(1, 0)
    );
}
```

`SELLER_DEDUCTION` удерживает комиссию из выплаты продавцу, `BUYER_SURCHARGE` добавляет её к оплате покупателя. Completed-событие содержит `transactionId`, исходную цену, фактическую оплату, выплату продавцу и список корректировок.

Полный consumer находится в [src/testConsumer](src/testConsumer) и собирается в `build/examples/SmpShopApiConsumer-1.1.0.jar`. Он предназначен только для проверки API и добавляет тестовую комиссию к покупкам.

## Сборка

```powershell
.\gradlew.bat clean build --rerun-tasks
```

Артефакты:

- `build/libs/SmpShop-1.1.0.jar` — плагин для сервера;
- `build/examples/SmpShopApiConsumer-1.1.0.jar` — тестовый API-consumer.

Основной JAR использует Java major 65 и содержит SQLite natives для Linux glibc/musl, Windows и macOS на x86_64 и ARM64.

## Ограничения

- набор валюты нельзя изменить через YAML;
- цена задаётся отдельно для каждого магазина;
- один магазин обслуживает одну покупательскую сессию одновременно;
- список магазинов не открывает сундуки удалённо;
- удаление мира или его файла не восстанавливает полный товарный сундук из SQLite;
- перед публикацией сборки нужна runtime-проверка на выбранных версиях Paper, Folia и Canvas.

## Лицензия

[MIT](LICENSE)
