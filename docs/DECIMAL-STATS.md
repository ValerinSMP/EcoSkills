# Decimal effective stats — display correction 2026-10-11

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
descriptions and menu rendering. Actionbar stats use legacy integers; menu/descriptions/public numeric placeholders show at most two decimals. Native expressions and Double API retain full precision, including cross-stat crit/mana expressions through native config-local injection. Roman numerals and skill levels remain integer.
Fractional holder IDs encode the exact Double bits; fractional caches are bounded to 512 entries.
Native bonus_health increases maximum health without healing. Its removal clamps current health
only when above the final maximum, through the existing next-tick LibreForge lifecycle.

Build: limited personal executor, JDK 25 toolchain / JVM 21, Kotlin 2.2.21.
38 tests passed: 15 decimal/display tests, 2 native health contract tests, 21 existing regressions
(including final XP receipt). Health tests execute the installed LibreForge clamp bytecode
with mocked entities and inspect native enable/remove calls. This is not in-game verification.
All old public/protected member descriptors of replaced classes were checked for preservation.

Runtime is composed from accepted decimal baseline c313244570a28ff34e56514f3342bbc6fe475c80aa27baea92b5c7a499b5e4dc
and the native shadow core. Only seven display/expression-context class entries change; six final XP bridge
class entries and all unrelated entries retain identical bytes. Public/protected ABI is preserved.
Loader, NMS and dependency versions are retained. API JAR contains EcoSkills classes only,
without bundled dependencies; use compileOnly. No vPets edits or XP/progression changes.

Runtime: EcoSkills-2026.27-all.jar
SHA256: 75bd85b9eb0cf07848036c020a66f945c83fd3443d56d014792446baa0b726c5
API: EcoSkills-2026.27-decimal-api.jar
SHA256: 324a62336c47c864d3c9ff08a8100417cd30d7bd3ae1cfc87f8fe6b2db40e233

Display correction installed on Test, Custom and Sync with verified backups/hashes.
No restart or reload was performed; owner explicitly forbids restarting Test. Activation and
startup verification of this correction are pending owner lifecycle. Earlier decimal baseline
boot was verified on Test with final XP bridge connected; that does not verify this correction.
HDD backup: /mnt/storage/backups/ai/survival-custom/ecoskills-decimal-display-20261011T201036Z.

Read-only live audit queried four stat placeholders for 66 Custom players and 19 Sync players.
MakiZenin_ and PatoMel had over-two-decimal outputs in that snapshot; none found in Sync.
This is an online sample, not a statement about all offline players or all stats.

Pending: adapt vPets against the supplied API, equip/remove/re-equip all 12 pets,
check decimals in native effects/placeholders/menu, and validate real reconnect/reload
without residual attributes or healing. Automated session/registry replacement tests passed;
these do not replace gameplay acceptance.
