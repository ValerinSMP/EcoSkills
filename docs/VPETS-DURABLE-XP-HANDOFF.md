# vPets ↔ EcoSkills: XP de mesa durable y atribución original

Coordinación autorizada por el propietario2026-10-15. Este documento NO acredita conexión lista ni pruebas de vPets. No se editó vPets/vEnchanting desde EcoSuite ni se enviaron mensajes externos. Entregar al chat vPets; autoridad/implementación de sus datos permanece allí.

## Decisión aprobada

EcoSkills guarda recibo y outbox durables. XP/progreso/límite se aplican una vez; la notificación puede repetirse tras caída con el MISMO awardId. vPets debe deduplicar de forma durable antes de dar XP. No volver a calcular fórmula, multiplicadores ni enchant_item×3.

## Atribución antes de la compra

Se necesita un contrato de captura de vPets ANTES de la operación asíncrona: UUID jugador, ámbito, sesión de juego original, captureId y token opaco inmutable que identifique la atribución de mascota(s) vigente (incluido reparto actual). Capturar/guardar en vPets la mascota/instancia/sesión de mascota y reglas pertinentes en ese punto, NO consultar la equipada al llegar el evento o al ejecutar SQL.

EcoSkills solo transporta captureId, sesión original y token: no consulta BD vPets, no interpreta especies/repartos ni inventa datos si falta captura. El captureId/token debe ser reproducible al reconsultar el recibo confirmado de la compra, no regenerarse en cada retry. Distintos plugins pueden tener sesiones internas distintas: el token puede contener la sesión interna vPets, manteniendo además el vínculo explícito con la sesión de juego original.

## Consumo durable requerido a vPets

1. Aceptar la nueva fuente pública verificada del proveedor EcoSkills de compras confirmadas, sin fabricar EnchantItemEvent. Conservar filtros existentes para otras fuentes. AwardId solo no demuestra procedencia: validar recibo APPLIED/evidencia del proveedor por su API pública, importe final y vínculo con captura.
2. En su propia transacción: deduplicar por (ámbito,awardId), verificar payload/importe/captureId original, aplicar la XP final a las mascotas capturadas según reparto actual y registrar recibo del consumidor. Misma operación repetida devuelve el mismo resultado. Payload distinto: conflicto, nunca otra suma.
3. Después de commit en vPets, confirmar entrega a EcoSkills con el identificador del intento de entrega. Si cae tras commit y antes del ack, el reenvío debe detectar el recibo y reenviar ack sin sumar XP otra vez. Ack viejo tras nueva reclamación no debe confirmar la entrega nueva equivocada.
4. No redirigir a una mascota nueva al reconectar/cambiar de servidor/equipar otra. Si la captura original no puede resolverse con seguridad, dejar pendiente/revisión explícita, no dar XP a la equipada ahora.
5. No interpretar timeout como rechazo ni generar un awardId nuevo. No consultar ni modificar SQL de EcoSkills desde vPets. Fallo de proveedor/recibo/captura bloquea la entrega, sin fallback a fuente no atribuida.

## Contrato en desarrollo, aún no listo para conectar

En personal EcoSkills existe un núcleo transaccional `DurableEnchantingStore` que modela recibo, contador diario, progreso nativo por gateway y outbox, con lease/revisión y token de reclamación. NO es todavía la API pública de runtime. Su integración al handler nativo, cercado de todas las escrituras ordinarias, eventos Bukkit y consumidores está pendiente. No adaptar vPets a clases internas ni cobrar/habilitar compras por la existencia de ese código.

Antes de conectar se entregarán métodos públicos/capacidades/versiones, evento/fuente tipados, consulta/validación del recibo y ack, API JAR y runtime exactos con hashes. `EnchantingPurchaseEvidence` del incremento anterior es solo un claim estructural, no una prueba de entrega. La fórmula y el awardId determinista por sí solos no son deduplicación durable.

## Pruebas de aceptación coordinadas

Reenvío tras commit sin ack; caída/reinicio de cada lado; duplicado concurrente Custom/Sync; mismo ID/payload distinto; cambio de mascota entre compra y entrega; desconexión y transferencia; captura ausente; reparto actual con varias mascotas; final modificado/cap truncado; importe0 sin XP mascotas. Recompensas/XP anteriores preservadas. Distinguir H2/mocks, MySQL real y sesión real. No builds fuera de personal, no lifecycle por inferencia, no producción.

## Firmas de coordinación preparadas

Paquete público `com.willfp.ecoskills.api.enchanting.EnchantingXPConsumer`, CONTRACT_VERSION1:

- `CompletionStage<Attribution> capture(AttributionRequest request)`.
- `CompletionStage<Consumption> consume(Delivery delivery)`.
- AttributionRequest: operationId,playerId,scope,originalSession.
- Attribution: captureId,request,opaqueToken (inmutable; hasta4096 caracteres).
- Delivery: evidencia de compra, awardId estable, finalXP, originalAttribution, deliveryAttempt y receiptPayloadHash.
- Consumption: APPLIED, ALREADY_APPLIED, RETRY, REVIEW, PAYLOAD_CONFLICT.

Ambas llamadas se diseñan para entrar en main; vPets copia allí los datos Bukkit y hace persistencia async. APPLIED/ALREADY_APPLIED significan commit propio realizado antes de retornar. El proveedor debe validar procedencia/recibo antes de notificar, y no hay aún registro de servicio de runtime habilitado. El contrato de recepción no es, por sí solo, prueba pública de que ocurrió una compra ni permiso para registrar un proveedor alternativo.

El runtime viejo no contiene esta interfaz. Usar únicamente el SDK compileOnly de desarrollo entregado junto al manifiesto de este incremento y coordinar versión antes de instalar; no adaptar a las clases internas del store. Evento final público y fachada de verificación/consulta/ack quedan pendientes de la integración del escritor nativo. No sustituirlos por un evento falso o gainSkillXP sin recibo.
