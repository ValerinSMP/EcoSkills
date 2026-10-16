# vEnchanting — preparación de compra y sesión EcoSkills, 2026-10-16

## Cambio requerido en vEnchanting, en su chat
El SDK público y sus firmas no cambian. No hay que crear otra API ni sombrear nuevas clases.
Quitar el precheck que aborta EcoPurchaseClient.prepare al obtener Optional.empty de provider.session(playerId). session es una observación local, deliberadamente vacía tras20s; no inicia admisión ni representa la readiness global.

Con proveedor global ready, llamar en MAIN a provider.preparePurchase(pluginOwner, playerUUID, operationId) y esperar async la Attribution durable. El proveedor ahora espera la readmisión/flush/lease SQL/publicación necesaria para compras nuevas. Solo cobrar o aplicar después de éxito.

Validar la respuesta: request.playerId/request.operationId/request.scope deben corresponder a la compra. Conservar captureId, request.originalSession y opaqueToken ORIGINALES en el ledger propio. No sustituir originalSession consultando la sesión actual al reenviar. Si ya hay atribución guardada, comprobar igualdad exacta; un conflicto no se resuelve generando nueva operationId.

Mismo operationId al reintentar; timeout no prueba fracaso ni permite otro cobro o captura independiente. Reconexión con intent/captura durable conserva la atribución original; si no existe y la sesión cambia durante admisión, se rechaza antes de captura. Scope/jugador/operación se siguen validando. grantConfirmedPurchase y recibo/outbox/ack no cambian.

## Causa y evidencia
Test registra proveedor10:34:17, join11:25:42 y compra abortada11:26:45 (63s). JAR instalado8c10179d… mantiene readiness local20s y lease60s. La expiración local reproduce session vacío con jugador conectado y readiness global true. No hay registro histórico de epoch/fence en ese error, por lo que no se afirma haber leído su estado exacto de memoria. El fallo MySQL del join en el log pertenece a HMCCosmetics; no demuestra fallo del handler EcoSkills.

La corrección conserva20/60s, admite antes de capturar, agrupa admisiones y evita finalizaciones viejas tras reconexión. No crea sesión ficticia, no repite grants ni callbacks por expirar readiness. Fallos de admisión ahora registran epoch/estado/fenceRevision y causa.

## Pruebas y entrega
Relojes simulados para20/63s; H2 para lease vivo/expirado/otro propietario; join posterior al arranque, desconexión pendiente, finalización antigua, espera de grant en curso y reenvío con atribución/award originales. API pública idéntica byte a byte.
Resultados finales y hashes en manifest.json/SHA256SUMS de session-fix-20261016. Estas pruebas NO son MySQL/Paper/Test reales.
Solo EcoSkills requiere nuevo JAR. No se editó vEnchanting/vPets ni se instaló o ejecutó lifecycle. Tras adaptar el cliente quedan pendientes aceptación real en Test, compra tras63s y reconexión.
