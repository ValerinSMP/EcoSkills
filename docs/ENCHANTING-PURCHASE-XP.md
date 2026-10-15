# EcoSkills — contrato de XP por compra de mesa

Estado2026-10-15: primer incremento de cálculo/evidencia/diagnóstico preparado; proveedor durable de concesión NO implementado ni habilitado. Sin instalaciones ni lifecycle ni cambios en vEnchanting/vPets. Solicitud: VENCHANTING-CONECTAR-XP-HANDOFF-20261015.md.

## Evidencia contrastada

Canónica: personal `/root/development/projects/eco-suite/EcoSkills`, HEADf65ddd1 previo al incremento. Se encontraron cambios ajenos en EcoSkillsAPI, SkillLevelMap, SkillXPAccumulator, STATE y el puente final/evento/helper/tests no versionados. Fueron hasheados antes del incremento y no se editaron. eco también tiene archivos ajenos de target AI; no se editaron. El JAR Test citado en el handoff es1ad3d552…; presencia de archivo no equivale a runtime.

Ruta actual: gainXP aplica skillXPMultiplier y PlayerSkillXPGainEvent, después giveXP actualiza nivel/XP y ejecuta level-up/recompensas, después PlayerSkillXPReceivedEvent. Este evento documenta explícitamente actualización en memoria, no commit SQL. Su awardId actual se genera aleatoriamente por llamada. No constituye recibo de compra durable.

EcoProfile.write primero cambia caché y llama profileWriter.write. PersistentDataHandler.write envía cada serializer.writeAsync mediante executor.submit sin devolver confirmación al consumidor. MySQL SingleValueSerializer hace upsert de una clave por transacción. Por tanto, insertar una tabla de recibos y llamar gainXP no daría atomicidad recibo/progreso/límite, y un crash puede perder o duplicar la actualización. Una cola o mapa local no corrige ese problema entre nodos/reinicios.

## Incremento independiente ya preparado

Paquete público nuevo `com.willfp.ecoskills.api.enchanting`, sin editar la fachada ni la mutación actual:

- `EnchantingPurchaseEvidence`: record inmutable de operación/jugador/ámbito/sesión, key de enchant, tiers, modalidad, niveles vanilla realmente consumidos, versión de tarifa, hash de contexto, receipt de entrega y momento confirmado. Valida forma y rechaza retiradas/no mejoras/consumo incompatible. No autentica al consumidor ni prueba entrega por sí solo.
- `payloadHash()`: huella SHA256 de campos codificados con longitud; no firma de autenticación. El mismo operationId con otra huella debe ser conflicto en el futuro backend, no otra concesión.
- `awardId()`: identidad determinista por ámbito/operación, estable entre llamadas y nodos. Calcularla NO registra ni aplica un award, y no demuestra idempotencia durable.
- `EnchantingPurchaseXP.effectiveLevels(evidence)` y `baseXP(evidence)`: vanilla usa niveles reales consumidos; custom usa6×deltaTier; base=3,5×L^1,5, sin×3. III→IV da51,43928459844674 para shards y gemas.
- `limitedFinalXP(committedToday, modifiedFinalXP)`: cálculo puro candidato de cap500.000 aplicado DESPUÉS de modificadores legítimos/evento nativo; no incluye persistencia ni contador compartido.
- `utcDay(Instant)`: reset propuesto medianocheUTC para ambos nodos, no reloj local independiente. El backend debe usar su reloj autoritativo y fijar bucket en el recibo, sin confiar en fecha del addon ni resetear por reload.
- `EnchantingLevelObservation.getCached(UUID)`: main, observa solo skills mediante API propietaria existente; devuelve level real o reason tipado de ausencia (cache_cold/profile_not_loaded/etc.), sin defaults, SQL o carga de perfil. No afirma corregir el fallo real hasta invocarlo en sesión Test y observar su causa.

Estos tipos NO registran una capacidad de concesión. No conectar compras, llamar gainXP como fallback ni inferir APPLIED de estos cálculos. Las formas hash/cálculo y el diagnóstico son API preliminar de este incremento, no contrato final del backend.

## Proveedor necesario para completar la solicitud

Firmas de fachada aún propuestas, NO compiladas/exportadas:

- `getEnchantingAwardCapabilities()` — schema/formula/cap-policy/backend/ready/replay y límites de cola.
- `awardEnchanting(confirmedPurchase)` → CompletionStage<AwardReceipt>.
- `getEnchantingAward(scope, operationId)` → CompletionStage<AwardReceipt>.

