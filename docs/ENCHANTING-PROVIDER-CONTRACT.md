# EcoSkills durable XP — public receipt contract v1 (2026-10-15)

## Status

The receipt/verification/attempt-ack interface is implemented. The origin-fenced writer bridge is implemented against eco's actual handler, and exercised with H2 native scalar tables. This is NOT a complete, ready runtime provider. There is no plugin bootstrap/ServicesManager registration, verified purchase-authority adapter, complete native mutation queue/cache publication or recovery wiring. No installation/lifecycle/real MySQL migration occurred. vPets 4781ed1 / candidate 1.30.0 remains uninstalled. Do not enable purchases, claim readiness or install these development libraries.

The existing EnchantingXPConsumer CONTRACT_VERSION=1 and its capture/consume descriptors are unchanged. EnchantingXPProvider CONTRACT_VERSION=1 is an additive public contract for receipt access, not a grant API. A full runtime SDK release remains blocked by the integration items below.

## Exact public methods

Package `com.willfp.ecoskills.api.enchanting`:

- `EnchantingXPProvider.Readiness readiness()`
- `EnchantingXPProvider.Access registerConsumer(org.bukkit.plugin.Plugin owner, EnchantingXPConsumer consumer)`
- `void unregisterConsumer(org.bukkit.plugin.Plugin owner, EnchantingXPConsumer consumer)`
- `Access.lookupReceipt(EnchantingXPProvider.ReceiptKey)` → `CompletionStage<Observation>`
- `Access.verifyDelivery(EnchantingXPConsumer.Delivery)` → `CompletionStage<Observation>`
- `Access.acknowledgeDelivery(EnchantingXPConsumer.Delivery, EnchantingXPConsumer.Consumption)` → `CompletionStage<Ack>`

ReceiptKey contains scope, operationId, awardId. CommittedReceipt returns full reconstructed EnchantingPurchaseEvidence, awardId, exact final double XP, ORIGINAL Attribution, receiptPayloadHash, committed native level/XP/revision. DTOs are immutable; Readiness copies blockers with Set.copyOf. No pool, credentials, SQL or other-plugin data is exported through this interface.

Registration checks the exact configured owner plugin instance, not a name. A different consumer cannot replace a registered one. Unregister revokes issued capabilities and queued work. This is a trusted in-process plugin contract, not a sandbox against malicious server plugins. Authorize/revoke helpers are implementation details, excluded from the API JAR. No consumer registration occurs merely by loading these classes.

## Lookup, verification and ack

Lookup reads committed provider rows, never reflects a supplied receipt as proof. It decodes a bounded versioned binary/base64 payload and rechecks binding of evidence, original session, captureId/token, player, scope, operation and stable award. Absence is NOT_FOUND, not invented PENDING. Corruption is REJECTED. Wrong award identity is PAYLOAD_CONFLICT. Query/queue/timeout failures are unavailable/unknown; they never prove rejection or permission to issue another award.

A lookup APPLIED is NOT delivery authorization. verifyDelivery additionally compares the entire evidence, exact double bits, ORIGINAL attribution and receiptPayloadHash, and locks/checks FINAL_XP outbox RUNNING with the same unexpired deliveryAttempt. READY, ACKED, REVIEW, absent, expired and replaced attempts cannot verify as APPLIED. It does not require the original game lease to remain alive after commit.

acknowledgeDelivery accepts only APPLIED/ALREADY_APPLIED from the authorized consumer after its OWN durable commit. The provider cannot prove another plugin's DB commit by itself; this is the consumer's contract. RETRY, REVIEW and PAYLOAD_CONFLICT are rejected without writes. The receipt and exact payload are rechecked; current attempt/lease and state are checked and CASed atomically. The identical already-ACKED attempt returns ALREADY_ACKED; an old attempt cannot finish a replacement attempt, even if the body/awardId is identical. Expiry before reclaim already rejects the old ack.

Keep the SAME original Delivery object in its consume future's completion. Timeout/unknown must never call an award method, regenerate operationId/awardId, replace attribution or select current pets. Lost ack causes a later outbox claim with the same award/payload and a new attempt; vPets recognizes its own committed receipt and returns ALREADY_APPLIED. Retry never means recalculate the award.

Zero final XP (cap exhausted) has a complete committed receipt including original capture, but no FINAL_XP outbox or positive Delivery. Token/hash fields belong to plugin coordination; do not publish them to the web/bot as player observation fields.

## vPets ReceiptVerifier adapter handoff (no vPets edit performed)

After a genuine runtime service is provided and readiness/integration accepted:

