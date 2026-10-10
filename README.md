# Magnet

Chunk collectors for Paper 1.21.11. Place a collector and it picks up every item that drops in its chunk, from mob farms, broken blocks, dying animals or anything else. Items are kept as counts, so a million cobblestone costs the same as one. The owner opens it to take items or sell them.

## Using it

1. `/magnet give <player> [amount]` gives the collector item (a lodestone by default).
2. Place it. One collector per chunk.
3. Right click to open. Click an item to take a stack, shift click to take all of it. The buttons at the bottom take everything, sell everything, and turn autosell on or off.
4. A collector must be empty before it can be broken. Breaking it gives the item back.

Explosions, pistons and block-changing mobs such as the wither cannot move or break a collector. If its block is removed some other way (WorldEdit, `/setblock`, a plugin that breaks blocks without an event), the collector never keeps swallowing items it cannot give back: when it holds items the block is put back (the console says so), and an empty one is forgotten. `/magnet list` shows your collectors and where they are. Items a player threw with Q, the loot a player drops on death, and fishing catches are left alone. To collect thrown items too (handy for testing with your own drops), set `collect-thrown-items: true`. Items already lying in the chunk when the collector is placed are not picked up, only new drops. Item stackers like RoseStacker are fine: the collector takes the item before they merge it.

## The pull animation

Items that drop in the chunk play their normal drop (the pop out of a broken block, a mob's drop, a throw), lie on the ground for a few seconds, then drift up toward the collector, circle it once and shrink into it, so players can see what is collecting.

It is only a visual. The item is counted the moment it drops, and the copy on the ground carries a tag so stacking plugins never merge it into real items. The copy on the ground cannot be picked up by players, mobs or hoppers, cannot merge with other items and is never saved, so nothing can be stolen or duplicated, and a restart or chunk unload cannot lose anything.

In `config.yml`: `animation.enabled: false` turns it off (items then go in instantly), `animation.ground-time` is the seconds an item lies there after landing (3 by default), `animation.max-flying` caps how many are animated at once, and `animation.sound` turns the pickup sound off.

Cost: one real item and later one display entity per animated item, and the server sends about one teleport every three ticks for each display. Past `max-flying`, and when no player is near the chunk, items go in without the animation. The task only runs while something is animating.

## The hologram

Each collector has a text above it that shows what it holds. It is a text display, so no hologram plugin is needed. It only exists while the chunk is loaded, is never saved, and is only rewritten when the contents change.

Everything is in the `hologram` section of `config.yml`: the lines (MiniMessage and `&` codes, with `<items>`, `<types>`, `<owner>` and `<worth>`), the height, the size, the shadow, the background (`transparent` by default, or `#AARRGGBB`), whether it can be seen through blocks, how far away it can be seen, and `enabled: false` to turn it off. `/magnet reload` applies changes.

## Selling

Needs Vault and an economy plugin. If the economy does not accept the payment, nothing is sold and the items stay in the collector. Prices come from ShopGUI+ when it is installed, so what your shop pays is what the collector pays, including the owner's multipliers while they are online. Anything ShopGUI+ has no price for falls back to `prices.yml`. `price-source` in `config.yml` can be set to `SHOPGUIPLUS` or `FILE` to use only one. Items with a name or enchantments sell only if `sell-custom-items` is on. With autosell on, the collector sells every `autosell-interval` seconds and pays the owner.

## Made to be light

- Finding the collector for a dropped item is two hash lookups, and a server with no collectors does nothing on item spawn.
- Items are counts per kind of item, not item stacks.
- Saving happens once a minute and on every world save, only for collectors that changed, on a separate thread, in one SQLite transaction (`collectors.db`). A collector is also written right after items are taken from it or sold, so a crash cannot bring them back, and a write that failed is tried again.
- Prices are cached and refreshed once a minute.

## Config

| File | What is in it |
| --- | --- |
| `config.yml` | the block, the item, disabled worlds, blacklist, limits, price source, autosell interval, the animation, the hologram |
| `messages.yml` | every text and the window, MiniMessage and `&` codes |
| `prices.yml` | fallback sell prices |

## Commands and permissions

| | |
| --- | --- |
| `/magnet give <player> [amount]`, `/collector` | `magnet.admin` (op) |
| `/magnet list` (your own), `/magnet list <player\|all>` | `magnet.use`, naming a player or `all` needs `magnet.admin` |
| `/magnet reload` | `magnet.admin` |
| place and use your own collector | `magnet.use` (everyone) |
| open anyone's collector | `magnet.admin` |

## Keeping your files safe

- `config.yml` and `messages.yml` start with a `config-version` / `lang-version` number. After an update, new settings are added to your files with their comments, and nothing you changed is touched. The old file is kept next to it as `<name>.<date>.bak` (the newest 5). A setting is only removed when the changelog says so.
- A value with a mistake (a negative time, an item that does not exist, text where a number belongs) is named in the console by file and key. On a reload, the settings in use stay as they were.
- Files are written to a temporary file and moved into place, with the previous version kept as `.bak`. A file that cannot be read is restored from its `.bak`, and the unreadable one is kept as `.broken-<time>`.
- A file or database that was made by a newer version of the plugin is left alone and a warning is logged.
- `collectors.db` is a SQLite database in WAL mode. It is checked when the plugin starts, a copy is made on a schedule (`backup.interval-hours`, `backup.keep` in `config.yml`, in the `backups` folder) and every copy is opened and checked before older ones are removed. A damaged database is replaced by the newest copy that checks out, or, where nothing may be lost, the plugin stays off and the file is left untouched.
- The backup is a consistent snapshot, not a copy of the open file.
- `/magnet doctor` shows the health of the files, versions, last backup and recent save failures (no player data). `/magnet backup now` makes a checked backup right away. Both need the admin permission.

## Telemetry

On startup Magnet sends a small anonymous beacon (plugin name/version, server software/version, online/max player counts, and a random ID with no player data) so we know which versions are in use. Turn it off with `metrics.enabled: false` in `config.yml`. The random ID is kept as `server-id` in `collectors.db` (older versions kept it in a `.server-id` file, which is moved over unchanged). The address and the interval are fixed in the plugin and are not settings.

## License

See `LICENSE`: free to run on your own servers, not for redistribution or resale.
