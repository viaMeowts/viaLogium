## viaLogium

viaLogium is a server-side world change logger for Fabric with search, inspect, rollback/restore, preview, and persistent database history.

## Based on Ledger

viaLogium started as a deep fork of [Ledger](https://github.com/QuiltServerTools/Ledger) 1.3.18 by QuiltServerTools - the original logging engine, callback/mixin architecture and command framework are built on that codebase. License is carried over unchanged ([LGPL-3.0](./LICENSE.md)). Upstream history is preserved in this repository's git log below the fork baseline commit.

The divergence is substantial: at the 0.3.0 baseline the tree differed from upstream by **351 files (+195k lines)** - 189 files moved in the rebrand, 86 new modules, plus this fork's own fixes and features since.

### What viaLogium changes and adds

**Identity**
- Full rebrand: mod id `vialogium`, namespace `com.viameowts.vialogium`, `/vl` command root, own icon and branding

**Database pipeline (rewritten)**
- `ActionQueueService` with NORMAL / CRITICAL / EMERGENCY queue-pressure modes, adaptive batch size/delay tuning and explicit per-category drop policies (upstream has none of this)
- Resilient flush: transient DB failures no longer kill logging silently - failed batches retry with a bounded budget, `/vl status` shows live `Logging: OK / DEGRADED` health
- Save-off (`/save-off`) write pauses are logged instead of being invisible
- Rollback execution guard preventing feedback loops during restore operations

**Logging coverage**
- Action types grown from 14 to 21: totem pops, villager trades, entity mount/dismount, player join/leave, improved item pick-up/drop paths
- Mixin set extended for current vanilla content (copper golem, shelves, decorated pots, minecart hopper transfers, silverfish infestation, ...) and opt-in piston movement logging (`[actions] logPistons`)

**Platform integration**
- [viaPanel](https://github.com/viaMeowts/viapanel) integration: full in-game config UI (bilingual provider), no manual TOML editing required
- Client-mod networking layer for search/inspect UX
- Public extension API for third-party database providers and actions

**Documentation & localization**
- Rewritten MkDocs documentation, Russian Discord user guides
- 11 language files including Belarusian (Latin and Cyrillic)

## Install

- Put viaLogium in your `mods` folder together with Fabric API and `fabric-language-kotlin`.
- For panel config editing, put a `viapanel-*.jar` build in the same `mods` folder.
- On first run, config is generated at `config/vialogium.toml`.

## Database backends

viaLogium initializes with the backend from `[database_extensions]` and fails startup on DB init errors.

Supported values:
- `SQLITE`
- `MYSQL`
- `MARIADB`
- `POSTGRESQL`
- `H2`

Performance note:
- For one server with local storage, tuned `SQLITE` is usually very good and simplest to operate.
- `MARIADB` is preferable when you need external DB hosting, larger concurrent workloads, or centralized DB management.

## MariaDB quick start (5 minutes)

1) Create database and user in MariaDB:

```sql
CREATE DATABASE vialogium CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'vialogium'@'%' IDENTIFIED BY 'change_me';
GRANT ALL PRIVILEGES ON vialogium.* TO 'vialogium'@'%';
FLUSH PRIVILEGES;
```

2) In `config/vialogium.toml`, set:

```toml
[database_extensions]
database = "MARIADB"
url = "127.0.0.1:3306/vialogium"
username = "vialogium"
password = "change_me"
properties = {}
maxPoolSize = 10
connectionTimeout = 60000
maxLifetime = 1800000
```

3) Restart server.

Tips:
- If MariaDB runs in Docker on another host, open port `3306` and use `<host>:3306/vialogium`.
- If connect fails, temporarily switch `database = "SQLITE"` to start server and validate other config.

## Commands (only `/vl`)

Root command:
- `/vl`

Legacy aliases are removed.
- Removed: `/vlg`, `/lg`, `/vialogium` and short subcommand aliases (`i`, `s`, `n`, `pg`, `rb`, `pv`).

Use full subcommands:
- `/vl inspect [on|off|<x y z>]`
- `/vl search <params>`
- `/vl near`
- `/vl page <number>`
- `/vl rollback <params>`
- `/vl restore <params>`
- `/vl preview rollback <params>`
- `/vl preview restore <params>`
- `/vl preview apply`
- `/vl preview cancel`
- `/vl purge <params>`
- `/vl purge --confirm <key>`
- `/vl status`
- `/vl tp <world> <x> <y> <z>`
- `/vl player <profile>`

### About `/vl near`

- `/vl near` performs a search around your current position.
- Radius is configured by `search.nearRadius` in `config/vialogium.toml`.
- After running it, use `/vl page <number>` to navigate result pages.

## Permissions

Permission prefix:
- `vialogium.commands.*`

Examples:
- `vialogium.commands.root`
- `vialogium.commands.search`
- `vialogium.commands.rollback`
- `vialogium.commands.purge`

Networking checks use:
- `vialogium.networking`

## viaPanel

viaLogium registers a viaPanel provider with editable sections:
- Search
- Database Queue
- Networking
- Colors

The panel supports reload and writes config changes back to runtime values.
