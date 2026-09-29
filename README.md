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

Russian step-by-step guide (install, PostgreSQL/MariaDB/SQLite, network setup, troubleshooting): [docs/install_ru.md](docs/install_ru.md).

- Put viaLogium in your `mods` folder together with Fabric API and `fabric-language-kotlin`.
- viaPanel 3.1.0 or newer is required (panel config editing and the server name for shared databases).
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
connectionTimeout = 10000
maxLifetime = 1800000
```

3) Restart server.

Tips:
- If MariaDB runs in Docker on another host, open port `3306` and use `<host>:3306/vialogium`.
- If connect fails, temporarily switch `database = "SQLITE"` to start server and validate other config.

## Several servers, one database (Velocity network)

All backends behind a proxy can log into the same PostgreSQL/MariaDB/MySQL database. Every action is stored with the server it happened on.

- The server name comes from viaPanel's `server_id` (`config/viaPanel/viaPanel.toml`). To override it for viaLogium only, set `[database] serverId` in `config/vialogium.toml`. Use the same names as in `velocity.toml`.
- Point every backend at the same `[database_extensions]` URL.
- `/vl search`, `/vl near` and inspect show only this server by default. Add `server:<id>` for another server, `server:!<id>` to exclude one, or `server:all` for the whole network. Results from other servers show the server name before the coordinates.
- `/vl rollback`, `/vl restore` and `/vl preview` only change this server's worlds. With `server:<another server>` they are refused, so run them on the server where the actions happened.
- Auto-purge (`autoPurgeDays`) deletes only this server's rows, so each server can keep logs for a different time.
- Upgrading a database from before 1.1.0 adds the `server` column and assigns the existing rows to the first server that starts with the new version. Start the server that owns the old database first.

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
