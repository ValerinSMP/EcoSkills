# EcoSkills observation API — schema 1 / 2

Read-only API owned by EcoSkills. Canonical source: personal `projects/eco-suite/EcoSkills` and `projects/eco-suite/eco`. This document describes the implementation, not a promise of gameplay or 100-player performance verification.

## Public facade

`com.willfp.ecoskills.api.EcoSkillsAPI` preserves all existing public methods. Additions:

```java
PlayerObservation getCachedObservation(UUID uuid);
PlayerObservation getCachedObservation(UUID uuid, ObservationRequest request);
Map<String, Object> getCachedSnapshot(Player player);
Map<String, Object> getCachedSnapshot(UUID uuid);
CompletionStage<PlayerObservation> getStoredObservationAsync(UUID uuid);
CompletionStage<PlayerObservation> getStoredObservationAsync(UUID uuid, ObservationRequest request);
CompletionStage<Map<UUID, PlayerObservation>> getStoredObservationsAsync(Set<UUID> uuids);
CompletionStage<Map<UUID, PlayerObservation>> getStoredObservationsAsync(Set<UUID> uuids, ObservationRequest request);
EcoSkillsDefinitions getObservationDefinitions();
ObservationCapabilities getObservationCapabilities();
```

DTOs live under `com.willfp.ecoskills.api.observation`; use `asMap()` to obtain deeply immutable JSON-compatible maps/lists/scalars. Serialize nulls explicitly. Do not serialize the DTO wrapper as the wire schema. UUID batch maps are immutable, and must be indexed by UUID before serializing a player observation.

`ObservationRequest.all()` selects every family; `persistentOnly()` selects SKILLS, STATS, EFFECTS and PREFERENCES. Its constructor accepts an immutable copy of a closed set of `Section` enum values. No SQL, storage keys, expressions, table names or arbitrary descriptors are accepted. A subset of IDs and modifier pagination are not implemented; explicit truncation is used instead.

## Cache-only observation

Main thread required for both cache APIs, including the Player overload. Calls from another thread fail immediately before player/profile lookup. No `OfflinePlayer.profile`, `Profile.read`, handler read, storage request, defaults, key registration, buckets, holder flush or cache refresh occurs.

The eco API adds optional `Eco.getProfileObservationAccess()`, `Profile.peekCachedValues(Set)` and `DefaultMap.getIfPresent(Object)`. Default implementations are conservative. Native profile lookup only uses the existing loaded map; profile peeks copy existing entries. Framework helpers are not a web authorization boundary; EcoSkills exports only its closed set of existing owned keys.

The legacy projection exactly preserves schemaVersion 1: UUID, observedAt, status, reason, skills `{id,level,xpWithinLevel,requiredXp,maxLevel}` and stats `{id,base,modified}`. Absence of a loaded profile returns unavailable with empty lists. Partial fields are null, never manufactured zeroes. Version 2 is a separate contract and must not be passed through the old vNetwork adapter with its 16-entry map/depth-six limits.

Statistics use the native pure `modifiedStatValue` calculation on the same cached base/modifier inputs, preserving ADD first and MULTIPLY second, original order, full Double precision and legacy Int conversion. Offline/sessionless effective values, bonus and modifiers remain unavailable. A loaded zero can be a native default or an unsaved write: cache persistence evidence remains unknown.

The normal MagicMap setter adds only an observation identity marker. It does not change clamping, resources or events. Weak Player identity markers are cleared on login/quit/disable; quantities are exposed only after a normal write in the current session. A new connection cannot inherit the previous connection's observed resource. Getters never mark a session or initialize resources.

## Storage observation

Any caller thread; no waiting for SQL. Separate worker executor, never the native serializer/write executor. Catalog/routing/registered keys and the initialized handler capability are captured on main during normal load/reload. Workers do not use Bukkit, player/profile loaders or mutable plugin configuration. Results never enter active profile caches.

Implemented backend: current MySQL scalar tables, using its existing pool. Three parameterized SELECT groups for INT, DOUBLE and BOOLEAN, shared by an entire batch. Read-only transaction with rollback and connection-state restoration. No DDL/DML/defaults/upserts/reconciliation. The MySQL capability requires at least three existing pool connections to limit observation to two and leave capacity for normal work; pool settings are not changed. This is a budget, not a dedicated writer connection or priority guarantee.

Local/YAML, MariaDB, MongoDB and legacy handlers are unsupported in this increment. Individual local routes remain unavailable while supported shared routes can still return data. A missing row is key_not_stored; SQL errors are storage_error; unsupported routes are unsupported_backend. A UUID is not evidence that a player exists or is globally offline.

