# SmpShop

SmpShop turns ordinary chests into player-run shops. The seller sets a price for an item stack, the buyer opens the chest, places the payment, and takes the selected item. Payment uses two types of diamond ore.

The plugin is designed for Paper, Folia, and Canvas. Servers from `1.21.11` through `26.2` are supported.

A separate [API guide](api.md) is available for developers.

## Features

- create shops on single and double chests;
- sell any items stored in the chest;
- accept `DIAMOND_ORE` and `DEEPSLATE_DIAMOND_ORE` as one currency;
- show a shop list with the owner, coordinates, price, and sales statistics;
- store a player's ore balance in a separate menu;
- protect shops from breaking, explosions, pistons, hoppers, and item movement;
- notify the seller about sales in-game, on the next login, through Telegram, and through Discord;
- configure commands, messages, sounds, limits, and integrations through YAML files;
- save shops, balances, and unfinished transactions in SQLite.

## Currency

The shop uses two materials:

| Material | Plugin label |
| --- | --- |
| `DIAMOND_ORE` | diamond ore |
| `DEEPSLATE_DIAMOND_ORE` | deepslate diamond ore |

Both materials count as one currency and can be combined in a single payment. Other items do not count as currency.

In plugin text, the currency is displayed as `АР`.

## Quick start for players

### Creating a shop

1. Put the item in a single chest.
2. Run `/smpshop set <price>`.
3. Right-click the chest within the allowed time.

For example:

```text
/smpshop set 12
```

The price applies to the entire item stack in the selected slot. If the slot contains 32 items, the buyer takes all 32 items for the listed price of `12 АР`.

A double chest is registered as one shop. A chest that already belongs to a shop cannot be registered again.

By default, the price can range from `1` to `2304 АР`. A regular player can create up to `10` shops; the `smpshop.plus` permission raises the limit to `50`.

### Buying

1. Right-click another player's shop.
2. Place the payment in the marked menu slots.
3. Click the item you want.
4. Move the received item into your inventory.

During a purchase, the physical chest is temporarily locked for the owner and other buyers. This prevents two players from taking the same item at the same time.

If the item changed, there is not enough inventory space, the payment is insufficient, or the menu is closed before the purchase finishes, the transaction is cancelled. The item and payment are returned to their respective storage.

### Balance

The `/smpshop balance` command opens the balance menu. It lets you:

- view your accumulated diamond and deepslate diamond ore;
- take ore into your inventory;
- return ore to the balance while it is still in the menu;
- switch between balance pages.

The menu accepts only the two ore types listed in the “Currency” section.

### Viewing shops

`/smpshop list` opens a list of your shops. Each shop card shows:

- the shop ID;
- the owner;
- the chest coordinates;
- the price of one item stack;
- the shop's earnings;
- the number of items sold.

The command does not teleport the player or open the chest remotely. It only shows information about registered shops.

### Removing a shop

To remove your shop:

1. Run `/smpshop delete`.
2. Right-click your chest.

The shop registration is removed, but the chest contents remain in place.

## Commands

Main command: `/smpshop`. Alias: `/shop`.

| Command | Action | Permission |
| --- | --- | --- |
| `/smpshop` or `/smpshop help` | show help | `smpshop.use` |
| `/smpshop set <price>` | start creating a shop | `smpshop.use` |
| `/smpshop balance` | open the ore balance | `smpshop.use`, `smpshop.balance` |
| `/smpshop list` | show your shops | `smpshop.use` |
| `/smpshop list <player>` | show another player's shops | `smpshop.use`, `smpshop.admin.list` |
| `/smpshop delete` | start removing your shop | `smpshop.use` |
| `/smpshop remove` | start removing any shop | `smpshop.use`, `smpshop.admin.remove` |
| `/smpshop link tg` | issue a Telegram linking code or link | `smpshop.use`, `smpshop.link` |
| `/smpshop link ds webhook <url>` | save a personal Discord webhook | `smpshop.use`, `smpshop.link` |
| `/smpshop reload` | reload settings and messages | `smpshop.use`, `smpshop.admin.reload` |

All commands are available to players only. Console execution is not supported.

## Permissions

| Permission | Purpose | Default |
| --- | --- | --- |
| `smpshop.use` | use the main command | all players |
| `smpshop.balance` | open the balance | all players |
| `smpshop.link` | link Telegram and Discord | all players |
| `smpshop.plus` | increased shop limit | operators |
| `smpshop.admin.list` | view another player's shops | operators |
| `smpshop.admin.remove` | remove any shop | operators |
| `smpshop.admin.reload` | reload settings and messages | operators |

