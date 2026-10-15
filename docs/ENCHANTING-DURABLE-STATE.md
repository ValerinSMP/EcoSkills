# Proveedor runtime — 2026-10-15
Implementados cola nativa completa, handler MySQL existente, fence/revisión/sesiones, publicación de caché/holders, preparePurchase/grantConfirmedPurchase y recuperación acotada. Recibos/verificación/ack conservados.
190 tests JVM/H2/simulados sin fallos; JDK25/release21, personal limitado. Java público compila y consumidor4781ed1 enlaza sin ejecutarse.
No instalación/activación/SQL real/lifecycle. Default desactivado. Artefactos en manifest.json/SHA256SUMS de runtime-20261015.
Implementación externa pendiente: autoridad vEnchanting y adaptador vPets. Pruebas pendientes: MySQL/Paper/Test, transferencias, inventario/callbacks reales y carga.
Contrato ENCHANTING-PROVIDER-CONTRACT.md; handoffs VENCHANTING-RUNTIME-HANDOFF.md y VPETS-DURABLE-XP-HANDOFF.md. Documentos parciales anteriores son históricos.
