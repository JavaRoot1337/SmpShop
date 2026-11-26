# SmpShop

Магазины игроков на сундуках для Paper и Folia `1.21–26.3`.

## Совместимость

| Параметр | Значение |
| --- | --- |
| Ядро | Paper, Folia (`folia-supported: true` в `paper-plugin.yml`) |
| Minecraft | `1.21 – 26.3` |
| Java | `21+` |

## Сборка

```powershell
gradle clean check build
```

Итоговый JAR: `build/libs/SmpShop-2.0.jar`.

## Валюта

В оплату принимаются `DIAMOND_ORE` и `DEEPSLATE_DIAMOND_ORE` (единая валюта `АР`).

## Команды

Основная команда: `/smpshop` (алиас: `/shop`). Только для игроков, кроме `/smpshop reload`.

| Команда | Действие | Право |
| --- | --- | --- |
| `/smpshop` или `/smpshop help` | Показать справку | `smpshop.use` |
| `/smpshop set <цена>` | Начать создание магазина по ПКМ | `smpshop.use` |
| `/smpshop balance` | Открыть меню баланса руды | `smpshop.use`, `smpshop.balance` |
| `/smpshop list` | Открыть список своих магазинов | `smpshop.use` |
| `/smpshop list <игрок>` | Открыть магазины другого игрока | `smpshop.use`, `smpshop.admin.list` |
| `/smpshop delete` | Начать удаление своего магазина | `smpshop.use` |
| `/smpshop remove` | Начать удаление любого магазина | `smpshop.use`, `smpshop.admin.remove` |
| `/smpshop link tg` | Выдать код или ссылку Telegram | `smpshop.use`, `smpshop.link` |
| `/smpshop link ds webhook <url>` | Сохранить личный Discord webhook | `smpshop.use`, `smpshop.link` |
| `/smpshop reload` | Перезагрузить `config.yml` и `messages.yml` | `smpshop.use`, `smpshop.admin.reload` |

## Права

| Право | Назначение | По умолчанию |
| --- | --- | --- |
| `smpshop.use` | Доступ к `/smpshop` | всем |
| `smpshop.balance` | Открытие меню баланса | всем |
| `smpshop.link` | Привязка Telegram и Discord | всем |
| `smpshop.plus` | Повышенный лимит (`plus-max-per-player`) | op |
| `smpshop.admin.list` | Просмотр магазинов другого игрока | op |
| `smpshop.admin.remove` | Удаление любого магазина | op |
| `smpshop.admin.reload` | Перезагрузка конфигурации | op |

## Конфигурация и хранение

Файлы в `plugins/SmpShop/`:
- `config.yml` — диапазон цен, лимиты, размер меню (`36`/`54`), параметры SQLite, звуки, офлайн-уведомления, настройки Telegram/Discord
- `messages.yml` — сообщения, заголовки меню, шаблоны Telegram/Discord, названия предметов (цвета `&` и `&#FFFFFF`, плейсхолдеры `%var%`)
- `shops.db` — база SQLite WAL (`shops`, `balances`, `transactions`, `item_recovery`, `notifications`, `social_links`, `link_codes`, `discord_webhooks`)

## Документация

- [Подробное руководство](guideru.md) — механика, защита, баланс, команды, конфиги, БД
- [Документация API](apiru.md) — Java API, события, контракт потоков

[English documentation](README.md)
