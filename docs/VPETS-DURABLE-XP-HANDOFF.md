# vPets 4781ed1 provider handoff — 2026-10-15

Receipt contract now implemented in public EnchantingXPProvider v1: registerConsumer/unregisterConsumer, Access.lookupReceipt/verifyDelivery/acknowledgeDelivery. Consumer v1 and the 10previous API class bytes are unchanged. See ENCHANTING-PROVIDER-CONTRACT.md for exact signatures, statuses, proof reconstruction, original-attempt ack and the vPets XPDigest mapping.

Do NOT connect a positive runtime adapter or install vPets1.30.0 yet. No provider bootstrap/discovery or complete native queue/cache/MySQL handler/source-authority binding exists. FencedNativeProfileWriter now exercises the actual eco handler and native scalar transactions with H2; that is not the whole runtime writer lifecycle. Runtime readiness is not established.

No vPets/vEnchanting edits or real migrations/installations/lifecycle. API SDK c64658f437003e4f71a3ef9eb105f8f4be6bb7ad1d17d5873b8b7c5008e58f8d is compileOnly/provided, never shaded or installed. Full contract, artifact hashes and evidence are in the provider delivery manifest. MySQL/Test two-node/crash and gameplay remain pending.