Limits: two concurrent reads, queue 64, batch 1–16 distinct non-null UUIDs, total deadline two seconds including queue; query timeout two seconds. Identical UUID-set/selection/catalog-generation requests coalesce. Cancellation affects a caller view; other subscribers remain valid. The final subscriber cancellation cancels queued work/deadlines or requests JDBC cancellation. A running, timed-out read keeps its actual worker until it exits, including a pool checkout that exceeds the public deadline. No fake capacity release or unbounded retry.

Completion normally occurs on a read/deadline thread. Immediate rejection can complete on the caller; normal CompletionStage callbacks attached after completion can also run there. Use an explicit async executor for network work, and return to main before Bukkit access. Do not join/get on main. Disable closes admission and resolves outstanding work; reload publishes a new generation, and completed old reads are rejected as registry_changed.

`requestedAt` includes queue admission; `readStartedAt`/`readCompletedAt` describe the worker reading window, not field modification timestamps. Missing timestamps remain null. `observedAt` is observation time, not last save/login/XP. The read-only snapshot does not make separately persisted level/XP writes logically atomic, and does not provide instant cross-node state.

## Families and availability

| Family | Cache | Storage |
|---|---|---|
| Skills/XP | Present level/XP, max, threshold, progress/percent/to-next, known aggregates | Owned persisted level/XP; same safe definition-derived values |
| Stats | Cached base, native effective Double, bonus, legacy Int, modifier count | Persisted base only; effective/bonus require session |
| EcoSkills effects | Owned passive progression levels | Their owned INT keys |
| Preferences | Existing actionbar/sound booleans | Existing BOOLEAN rows, no default fallback |
| Magic | Current-session amount; native Int limit and ceil base regen when all pure inputs exist | Resource/effective values are not persisted |
| Modifiers | Immutable UUID/stat/operation/Double/order; 512 maximum, total/returned/truncated | Not persisted |
| Rankings | disabled or not_materialized; no loader | Not persisted; no global ranking query |
| Context | Own world-restriction flag; session presence is node-local | Session context not retained in storage |
| Presentation | not_materialized for sessionless/unsafe retained display state; bossbar ownership not_retained | Unavailable |
| Historical | unsupported; no new history | Unsupported |

Catalog definitions publish immutable IDs, public names, units, maxima, bounded requirements and safe expression capabilities/inputs during load/reload. Getters only read the published catalog. Only a conservative deterministic subset of the native parser is prepared independently: no PlaceholderAPI, global result/compile cache, arbitrary external inputs or randomness. Unsupported expressions/unknown inputs remain unavailable. Native mechanics and YAML expression evaluation are not altered.

Each observed field carries value/status/reason/source. Root source is CACHE or STORAGE; value source is CACHE, SESSION, STORED, DEFINITION or DERIVED_FROM_OBSERVED_INPUTS. Availability is available/partial/unavailable. At max, thresholds/progress are null/at_max_level; stored extra XP is preserved. Session-required fields do not make persistentOnly incomplete when its persistent fields are known. No lastSavedAt, lifetime earned XP or reward history is invented.

Limits also cover 128 skills/stats/effects, 32 resources, public IDs <=64, names <=256, 512 collection entries, depth 12 and a conservative UTF-8 JSON budget of 256 KiB (legacy 64 KiB). Overflow is explicit limit_exceeded; IDs are not cut into collisions. These are initial budgets, not measured throughput guarantees for 60–100 users or 30–40 active PvP players.

## Delivery and preservation

Build exclusively in personal through its limited executor, declared module toolchains and Java 21 bytecode. Existing runtime divergence must be preserved: specifically PersistentDataHandler's installed submit/Future.get implementation, existing XP receipts/events/callbacks and unrelated source candidates. A full rebuild is not interchangeable with the reviewed runtime.

Runtime delivery is assembled from exact installed baselines plus the approved additive methods/new observation classes. Only two existing EcoSkills method bodies add hooks: constructor installs observation lifecycle and MagicMap.set marks a normal write. Their previous operations remain identical. All other entries/methods/hierarchy/fields and consumer ABI are compared. Tools under `tools/observation-runtime/` reproduce the approval boundary; run their compilation/assembly only through personal's limited executor with the recorded baselines.

The compilation API artifact contains the exact EcoSkills runtime class ABI, no plugin descriptor. Declare compileOnly/provided; never install or shade it. No vNetwork source is modified by this task. Runtime installation/activation is Test-only with HDD backups and hashes. Production requires a separate authorization and delivery.
