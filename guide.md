# SmpShop Guide

Full usage, mechanics, commands, configuration, and storage reference for `SmpShop 2.0.1`.

## Navigation

- [Overview](README.md)
- [API guide](api.md)
- [Русское руководство](guideru.md)

## Currency

Accepted materials:
- `DIAMOND_ORE`
- `DEEPSLATE_DIAMOND_ORE`

Both ores count as one currency (`АР`) and can be mixed in a single payment. Other items are not accepted as payment.

## How it works

### Creating a shop

1. Place a single or double chest (`CHEST`) and put items inside.
2. Run `/smpshop set <price>`.
3. Right-click the chest within `shop.pending-placement-timeout-seconds` (default `60`s).

Rules:
- Price applies per occupied chest slot (the entire stack in that slot).
- Double chests register as one shop.
- Default price range: `1` – `2304` (`shop.min-price`, `shop.max-price`).
- Default player limit: `10` (`shop.max-per-player`), `50` with `smpshop.plus` (`shop.plus-max-per-player`).

### Buying

1. Right-click another player's shop chest.
2. Empty chest slots serve as payment slots; occupied slots hold items for sale.
3. Place `DIAMOND_ORE` or `DEEPSLATE_DIAMOND_ORE` into empty slots (left/right click, shift-click, or drag).
4. Left-click the item stack you want to buy, then place it into an empty slot in your inventory.

Safety & locking:
- While a buyer has the shop open, the physical chest is locked for the owner and other buyers.
- Right-clicking your own shop chest opens it normally unless a buyer is inside.
- Placing the pending item back into its original slot cancels the purchase step.
- If the chest item changed, payment is insufficient, inventory is closed, or the server stops mid-purchase, the transaction rolls back or queues pending/recovery delivery for next login.
- Unused payment returns to your inventory on close, or deposits to your SQLite balance if inventory space/session is unavailable.

### Protection

Registered shop chests are protected against:
- block breaking and block/entity explosions;
- piston extend and retract;
- adjacent hoppers and hopper minecarts;
- placing a chest next to a single-chest shop (prevents turning it into a double chest);
- hopper item movement (`InventoryMoveItemEvent`, `InventoryPickupItemEvent`).

### Balance menu

`/smpshop balance` opens a 54-slot storage menu (`45` item slots, navigation arrows in slots `45` and `53`):
- displays accumulated `DIAMOND_ORE` and `DEEPSLATE_DIAMOND_ORE` in stacks up to 64;
- allows withdrawing ore or depositing ore into the balance;
- saves net balance changes atomically to SQLite when the menu closes or the player quits;
- locks external API balance changes (`ApiStatus.BUSY`) while the menu is open.

### Shop list

`/smpshop list` (or `/smpshop list <player>` with `smpshop.admin.list`) opens a paginated menu (`36` or `54` slots via `shop.menu-size`):
- card icon shows the most common non-currency material in the chest (`BARRIER` if empty or missing);
- displays shop ID, owner name, coordinates (`x y z`), total earned `АР`, total items sold, and slot price;
- informational only — does not teleport or open chests remotely.

### Removing a shop

1. Run `/smpshop delete` (own shop) or `/smpshop remove` (any shop, requires `smpshop.admin.remove`).
2. Right-click the target shop chest.

Removing a shop deletes its registration only; chest blocks and contents stay untouched. Removal is blocked while a buyer is inside (`shop-busy`).

## Commands

Main command: `/smpshop` (alias: `/shop`). Player-only except `/smpshop reload`.

