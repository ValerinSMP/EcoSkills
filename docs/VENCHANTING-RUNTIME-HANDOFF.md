# vEnchanting — integración EcoSuite durable XP, 2026-10-15

Proveedor runtime implementado y compilado; no instalado. Contrato: ENCHANTING-PROVIDER-CONTRACT.md.
SDK compileOnly/provided: EcoSkills-enchanting-runtime-api-2026.27.jar y EcoEnchants-upgrade-api-2026.27.jar. No sombrear ni instalar SDK como plugin.

1. Descubrir EnchantingXPProvider por ServicesManager y altas/bajas del servicio. En MAIN registrar registerPurchaseAuthority(plugin,authority), aunque readiness aún esté incompleto. vPets registra su consumidor separadamente; habilitar checkout solo cuando readiness lo permita.
2. Crear operationId estable ANTES de comprar. preparePurchase(plugin,playerUUID,operationId), MAIN, devuelve captura durable con atribución original. Conservarla en el ledger de compra junto al jugador, sesión, scope y evidencia canónica. No elegir otra mascota al reintentar.
3. Resolver realmente cobro, custodia y entrega de la mejora. Solo tras compromiso durable propio crear Confirmation(scope,operationId,deliveryReceiptId). verifyConfirmedPurchase debe consultar ese ledger: CONFIRMED con evidencia y Attribution originales completas; UNKNOWN/NOT_COMMITTED/REJECTED sin payload. No sustituirlo por un booleano, clic o evento aplicado.
4. grantConfirmedPurchase(plugin,confirmation), MAIN. I/O propia async; devolver operaciones Bukkit a MAIN. Reintentar con la MISMA confirmation/operación/recibo/payload. Timeout es incierto: no cobrar, aplicar ni conceder otra vez. El proveedor conserva awardId, recibo/outbox, progreso y cap. lookupReceipt/verifyDelivery/acknowledgeDelivery existentes se mantienen.
5. Callback incierto va a revisión/reconciliación, nunca repetición ciega. Fallo de publicación de caché tras commit no autoriza otro grant.

## Mejora nativa
EnchantUpgradeAPI.validateUpgrade y applyUpgrade, MAIN. Aplicar recibe Player, storageSlot, expectedItem exacto, Enchantment, expectedFrom, targetLevel, LevelPolicy y operationId. Conserva metadatos/PDC, restricciones nativas, holders y callbacks; revalida tras eventos y CAS del item. NATIVE_MAXIMUM por defecto; VANILLA_ONE_ABOVE solo máximo vanilla+1.
APPLIED_CALLBACK_REVIEW: item aplicado, no repetir. UNKNOWN_APPLY_REVIEW: reconciliar. operationId correlaciona pero NO es recibo durable ni deduplicación de entrega. vEnchanting conserva autoridad de custodia/ledger.
Desbloqueo has_skill_level exige caché conocida; condiciones de activación permanecen en holders. No emitir EnchantItemEvent sintético ni duplicar XP legacy/final para esta compra.

## Estado
190 pruebas JVM/H2/simuladas pasaron. Java público compila; consumidor4781ed1 enlaza sin ejecutarse. Pendientes adaptador de autoridad vEnchanting, bootstrap/verificador durable vPets y aceptación MySQL/Paper/Test/transferencia/carga real.
No se modificó vEnchanting/vPets; vPets1.30.0 permanece sin instalar. Test requiere prefijo/almacenamiento separado de producción antigua; otro scope solo no aísla. Este documento no autoriza instalación, migraciones ni lifecycle.
