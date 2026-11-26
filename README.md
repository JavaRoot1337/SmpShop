# SmpShop

Chest-based player shops for Paper and Folia `1.21–26.3`.

## Compatibility

| Parameter | Value |
| --- | --- |
| Core | Paper, Folia (`folia-supported: true` in `paper-plugin.yml`) |
| Minecraft | `1.21 – 26.3` |
| Java | `21+` |

## Build

```powershell
gradle clean check build
```

Output JAR: `build/libs/SmpShop-2.0.1.jar`.

## Currency

Accepted materials: `DIAMOND_ORE` and `DEEPSLATE_DIAMOND_ORE` (combined as `АР`).

## Commands

Main command: `/smpshop` (alias: `/shop`). Player-only except `/smpshop reload`.

| Command | Action | Permission |
| --- | --- | --- |
| `/smpshop` or `/smpshop help` | Show help | `smpshop.use` |
| `/smpshop set <price>` | Start creating a shop on right-clicked chest | `smpshop.use` |
| `/smpshop balance` | Open ore balance menu | `smpshop.use`, `smpshop.balance` |
| `/smpshop list` | Open your shop list | `smpshop.use` |
| `/smpshop list <player>` | Open another player's shop list | `smpshop.use`, `smpshop.admin.list` |
| `/smpshop delete` | Start removing your shop | `smpshop.use` |
| `/smpshop remove` | Start removing any shop | `smpshop.use`, `smpshop.admin.remove` |
| `/smpshop link tg` | Issue Telegram link code or bot URL | `smpshop.use`, `smpshop.link` |
| `/smpshop link ds webhook <url>` | Save personal Discord webhook | `smpshop.use`, `smpshop.link` |
| `/smpshop reload` | Reload `config.yml` and `messages.yml` | `smpshop.use`, `smpshop.admin.reload` |

## Permissions

| Permission | Purpose | Default |
| --- | --- | --- |
| `smpshop.use` | Base `/smpshop` access | all |
| `smpshop.balance` | Open balance menu | all |
| `smpshop.link` | Link Telegram and Discord | all |
| `smpshop.plus` | Increased shop limit (`plus-max-per-player`) | op |
| `smpshop.admin.list` | View other players' shops | op |
| `smpshop.admin.remove` | Remove any shop | op |
| `smpshop.admin.reload` | Reload configuration | op |

## Configuration & storage

Files in `plugins/SmpShop/`:
- `config.yml` — price bounds (`shop.min-price`–`max-price`), player limits, menu size (`36`/`54`), SQLite settings, sounds, offline join notifications, Telegram/Discord integration settings
- `messages.yml` — in-game messages, GUI labels, Telegram/Discord templates, item names (`&` and `&#FFFFFF` colors, `%var%` placeholders)
- `shops.db` — SQLite WAL database (`shops`, `balances`, `transactions`, `item_recovery`, `notifications`, `social_links`, `link_codes`, `discord_webhooks`)

## Documentation

- [Usage guide](guide.md) — mechanics, protection, balance, commands, config, storage
- [API guide](api.md) — Java API, events, threading contract

[Русская документация](READMERU.md)