Resultado explícito: APPLIED solo con commit durable comprobado; DECLINED con motivo; PENDING/UNKNOWN ante ejecución/resultado incierto; PAYLOAD_CONFLICT si el mismo ID trae otra huella. No traducir un resultado desconocido a DECLINED ni volver a emitir con ID nuevo. Separar NOT_FOUND de UNKNOWN/servicio no disponible. Repetir conserva importe/awardId/política original, aunque cambien tarifas/modificadores.

Validar plugin consumidor autorizado y confirmación de la autoridad que aplicó/entregó el objeto. Un booleano de una solicitud no prueba una compra genuina. Coordinación necesaria con vEnchanting para recibo confirmado consultable y captura de atribución de mascota/sesión antes de trabajo asíncrono; este chat no edita ese plugin.

Main captura/valida jugador, sesión propietaria actual, definición de Encantador, elegibilidad natural, baseXP y modificadores legítimos exactamente una vez; no pasar por enchant_item×3 ni fabricar EnchantItemEvent. Perfil/sesión/lectura ausente bloquean. Revalidar tras espera async y transferencia; no atribuir a la nueva sesión/mascota por consultar al final.

Backend propio de EcoSkills con acceso acotado mediante handler eco ya inicializado: recibo, contador diario y progreso durable deben tener una frontera transaccional común. También hay que cercar escrituras normales pendientes, caché local, sesiones y escrituras de otro nodo: sin esa protección, un upsert tardío puede deshacer una concesión comprometida. No crear un segundo perfil/tabla de progresión en el addon ni prometer atomicidad usando el StorageReader de observación, que es deliberadamente solo lectura.

No se ejecutaron DDL/DML ni se crearon tablas. Migración y pruebas reales de persistencia requieren entrega/ejecución acotada en Test y autorización de las acciones que correspondan; no activar mediante este documento. Sin credenciales nuevas ni SQL arbitrario público.

## Decisión pendiente: entrega del evento final

Una única emisión física de Bukkit y entrega garantizada tras caída son incompatibles sin participación durable del consumidor: caer después de emitir y antes del ack obliga a elegir posible duplicado o pérdida. Se consultó al propietario si acepta outbox/reenvío con awardId estable y deduplicación durable del consumidor. No sustituir en silencio el contrato por at-most-once con pérdida.

Propuesta: progreso/cap/recibo una vez; notificación al menos una vez con importe final, awardId, fuente pública tipada y evidencia de compra. vPets deduplica de forma durable por awardId y conserva atribución capturada. Reconciliar eventos pendientes tras reinicio/transferencia solo en sesión/consumidor válidos. Entregar dos veces la notificación en una simulación NO debe duplicar XP/mascotas/recompensas. Level-up/recompensas nativos requieren su propia revisión de efectos e idempotencia; no prometer que ejecutar comandos externos sea una transacción SQL.

## Verificación y límites

Tests nuevos del incremento: fórmula/consumo real/delta/no×3; no upgrade/retirada/consumo incompatible; identidad estable y huella conflictiva; cap puro con modificadores/no finitos; fronteraUTC; lectura diagnóstica de 0/valor positivo/ausencia/identidad/tipo inválido. No son pruebas de idempotencia durable ni de runtime.

Pendientes del proveedor: almacenamiento real, saturación/deadline/cancelación, replay tras commit/timeout/reinicio, operación duplicada concurrente en dos nodos, payload distinto, cap compartido, fence del perfil, modificación/cancelación nativa, level-ups y premios, outbox/ack y vPets. Pendiente también una captura real de level_missing antes de corregir su causa. Ninguna de esas pruebas se presenta como realizada.

## Build del primer incremento

Compilado con JDK25/release21 y ejecutor limitado en personal; Maven aislado observation-20261013, que conserva las APIs eco/LibreForge modificadas. El intento con Maven upstream anterior falló por faltar esas APIs y no se usó su salida. Última ejecución:66 tests,0fallos/errores/omisiones,10nuevos; hashes de ocho fuentes ajenas idénticos. JAR de contrato preliminar (solo compileOnly, sin provider/plugin.yml ni instalación): `EcoSkills-enchanting-contract-draft-api-2026.27.jar`, SHA256 `08628085fd1f82a04eb7407ca676cd757d3bf20478c1c362b53f19b73384033d`. No sustituye el runtime ni desbloquea vEnchanting.
