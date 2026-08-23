# Changelog

## [Unreleased] - 1.0.0 (Minecraft 26.2 port)

### Changed
- Ported to MC **26.2** following upstream Ledger ports (bf8192b, 8c6cfa4), adapted across our renamed tree: 41 files (+946/-579).
- Toolchain: official Mojang mappings (MC unobfuscated), Loom 1.17.x plugin id net.fabricmc.fabric-loom, Gradle 9.5.1, Java toolchain 25, Loader 0.19.3, Fabric API 0.152.1+26.2, Kotlin 2.3.10.
- viapanel compile dep moved to `lib/mc26/viapanel-3.0.0+mc26.2*.jar` (1.21.11 jars in lib/ root untouched).
- Mixin adaptations: deobfuscation of lambda/method_ targets, renamed vanilla classes (LilyPad, Farmland, Speleothem, CauldronInteractions...), ContainerInput clicks, sign WrapOperation signature, Fabric API playS2C/serverboundPlay renames, ShowItem ItemStackTemplate hover.
- testmod fixed (ClientCommandManager removal workaround) - broken even upstream.

### Note
- Mixin injection targets compile-checked; runtime smoke test on a real 26.2 server recommended before production use.

## [Unreleased] - 0.4.0 (upstream sync)

Ported from upstream Ledger (commits between fork point 1.3.18 and 1.3.23):

### Fixed
- MySQL: action rows whose `extra_data` exceeds the TEXT column limit are skipped with a warn instead of failing the whole batch (#371).
- Search result click actions now execute on the server thread - custom `vIALOGIum:page-change` / `teleport` click events are dispatched through a `ServerCommonPacketListenerImpl` mixin injected after `ensureRunningOnSameThread` (#372, #361).
- Teleport click action guards against a null server level before warping.

### Added
- Declarative `actions(time)` index plus xyz index for fresh schemas on all backends; raw-exec migrations now gated behind new `[database] updateSchema = false` option (#369, #375).
- Player names resolved from the local database cache (bimap keyed by DB player id) instead of hitting the Mojang cache per query (#357).
- Item insert/remove tracking for Chiseled Bookshelf (`blocks.ChiseledBookShelfBlockMixin`) (#368).

### Skipped (already covered better in this fork)
- Upstream #355 (@global range NPE): our SearchParamArgument handles global ranges via explicit GLOBAL bounds - NPE unreachable.

## [Unreleased] - 0.3.0

### Added
- **Piston movement logging** (opt-in): new `[actions] logPistons = false` config key (`ActionsSpec.kt`, `vialogium.toml`). When enabled, `PistonBaseBlockMixin` snapshots the affected block ray at `moveBlocks` HEAD and `PistonLog` emits a deferred diff 4 ticks later (after `moving_piston` placeholders settle), producing standard `block-change` / `block-break` actions with source `piston`.
- Flood safety by design: Java-edition pistons are redstone-only (no player attribution exists), so piston actions carry no player profile and are the FIRST category dropped by CRITICAL/EMERGENCY queue modes - a clock flood self-throttles instead of flooding the DB. Per-world/per-source blacklists apply as usual (`worldBlacklist`, `sourceBlacklist += ["piston"]`).
- Mixin registered: `PistonBaseBlockMixin` (`vialogium.mixins.json`).

### Notes
- Piston logging was a documented systematic gap: pistons previously produced zero log entries.

## [Unreleased] - 0.2.23

### Fixed
- **Silent logging death (root cause of "some blocks/containers randomly not logged")**: the DB flush coroutine in `ActionQueueService.prepareNextBatch()` ran `while(true)` with no error handling - a single DB failure lasting longer than the Exposed retry budget (~12 s: 5 attempts, backoff 200-3000 ms) killed the coroutine permanently. All logging silently stopped until server restart while the queue grew to `maxQueueSize` and then dropped everything (warn every 5000 drops, easy to miss). The flush loop now survives any write failure: failed batch is held in memory and retried up to `MAX_BATCH_RETRIES` (5) with a 5 s pause between attempts; only after exhausting retries the batch is dropped with a loud error listing affected identifiers (`ActionQueueService.kt:drainBatch`).
- **Shutdown drain hardening**: `drainAll()` also drains a held retry batch and no longer aborts mid-way on an exception; per-batch errors are logged, remaining queue keeps draining.
- **Save-off visibility**: `DatabaseManager.execute()` logged nothing while vanilla `/save-off` paused all DB writes (backup windows). Now warns once per pause episode: "DB writes paused: vanilla save-off is active" (`DatabaseManager.kt:execute`).
- **Status visibility**: `/vl status` shows a new `Logging:` line - `OK` (green) or `DEGRADED - DB writes failing, retrying` (red), backed by `ActionQueueService.healthy` (`StatusCommand.kt`, lang keys `text.vialogium.status.logging` en/ru).

### Known gaps (documented, not fixed by design decision)
- Pistons do not generate actions at all (no mixins cover piston block movement).
- Fluid source creation is disabled (commented out as spammy, `FlowingFluidMixin.java:14-18`).
- Under queue pressure CRITICAL/EMERGENCY modes intentionally drop non-player block actions and sample explosions 20:1 / 100:1 (configurable via `[database]` spec).

## [Unreleased]

### Added
- _index/ documentation set: summary.md, config.md, refs.md, decisions.md (tree.txt и meta.json не изменялись).

### Build
- Version bumped to 0.2.22.

### Fixed
- viaPanel config save: `ViaLogiumPanelConfig.save()` now persists changes to the TOML file on disk via `config.to.toml.file()`. Previously only updated the in-memory config, causing all viaPanel edits (e.g. `autoPurgeDays`) to reset to defaults on server restart (`ViaLogiumPanelConfig.kt:199`).
- Fire rollback: replaced two `@Inject` mixins in `FireBlockMixin` with `@WrapOperation`. `removeBlock` now fires `BlockBreakCallback` (correct), `setBlock` now fires `BlockChangeCallback` instead of incorrectly firing `BlockBreakCallback`. Eliminates double-logging from redundant injectors on `checkBurnOut`.
- Chest rollback (two-pass): removed immediate chest-type fixing from `BlockChangeActionType.rollback()`. Rollback now only restores blocks; a new second pass in `RollbackCommand` iterates all restored chest positions and reconnects double chests using the actual world state (neighbor exists after first pass). Fixes double chests becoming `SINGLE` on rollback.
- Chest type direction: `ChestType.LEFT`/`RIGHT` assignment in second pass was inverted. Neighbor at `facing.clockWise` → this chest is `LEFT`, neighbor is `RIGHT` (and vice versa). Fix applied in `RollbackCommand.kt:217`.
- Fire block entity capture: `onSetBlock` in `FireBlockMixin` now captures the old `BlockEntity` *before* `setBlock` is called, preserving container contents for rollback.

### Build
- Corrupted JAR (`ZipException: invalid LOC header`): caused by Gradle daemon OOM during `clean build` (Java 25 on 7GB host with `-Xmx2G`). Rebuilt with lower memory pressure; fresh JAR is clean.
