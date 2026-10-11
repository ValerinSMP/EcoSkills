# Decimal effective stats — Test delivery 2026-10-11

Canonical source: personal `/root/development/projects/eco-suite/EcoSkills`.
Upstream Kotlin/toolchain, persisted Int bases, progression and final XP bridge are preserved.

Public additions to `com.willfp.ecoskills.api.EcoSkillsAPI`:
* `getStatValue(OfflinePlayer, Stat): double`
* `getBonusStatValue(OfflinePlayer, Stat): double`

Legacy Int API signatures and truncation remain unchanged. ADD is applied before MULTIPLY,
as before. Modifiers use a stable UUID: another add with that UUID replaces its value.
Removal dirties only the affected online player's native EcoSkills holder provider and icon.
Updates are coalesced by LibreForge's existing holder flush; no new polling is introduced.
Reload resolves modifiers by stat ID. Quit removes session modifiers; consumers reapply on join.
Use the Bukkit main thread for modifier mutations. Do not force refresh every tick.

Stats use Double holders, native `%level%` expressions, numeric placeholders,
descriptions and menu rendering. Roman numerals and skill levels remain integer.
Fractional holder IDs encode the exact Double bits; fractional caches are bounded to 512 entries.
Native bonus_health increases maximum health without healing. Its removal clamps current health
only when above the final maximum, through the existing next-tick LibreForge lifecycle.

Build: limited personal executor, JDK 25 toolchain / JVM 21, Kotlin 2.2.21.
35 tests passed: 12 decimal tests, 2 native health contract tests, 21 existing regressions
(including final XP receipt). Health tests execute the installed LibreForge clamp bytecode
with mocked entities and inspect native enable/remove calls. This is not in-game verification.
All old public/protected member descriptors of replaced classes were checked for preservation.

Runtime is composed from accepted Test baseline f373a3eaac5dd987462f2a6e13fa6de9fa2e891bbfe1c6f6e6c315a97c155af2
and the native shadow core. Only 19 decimal-related class entries change; six final XP bridge
class entries and all unrelated entries retain identical bytes. Loader, NMS and dependency versions
are retained. API JAR contains EcoSkills classes only, without bundled dependencies; use compileOnly.

Runtime: EcoSkills-2026.27-all.jar
SHA256: c313244570a28ff34e56514f3342bbc6fe475c80aa27baea92b5c7a499b5e4dc
API: EcoSkills-2026.27-decimal-api.jar
SHA256: b5ea727a501ea241ccf7af3d30d1d3faaf42d6f8a226100ecc5569908ba8ff90

Installed and started only Test f2ea450e-02a0-416c-9838-e73458d37b1c.
Boot completed 15:35:47 Chile: EcoSkills/LibreForge loaded, vPets final Eco XP bridge connected.
Production Custom/Sync EcoSkills hashes remain 1273599d07dfec88e9b5cd7cd55e102faca0b9027a0f35fb9b591bf1e7419efa.
No production install or lifecycle. vPets source/JAR not modified.
HDD backup: /mnt/storage/backups/ai/survival-custom/ecoskills-decimals-20261011.

Pending: adapt vPets against the supplied API, equip/remove/re-equip all 12 pets,
check decimals in native effects/placeholders/menu, and validate real reconnect/reload
without residual attributes or healing. Automated session/registry replacement tests passed;
these do not replace gameplay acceptance.
