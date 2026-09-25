# Magnet

Chunk collectors for Paper 1.21.11. Place a collector and it picks up every item that drops in its chunk, from mob farms, broken blocks, dying animals or anything else. Items are kept as counts, so a million cobblestone costs the same as one. The owner opens it to take items or sell them.

## Using it

1. `/magnet give <player> [amount]` gives the collector item (a lodestone by default).
2. Place it. One collector per chunk.
3. Right click to open. Click an item to take a stack, shift click to take all of it. The buttons at the bottom take everything, sell everything, and turn autosell on or off.
4. A collector must be empty before it can be broken. Breaking it gives the item back.

Explosions and pistons cannot move a collector. Items a player threw, the loot a player drops on death, and fishing catches are left alone.

## The pull animation

When an item drops in the chunk, a small copy of it lifts off, swoops over and shrinks into the collector, so players can see what is collecting. It is only a visual: the item is already in the collector when it starts. Turn it off with `animation.enabled: false`, and change the speed with `animation.duration` (ticks, 16 by default).

It costs one display entity per item in flight, capped by `animation.max-flying` (past that, items go in without it), and nothing when no player is near the chunk. The client does the movement, the server teleports each display twice. The task only runs while something is flying.

## Selling

Needs Vault and an economy plugin. Prices come from ShopGUI+ when it is installed, so what your shop pays is what the collector pays, including the owner's multipliers while they are online. Anything ShopGUI+ has no price for falls back to `prices.yml`. `price-source` in `config.yml` can be set to `SHOPGUIPLUS` or `FILE` to use only one. Items with a name or enchantments sell only if `sell-custom-items` is on. With autosell on, the collector sells every `autosell-interval` seconds and pays the owner.

## Made to be light

- Finding the collector for a dropped item is two hash lookups, and a server with no collectors does nothing on item spawn.
- Items are counts per kind of item, not item stacks.
- Saving happens once a minute, only for collectors that changed, on a separate thread, in one SQLite transaction (`collectors.db`).
- Prices are cached and refreshed once a minute.

## Config

| File | What is in it |
| --- | --- |
| `config.yml` | the block, the item, disabled worlds, blacklist, limits, price source, autosell interval, the animation |
| `messages.yml` | every text and the window, MiniMessage and `&` codes |
| `prices.yml` | fallback sell prices |

## Commands and permissions

| | |
| --- | --- |
| `/magnet give <player> [amount]`, `/collector` | `magnet.admin` (op) |
| `/magnet reload` | `magnet.admin` |
| place and use your own collector | `magnet.use` (everyone) |
| open anyone's collector | `magnet.admin` |

MIT license.