| Command | Action | Permission |
| --- | --- | --- |
| `/smpshop` or `/smpshop help` | Show command list | `smpshop.use` |
| `/smpshop set <price>` | Bind price and wait for chest right-click | `smpshop.use` |
| `/smpshop balance` | Open ore balance menu | `smpshop.use`, `smpshop.balance` |
| `/smpshop list` | Open your shop list | `smpshop.use` |
| `/smpshop list <player>` | Open another player's shop list | `smpshop.use`, `smpshop.admin.list` |
| `/smpshop delete` | Remove your shop on next right-click | `smpshop.use` |
| `/smpshop remove` | Remove any shop on next right-click | `smpshop.use`, `smpshop.admin.remove` |
| `/smpshop link tg` | Issue Telegram linking code or referral URL | `smpshop.use`, `smpshop.link` |
| `/smpshop link ds webhook <url>` | Save personal Discord webhook URL | `smpshop.use`, `smpshop.link` |
| `/smpshop reload` | Reload `config.yml` and `messages.yml` | `smpshop.use`, `smpshop.admin.reload` |

## Permissions

| Permission | Purpose | Default |
| --- | --- | --- |
| `smpshop.use` | Base `/smpshop` access | all |
| `smpshop.balance` | Open ore balance menu | all |
| `smpshop.link` | Link Telegram and Discord | all |
| `smpshop.plus` | Use `shop.plus-max-per-player` limit | op |
| `smpshop.admin.list` | View another player's shops | op |
| `smpshop.admin.remove` | Remove any player's shop | op |
| `smpshop.admin.reload` | Run `/smpshop reload` | op |

## Configuration

All files are in `plugins/SmpShop/`.

### `config.yml`

- `shop` — `min-price`, `max-price`, `max-per-player`, `plus-max-per-player`, `menu-size` (`36` or `54`), `allowed-menu-sizes`, `pending-placement-timeout-seconds`
- `storage` — `sqlite-file`, `transaction-timeout-seconds`, `shutdown-timeout-seconds`
- `sounds.seller-sale` / `sounds.buyer-purchase` — `enabled`, `sound`, `volume`, `pitch`
- `notifications` — `offline-enabled`, `join-message-enabled`
- `integrations.http` — `max-concurrent`, `connect-timeout-seconds`, `request-timeout-seconds`, `response-limit-bytes`
- `integrations.telegram` — `enabled`, `bot-token`, `poll-interval-seconds`, `poll-long-poll-seconds`, `link-code-timeout-seconds`, `api-base-url`, `referral-bot`, `user-bot`
- `integrations.discord` — `enabled`

`/smpshop reload` validates YAML before applying changes; invalid configs keep previous active settings.

### `messages.yml`

- Supports `&` legacy color codes and `&#RRGGBB` / `&RRGGBB` hex colors.
- Placeholders use `%name%` syntax.
- Supports `%icon_diamond%` and `%icon_deepslate%` sprite tokens.
- Contains in-game messages, GUI labels, `telegram.*`, `discord.sale`, and `items.*` material translations.

## Sale notifications

### In-game

- Online sellers receive `sale-chat` and `sounds.seller-sale`.
- If `notifications.offline-enabled: true`, earnings for offline sellers are stored in `notifications` and shown on join when `notifications.join-message-enabled: true`.

### Telegram

1. Set `integrations.telegram.enabled: true` and `bot-token`.
2. Optionally set `user-bot` (without `@`) and `referral-bot: true` for clickable `t.me/<bot>?start=<code>` links.
3. Run `/smpshop reload`.
4. In-game run `/smpshop link tg` and follow the link or send `/code <code>` to the bot.

Codes are single-use 16-digit tokens valid for `link-code-timeout-seconds`.

### Discord

1. Set `integrations.discord.enabled: true` and run `/smpshop reload`.
2. In-game run `/smpshop link ds webhook https://discord.com/api/webhooks/...`.

Each player stores one personal webhook; running the command again replaces it.

## Data storage

`plugins/SmpShop/shops.db` (SQLite WAL) stores:
- `shops` — registered shops and sales stats
- `balances` & `balance_ledger` — player ore balances and idempotent API operations
- `transactions` & `item_recovery` — purchase state machine (`PREPARED`, `COMPLETED`, `DELIVERED`, `ROLLED_BACK`) and recovery snapshots
- `notifications` — undelivered offline sale totals
- `social_links` & `link_codes` — Telegram account bindings
- `discord_webhooks` — player Discord webhooks

---

[Overview](README.md) · [API guide](api.md) · [Русское руководство](guideru.md)