## Server installation

1. Put `SmpShop-1.0.jar` in the `plugins` folder.
2. Start the server once.
3. Open the created `plugins/SmpShop` folder.
4. Change the required settings in `config.yml` and the text in the message YAML files.
5. Run `/smpshop reload`.

Replacing the JAR requires a full server restart. `/smpshop reload` updates settings, messages, sounds, and integrations, but it does not load new plugin code.

### Compatibility

| Server | Java |
| --- | --- |
| Paper or Folia `1.21.11` | 21 |
| Paper, Folia, or Canvas `26.1+` | 25 |

Spigot and CraftBukkit without the Paper API are not supported.

## Configuration

All settings are stored in `plugins/SmpShop`.

### `config.yml`

| Section | Main settings |
| --- | --- |
| `shop` | price range, shop limit, menu size, and chest placement timeout |
| `storage` | SQLite filename and operation timeout |
| `sounds.seller-sale` | sale sound for the seller |
| `sounds.buyer-purchase` | completed purchase sound for the buyer |
| `notifications` | storing notifications for offline sellers and the join message |
| `integrations.telegram` | Telegram Bot API and the linking method |
| `integrations.discord` | enable notifications through a Discord webhook |

The shop list menu size is set by `shop.menu-size`. Allowed sizes are `36` and `54` slots.

Default limit values:

```yaml
shop:
  min-price: 1
  max-price: 2304
  max-per-player: 10
  plus-max-per-player: 50
  menu-size: 36
  pending-placement-timeout-seconds: 60
```

YAML is validated before it is reloaded. If the new configuration contains an error, the plugin keeps the previous working settings.

### Message files

- `message.yml` contains in-game messages, menu names, hints, and command text;
- `message_other.yml` contains technical error messages;
- `message_tg.yml` contains Telegram bot messages;
- `message_ds.yml` contains Discord messages;
- `logger_locate.yml` contains item names for external notifications.

In `message.yml`, you can change colors using standard Minecraft codes such as `&b` or `&#FFFFFF`.

## Sale notifications

### In-game

An online seller receives a sale message and sound. If the seller was offline, the plugin can save the sale amount and show it on the next login.

This is configured in `config.yml`:

```yaml
notifications:
  offline-enabled: true
  join-message-enabled: true
```

The seller's balance is saved regardless of whether the notification was delivered.

### Telegram

1. Create a bot through BotFather and obtain a token.
2. Enable `integrations.telegram.enabled`.
3. Set `bot-token`.
4. Set the `user-bot` name without the `@` symbol if you want to issue a bot link.
5. Run `/smpshop reload`.
6. Run `/smpshop link tg` in-game.
7. Follow the link or send the received code to the bot.

The bot accepts `/start <code>` and `/code <code>`. The code is single-use and expires after a limited time. Telegram receives sale notifications only.

If `referral-bot: true`, the player receives a link in the form `https://t.me/<bot>?start=<code>`. If the setting is disabled, the plugin issues a single-use code for `/code`.

### Discord

1. Enable `integrations.discord.enabled`.
2. Run in-game:

```text
/smpshop link ds webhook <url>
```

3. After the webhook is saved, sale notifications for your shops are sent to this channel.

A webhook is stored separately for each player. Running the command again replaces the saved address.

Do not publish the Telegram token or Discord webhook in chat, Git, or public documentation.

## Data storage

By default, the plugin uses `plugins/SmpShop/shops.db`. It stores:

- registered shops and their statistics;
- player balances;
- transaction history and state;
- pending notifications;
- Telegram links;
- player Discord webhooks.

Removing a shop does not remove items from the chest. The chest remains a normal in-game storage.

If a player leaves during a purchase or the server stops at an unsuitable moment, the plugin restores the unfinished transaction on the next login and should not charge the payment again.

## Limitations

- the currency is fixed and cannot be changed through YAML;
- the price is set separately for each shop;
- each shop serves one buyer at a time;
- the shop list does not open the chest remotely;
- deleting a world or its world file does not restore chest contents from SQLite;
- `/smpshop link max` appears in an old help message, but the current version has no working MAX integration.

## License

[MIT](LICENSE)
