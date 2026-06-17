# HappyGhastPet

A [Paper](https://papermc.io/) plugin that lets players tame and upgrade **Happy Ghasts** as persistent pets — complete with a levelling system, inventory GUI, rideable mechanics, and SQLite storage.

> Requires **Paper 1.21.6+**. Happy Ghast was added in Minecraft 1.21.6; Spigot is not supported.

---

## Features

- **Taming** — claim a wild Happy Ghast as your pet with a single command
- **Levelling** — 15 levels unlocked by feeding; each level raises speed, health, and unlocks passenger potion effects (Slow Falling → Speed I → Fire Resistance → Regeneration → Speed II)
- **Rideable** — mount and fly; speed scales with level and stays within anti-cheat-safe limits
- **GUI management** — per-pet panel to feed, rename, summon, resurrect, trust players, or release the pet
- **Pet inventory** — a built-in chest (27 slots by default) opened via Shift+Right-click
- **Resurrection** — revive a dead pet using a soul campfire ritual
- **Trust system** — share ride access with specific players
- **Parking modes** — configurable idle behaviour after dismounting (`PARKED` / `FREEZE` / `OFF`)
- **Persistent storage** — SQLite with automatic schema migrations and pre-migration backups
- **Full localisation** — all player-facing messages live in `messages.yml`
- **bStats** — optional anonymous usage metrics

---

## Requirements

| | Version |
|---|---|
| Java | 21+ |
| Paper (or compatible fork) | 1.21.6+ |

---

## Installation

1. Download the latest JAR from [Releases](../../releases).
2. Place it in your server's `plugins/` folder.
3. Restart the server — `config.yml` and `messages.yml` are created automatically.
4. (Optional) Edit the generated config files and run `/gh admin reload`.

---

## Commands

Base command: `/ghastpet` (aliases: `/gh`, `/gp`, `/hgp`)

### Player commands — permission `happyghastpet.use` (granted to everyone by default)

| Command | Description |
|---|---|
| `/gh tame` | Tame the Happy Ghast you are looking at |
| `/gh menu` | Open the pet management GUI |
| `/gh name <name>` | Rename your targeted pet |
| `/gh trust <player>` | Allow another player to ride your targeted pet |
| `/gh untrust <player>` | Revoke ride access |
| `/gh help` | Show a quick guide to commands and pet mechanics |

### Admin commands — permission `happyghastpet.admin` (OP by default)

| Command | Description |
|---|---|
| `/gh admin give <player>` | Spawn a tamed Ghast and assign it to an online player |
| `/gh admin setlevel <player> <1–15>` | Set the level of that player's pet |
| `/gh admin remove <pet_id>` | Remove a pet record by its ID |
| `/gh admin reload` | Reload config and messages without restart |

### Permissions

| Permission | Default | Description |
|---|---|---|
| `happyghastpet.use` | everyone | Player pet commands |
| `happyghastpet.admin` | OP | Admin pet commands (includes `use`) |
| `happyghastpet.colorname` | OP | Color codes in custom pet names |

---

## Configuration

All settings live in `plugins/HappyGhastPet/config.yml`. Key options:

```yaml
max-pets-per-player: 2        # Dead pets still occupy a slot until resurrected or removed

experience:
  daily-limit: 150            # XP cap from feeding per calendar day
  auto-upgrade: false         # true = pets level up automatically once they have enough XP

summon:
  cooldown-seconds: 7200      # 2-hour cooldown between summons
  same-world-only: true       # Prevents pulling a pet across dimensions

idle:
  mode: PARKED                # OFF | PARKED | FREEZE

inventory:
  enabled: true
  size: 27                    # Must be a multiple of 9 (max 54)
```

Feeding items, level thresholds, speed multipliers, passenger effects, and resurrection materials are all configurable. The file is fully annotated.

### Feeding items (defaults)

| Item | XP | Daily limit |
|---|---|---|
| Snowball | 1 | 16 |
| Sweet Berries | 3 | 16 |
| Glow Berries / Glowstone Dust | 6 | 12 |
| Magma Cream | 8 | 8 |
| Honey Bottle / Blaze Powder | 10 | 8 |
| Phantom Membrane / Ender Pearl | 15 | 6 |
| Ghast Tear | 25 | 4 |
| Nether Star | 100 | 1 |

### Level progression (defaults)

| Level | XP to reach | Speed | Passenger effect |
|---|---|---|---|
| 1 | — | ×1.00 | — |
| 2 | 20 | ×1.05 | — |
| 3 | 30 | ×1.10 | — |
| 4 | 45 | ×1.15 | Slow Falling |
| 5–6 | 60 / 80 | ×1.20–1.25 | — |
| 7 | 110 | ×1.30 | + Speed I |
| 8 | 140 | ×1.40 | — |
| 9 | 170 | ×1.45 | + Fire Resistance |
| 10 | 200 | ×1.55 | — |
| 11 | 240 | ×1.60 | + Regeneration I |
| 12–14 | 280–380 | ×1.65–1.75 | — |
| 15 | 450 | ×1.80 | Speed → II |

Effects are cumulative: a level-15 pet grants Slow Falling, Speed II, Fire Resistance, and Regeneration to its rider simultaneously.

---

## Building from source

```bash
git clone https://github.com/Nevarielle/HappyGhastPet.git
cd HappyGhastPet
./gradlew build          # Linux / macOS
gradlew.bat build        # Windows
```

Output: `build/libs/HappyGhastPet-1.0.0.jar`

`sqlite-jdbc` is intentionally **not** bundled — it is fetched at server startup via `plugin.yml` libraries, so you don't need to manage it manually.

---

## Roadmap

See [TODO.md](TODO.md) for the full list. Highlights:

- **Multi-species architecture** — refactor `PetSpecies`/`PetType` model so other mobs can be added inside the same plugin
- **Social system** — pet friendship, mood, and optional breeding (off by default)
- **GUI improvements** — sorting, pagination, level-scaling inventory size

---

## License

MIT — see [LICENSE](LICENSE).
