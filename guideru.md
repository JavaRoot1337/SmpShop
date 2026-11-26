# Руководство SmpShop

Подробное описание механики, команд, прав, конфигурации и хранения данных для `SmpShop 2.0.1`.

## Навигация

- [Обзор](READMERU.md)
- [Документация API](apiru.md)
- [English guide](guide.md)

## Валюта

В оплату принимаются:
- `DIAMOND_ORE`
- `DEEPSLATE_DIAMOND_ORE`

Оба материала считаются единой валютой (`АР`) и могут комбинироваться в одной покупке. Другие предметы как оплата не принимаются.

## Механика работы

### Создание магазина

1. Поставьте обычный или двойной сундук (`CHEST`) и положите в него товар.
2. Выполните `/smpshop set <цена>`.
3. Нажмите ПКМ по сундуку в течение `shop.pending-placement-timeout-seconds` (по умолчанию `60` сек).

Правила:
- Цена задаётся за один занятый слот сундука (весь стек в слоте).
- Двойной сундук регистрируется как один магазин.
- Диапазон цен по умолчанию: `1` – `2304` (`shop.min-price`, `shop.max-price`).
- Лимит магазинов по умолчанию: `10` (`shop.max-per-player`), с правом `smpshop.plus` — `50` (`shop.plus-max-per-player`).

### Покупка

1. Нажмите ПКМ по чужому магазину.
2. Пустые слоты сундука служат слотами оплаты, занятые — витриной товаров.
3. Положите `DIAMOND_ORE` или `DEEPSLATE_DIAMOND_ORE` в пустые слоты (ЛКМ/ПКМ, Shift-клик или перетаскивание).
4. Нажмите ЛКМ по нужному товару и переложите его в пустой слот своего инвентаря.

Блокировки и безопасность:
- Пока покупатель в меню, физический сундук закрыт для владельца и других игроков.
- Владелец открывает свой сундук напрямую, если внутри нет покупателя.
- Возврат товара в исходный слот отменяет начатую покупку.
- Если товар в сундуке изменился, не хватает оплаты, закрыт инвентарь или сервер остановился во время сделки, покупка откатывается или ставится в очередь довыдачи при следующем входе.
- Неиспользованная оплата при закрытии возвращается в инвентарь или зачисляется на баланс в SQLite, если вернуть в инвентарь нельзя.

### Защита сундуков

Зарегистрированные магазины защищены от:
- ломания и взрывов блоков/сущностей;
- сдвига и вытягивания поршнями;
- установки воронок рядом и вагонеток с воронкой;
- пристройки второго сундука к одиночному магазину;
- перекачки предметов воронками (`InventoryMoveItemEvent`, `InventoryPickupItemEvent`).

### Меню баланса

`/smpshop balance` открывает меню на 54 слота (`45` слотов под руду, стрелки навигации в слотах `45` и `53`):
- показывает накопленную руду (`DIAMOND_ORE`, `DEEPSLATE_DIAMOND_ORE`) в стеках до 64;
- позволяет забирать руду и класть обратно на баланс;
- при закрытии меню или выходе игрока атомарно сохраняет дельту в SQLite;
- блокирует внешние мутации баланса через API (`ApiStatus.BUSY`), пока меню открыто.

### Список магазинов

`/smpshop list` (или `/smpshop list <игрок>` с правом `smpshop.admin.list`) открывает меню (`36` или `54` слота по `shop.menu-size`):
- иконка карточки берёт самый частый невалютный предмет в сундуке (`BARRIER`, если сундук пуст или мир/блок недоступен);
- отображает ID магазина, владельца, координаты (`x y z`), заработок в `АР`, число проданных предметов и цену слота;
- служит только для просмотра: телепортация и удалённое открытие сундука не выполняются.

### Удаление магазина

1. Выполните `/smpshop delete` (свой магазин) или `/smpshop remove` (любой магазин, право `smpshop.admin.remove`).
2. Нажмите ПКМ по нужному сундуку.

Удаляется только регистрация магазина — сундук и предметы остаются на месте. Удаление запрещено, пока в магазине идёт покупка (`shop-busy`).

## Команды

Основная команда: `/smpshop` (алиас: `/shop`). Все команды, кроме `/smpshop reload`, только для игроков.

