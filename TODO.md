# TODO — HappyGhastPet

Core features are done: GUI management with confirmation dialogs and progress bars, 15-level progression, feeding with daily limits, parking after dismounting, ambient sound reduction, database migrations with backups, `messages.yml` localisation, pet chest inventory, summon deduplication, bStats.

Below is everything still being considered or planned for later.

---

## Architecture: multi-species support

> **Planned — not yet started.**

The codebase currently assumes Happy Ghast everywhere (`instanceof HappyGhast`, Ghast-specific event handling, etc.). Before adding other tameable mobs, the plugin needs a proper species abstraction — doing it in two separate plugins would cause event conflicts on mount/feed/death.

Planned approach:

- Introduce `PetSpecies` / `PetType` (a config section `species:`) with fields: `entity-type`, `rideable`, `flying`, per-species levels / buffs / food / name format / resurrection cost.
- Add a `species` column to the `pets` table via `addColumnIfMissing` (default: `HAPPY_GHAST`).
- Replace all `instanceof HappyGhast` checks with entity-type lookup from the record.
- Extract idle/park behaviour into a strategy interface so flying and ground-based pets behave differently.

---

## Social system (draft — off by default)

Roll out in stages. Full breeding goes last and only if the social features actually get used — otherwise players will farm pets.

- **Stage 1:** Pet gender (`MALE` / `FEMALE` / `UNKNOWN`), no gameplay effect. Existing pets default to `UNKNOWN`.
- **Stage 2:** Friendship between nearby parked pets. Gain is infrequent and capped; stored as a `pet_a` / `pet_b` pair.
- **Stage 3:** Show gender, nearby friends, and friendship level in the GUI.
- **Stage 4:** Mood — rises from varied feeding and parking near friends; small bonus (e.g. +5% feeding XP or cosmetic particles).
- **Stage 5 (optional):** Breeding — both pets at high level, max friendship, long pair cooldown, baby limit per pair, global pet cap enforced.

Config skeleton:

```yaml
social:
  enabled: false
  friendship:
    park-distance: 6.0
    gain-period-seconds: 300
    gain-amount: 1
    daily-limit-per-pair: 5
    max-value: 100
  bonuses:
    high-friendship-threshold: 80
    feeding-exp-multiplier: 1.05

breeding:
  enabled: false
  min-level: 5
  friendship-required: 100
  cooldown-hours: 72
  max-babies-per-pair: 1
```

---

## Smaller improvements

- Cache the level/buff model on reload instead of calling `getConfig()` every tick.
- Pet sorting and pagination in the GUI when the per-player limit exceeds 9.
- Pet chest size that grows with level (careful: never drop items when shrinking).
- Optional flight-time XP with its own daily cap.
- Varied-feeding bonus: 3+ different items in one day grant a small XP multiplier.
- "Highlight nearby pet" button in GUI.
- Periodic auto-save of open pet chest to prevent item loss on crash.
- Register on bstats.org and replace the placeholder `BSTATS_PLUGIN_ID` with the real one.