1. Register the existing EnchantingXPConsumer instance through the public provider and retain Access. Do not import DurableEnchantingStore or any implementation class.
2. Call access.verifyDelivery(originalDelivery) asynchronously.
3. APPLIED: reconstruct a Delivery from the RETURNED committed receipt fields plus the original attempt which this exact verification checked. Compute vPets' existing XPDigest.delivery from that reconstructed object; return VERIFIED_APPLIED with that vPets fingerprint and the same validated attempt. Do not confuse provider receiptPayloadHash with vPets validatedConsumerPayload. They are different hashes. Do not return a digest of unchecked caller input.
4. NOT_FOUND/PENDING/UNKNOWN → NOT_COMMITTED (retry same identity). UNAVAILABLE/failed future/timeout → UNAVAILABLE. REJECTED → REJECTED. PAYLOAD_CONFLICT → PAYLOAD_CONFLICT.
5. Only after consume(originalDelivery) returns APPLIED/ALREADY_APPLIED following vPets' commit, call acknowledgeDelivery on that SAME delivery/attempt. Other results are never success-acks.
6. Retain the SDK as compileOnly/provided; never shade it or install the API JAR as a plugin. Source/capture schema and consumer version remain 1. Bootstrap/discovery is NOT supplied by this increment; do not invent getProvider APIs or use reflection as a positive adapter.

## Real eco writer change

`EcoProfile.write` captures/admit-checks a PreparedProfileWrite BEFORE cache mutation. ProfileWriter coalesces captured tasks, preserving origin context; conditional map removal prevents losing a newer task. PersistentDataHandler.write (including loadSerializedProfile) also captures before executor submission. At execution no current session/revision is reconstructed. Jobs captured before a different writer binding fail closed; live bindings cannot be replaced/removed.

The optional ProfileWriteBarrier returns a complete replacement persistence task for owned keys; it must NOT wrap the old serializer's separate transaction. Unowned keys retain native serializer behavior. FencedNativeProfileWriter binds actual typed EcoSkills keys and a reconciled Fence on MAIN. It uses the existing handler's executor, not a second native pool. Its task writes fence + per-key revision/sequence + native scalar value in one store transaction. Out-of-order values are superseded; old session/revision cannot overwrite XP or built-in rewards after a durable award. SQL uncertainty freezes the binding and requests reconciliation, never blind replay.

This bridge alone does NOT close the native runtime integration: the real already-initialized MySQL pool still needs a constrained backend binding, and native domain mutations must be queued as WHOLE operations while a grant commits, then loaded caches/holders coherently published before resuming. Throwing on an unavailable/frozen scalar is a fail-closed backstop, not a substitute for that queue. Do not declare ready based on installProfileWriteBarrier, class presence, one guarded SQL call, or H2 tests.

An explicit `reconcileUncertainLevelCallbacks(scope, limit<=32)` moves only expired RUNNING LEVEL_EFFECT entries to REVIEW, never claims READY work or executes commands. Built-in native stat/effect rewards remain in the award transaction; do not call handleLevelUp afterward and grant them twice. The callback dispatcher still needs runtime binding and session-aware publication.

## Bounds, storage and acceptance

Receipt service: one I/O worker, queue 64 (65 running+queued maximum), one response-deadline timer, default response deadline 5s. A running timed-out job retains its worker slot; queued timed-out jobs are removed. SQL statement timeout 2s; transaction deadline is checked before statements and commit. Acquisition/driver/commit interruption is not guaranteed by those checks. The existing pool/driver must be checked before readiness; no new pool or credential copy is introduced. Close service before pool; shutdown/revocation does not turn a potentially committed operation into a safe new grant.

New draft schema stores original attribution on receipts too (including zero-XP receipts), plus origin ordering metadata in enchanting_writes. The previous draft schema was never migrated on live MySQL. CREATE IF NOT EXISTS is not an upgrade of an existing old draft table: an existing old draft database must be rejected/migrated explicitly before readiness. No real DDL/DML was executed here.

Pending release gates: complete native whole-operation queue + startup/session/transfer ownership + pending-write drain + cache/holder publication; actual initialized MySQL handler binding/timeouts; genuine vEnchanting confirmed-purchase authority; capture-before-checkout durable ordering; typed final dispatcher with no duplicate legacy event; bounded bootstrap/reconnect/transfer recovery; full MySQL/Test two-node/crash acceptance. These are implementation/integration work, not merely unrun tests. Keep provider unavailable and vPets 1.30.0 uninstalled.

Preserved native API, decimal statistics, mana, rewards/XP legacy bridge, toolchains and foreign source changes. No vPets/vEnchanting source modifications. H2/simulations do not demonstrate physical MySQL crashes, gameplay, active runtime or measured performance.