| Команда | Действие | Право |
| --- | --- | --- |
| `/smpshop` или `/smpshop help` | Показать справку | `smpshop.use` |
| `/smpshop set <цена>` | Назначить цену и ждать ПКМ по сундуку | `smpshop.use` |
| `/smpshop balance` | Открыть меню баланса руды | `smpshop.use`, `smpshop.balance` |
| `/smpshop list` | Открыть список своих магазинов | `smpshop.use` |
| `/smpshop list <игрок>` | Открыть магазины другого игрока | `smpshop.use`, `smpshop.admin.list` |
| `/smpshop delete` | Удалить свой магазин по ПКМ | `smpshop.use` |
| `/smpshop remove` | Удалить любой магазин по ПКМ | `smpshop.use`, `smpshop.admin.remove` |
| `/smpshop link tg` | Выдать код или ссылку привязки Telegram | `smpshop.use`, `smpshop.link` |
| `/smpshop link ds webhook <url>` | Сохранить личный Discord webhook | `smpshop.use`, `smpshop.link` |
| `/smpshop reload` | Перезагрузить `config.yml` и `messages.yml` | `smpshop.use`, `smpshop.admin.reload` |

## Права

| Право | Назначение | По умолчанию |
| --- | --- | --- |
| `smpshop.use` | Доступ к `/smpshop` | всем |
| `smpshop.balance` | Открытие меню баланса | всем |
| `smpshop.link` | Привязка Telegram и Discord | всем |
| `smpshop.plus` | Лимит магазинов `shop.plus-max-per-player` | op |
| `smpshop.admin.list` | Просмотр магазинов другого игрока | op |
| `smpshop.admin.remove` | Удаление любого магазина | op |
| `smpshop.admin.reload` | Выполнение `/smpshop reload` | op |

## Конфигурация

Все файлы хранятся в `plugins/SmpShop/`.

### `config.yml`

- `shop` — `min-price`, `max-price`, `max-per-player`, `plus-max-per-player`, `menu-size` (`36` или `54`), `allowed-menu-sizes`, `pending-placement-timeout-seconds`
- `storage` — `sqlite-file`, `transaction-timeout-seconds`, `shutdown-timeout-seconds`
- `sounds.seller-sale` / `sounds.buyer-purchase` — `enabled`, `sound`, `volume`, `pitch`
- `notifications` — `offline-enabled`, `join-message-enabled`
- `integrations.http` — `max-concurrent`, `connect-timeout-seconds`, `request-timeout-seconds`, `response-limit-bytes`
- `integrations.telegram` — `enabled`, `bot-token`, `poll-interval-seconds`, `poll-long-poll-seconds`, `link-code-timeout-seconds`, `api-base-url`, `referral-bot`, `user-bot`
- `integrations.discord` — `enabled`

При `/smpshop reload` новый конфиг сначала валидируется — при ошибке остаются предыдущие рабочие настройки.

### `messages.yml`

- Поддерживает `&`-коды и hex-цвета `&#RRGGBB` / `&RRGGBB`.
- Плейсхолдеры только формата `%name%`.
- Поддерживает спрайты `%icon_diamond%` и `%icon_deepslate%`.
- Содержит игровые сообщения, заголовки GUI, шаблоны `telegram.*`, `discord.sale` и названия материалов `items.*`.

## Уведомления о продажах

### В игре

- Онлайн-продавец получает сообщение `sale-chat` и звук `sounds.seller-sale`.
- При `notifications.offline-enabled: true` заработок офлайн-продавца сохраняется в таблицу `notifications` и выводится при входе, если включён `notifications.join-message-enabled`.

### Telegram

1. Укажите `integrations.telegram.enabled: true` и `bot-token`.
2. При необходимости задайте `user-bot` (без `@`) и `referral-bot: true` для кликабельных ссылок `t.me/<bot>?start=<code>`.
3. Выполните `/smpshop reload`.
4. В игре выполните `/smpshop link tg` и перейдите по ссылке или отправьте боту `/code <code>`.

Код одноразовый, состоит из 16 цифр и действует `link-code-timeout-seconds`.

### Discord

1. Включите `integrations.discord.enabled: true` и выполните `/smpshop reload`.
2. В игре выполните `/smpshop link ds webhook https://discord.com/api/webhooks/...`.

Вебхук сохраняется на каждого игрока отдельно; повторный ввод заменяет старый адрес.

## Хранение данных

`plugins/SmpShop/shops.db` (SQLite WAL) хранит таблицы:
- `shops` — магазины и статистика продаж
- `balances` и `balance_ledger` — балансы руды и идемпотентный журнал API
- `transactions` и `item_recovery` — состояния сделок (`PREPARED`, `COMPLETED`, `DELIVERED`, `ROLLED_BACK`) и возврат предметов
- `notifications` — отложенные уведомления офлайн-продавцам
- `social_links` и `link_codes` — привязки Telegram
- `discord_webhooks` — Discord webhook игроков

---

[Обзор](READMERU.md) · [Документация API](apiru.md) · [English guide](guide.md)
