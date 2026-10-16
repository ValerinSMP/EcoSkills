# EcoSuite — proveedor runtime durable de XP de compras, contrato final v1

Fecha: 2026-10-15. Consumidor compatible: vPets `4781ed1`, EnchantingXPConsumer.CONTRACT_VERSION=1. El proveedor y la aplicación nativa de mejoras están implementados y se entregan como candidatos runtime; no instalados ni activos por esta entrega. La confirmación legítima la implementa vEnchanting contra su propio ledger; el adaptador y bootstrap de vPets pertenecen a su chat. Registrar clases o activar una opción no prueba una compra.

## Descubrimiento, disponibilidad y activación futura

Servicio Bukkit `com.willfp.ecoskills.api.enchanting.EnchantingXPProvider`. Se registra mediante ServicesManager solo después de conectar el handler MySQL inicializado de eco, comprobar/migrar las tablas propias y conectar el escritor. El consumidor debe consultar el registro al habilitarse y escuchar ServiceRegisterEvent/ServiceUnregisterEvent; no usar reflexión sobre implementaciones ni polling. La clase pública `EnchantingXPProvider` y sus DTO son del runtime EcoSkills; SDK compileOnly/provided, nunca sombreado ni instalado como plugin.

Configuración nativa opcional (default entregado: desactivado):

```yaml
enchanting-purchase-xp:
  enabled: false
  scope: ""
```

El ámbito es explícito y común entre los nodos que comparten tablas nativas; no es el ID del servidor. El prefijo físico queda ligado durablemente a un solo scope: no se permite elegir otro para abrir una segunda autoridad de fences/cap sobre los mismos perfiles. Test necesita almacenamiento/prefijo propio; otro scope sobre las mismas tablas NO lo aísla. Todos los escritores que compartan ese prefijo deben ejecutar el runtime actualizado. Esto es una condición de despliegue: el proveedor no puede descubrir un servidor antiguo que escriba fuera de su protocolo.

Requiere handler MySQL nativo, almacenamiento compartido de EcoSkills (no local), al menos tres conexiones del pool existente, tablas InnoDB y Encantador nativo con hasta1024 niveles y requisitos positivos finitos. Usa claves registradas reales de skills/XP, stats y efectos; no cambia BD/progresión a otra representación. Reutiliza el pool de eco; al activar el escritor limita la espera de adquisición a3s. No copia credenciales ni crea otro pool.

`readiness()` exige MAIN. Devuelve ready solo si el backend y su cola están inicializados, no hubo cambio de configuración que requiera reinicio y están registrados el consumidor y la autoridad reales. Es disponibilidad LOCAL del protocolo, no prueba de compra ni verificación del despliegue de otros nodos. Antes de una compra también debe existir la sesión del jugador. Deshabilitar un participante revoca su registro. Un reload de EcoSkills posterior a la inicialización detiene compras nuevas hasta un reinicio completo; no soporta hot-unload/reemplazo individual de la barrera de persistencia. El resto de las rutas legacy conserva sus firmas. Esta entrega no habilita configuración ni ejecuta lifecycle.

## Métodos públicos del proveedor

Paquete `com.willfp.ecoskills.api.enchanting`:

- `Readiness readiness()`
- `Access registerConsumer(Plugin owner, EnchantingXPConsumer consumer)`
- `void unregisterConsumer(Plugin owner, EnchantingXPConsumer consumer)`
- `void registerPurchaseAuthority(Plugin owner, EnchantingPurchaseAuthority authority)`
- `void unregisterPurchaseAuthority(Plugin owner, EnchantingPurchaseAuthority authority)`
- `Optional<Session> session(UUID playerId)`
- `CompletionStage<EnchantingXPConsumer.Attribution> preparePurchase(Plugin owner, UUID playerId, UUID operationId)`
- `CompletionStage<Observation> grantConfirmedPurchase(Plugin owner, EnchantingPurchaseAuthority.Confirmation confirmation)`
- `CompletionStage<Integer> recoverDeliveries(Plugin owner, int limit)`

Las entradas anteriores requieren MAIN. Solo pueden registrar/conceder los objetos Plugin reales que Bukkit identifica como vEnchanting/vPets, según su función. El estado del propietario se compara por identidad, no por un nombre que mande el llamador. Es coordinación entre plugins de confianza en el mismo JVM, no aislamiento frente a un plugin malicioso.

Las capacidades de recibo ya existentes se conservan; sus métodos se pueden invocar desde cualquier hilo y hacen el SQL fuera de MAIN:

- `Access.lookupReceipt(ReceiptKey)` → `CompletionStage<Observation>`
- `Access.verifyDelivery(EnchantingXPConsumer.Delivery)` → `CompletionStage<Observation>`
- `Access.acknowledgeDelivery(EnchantingXPConsumer.Delivery, EnchantingXPConsumer.Consumption)` → `CompletionStage<Ack>`

No se expone SQL, pool ni Connection por el proveedor o su SDK. `ProfileMutationAccess` es un SPI privilegiado de eco para su productor nativo; no es una API para vNetwork, web, bot, vPets ni vEnchanting. Compartir MySQL no autoriza consultar datos de otros plugins.

## Antes de checkout: atribución original durable

1. El cliente obtiene el servicio y comprueba readiness; ninguna respuesta ausente significa nivel0 o compra autorizada.
2. Crea una sola operationId para esa operación y llama preparePurchase ANTES de cobrar o aplicar el encantamiento. No usar session(UUID) como precondición de disponibilidad: es un snapshot estricto que puede estar vacío tras20s. preparePurchase readmite/reconcilia de forma asíncrona si hace falta y devuelve la Attribution durable; esperar su éxito antes de cobrar. Una compra nueva exige jugador online y misma sesión a través de la admisión. Una operación ya capturada o con intent durable conserva la atribución original al reenviar, no adopta la mascota/sesión actual.
3. EcoSkills retiene durablemente la intención y su sesión original ANTES de invocar capture. El consumidor captura en MAIN y persiste su contexto propio asíncronamente; el proveedor conserva captureId/request/token después de su confirmación.
4. Solo completar esa preparación permite comenzar checkout. Ante timeout, repetir con la MISMA operationId. La intención y captura anteriores se recuperan sin escoger una mascota/sesión nueva, incluso tras reconectar.
5. vEnchanting guarda la Attribution recibida junto a la operación. Sus campos no son prueba del pago ni del ítem entregado.

La sesión del proveedor y la sesión interna de vPets son distintas. El token opaco no se interpreta, no se publica en web/bot y no se reconstruye desde una mascota actual.

## Confirmación genuina que debe proporcionar vEnchanting

Implementar `EnchantingPurchaseAuthority.verifyConfirmedPurchase(Confirmation)` → `CompletionStage<Verification>`; entrada MAIN y toda consulta/escritura de su ledger asíncrona. Confirmation contiene scope, operationId y deliveryReceiptId; es una clave de consulta, NO una afirmación de éxito.

La autoridad debe leer sus recibos durables y devolver:

- `CONFIRMED` solo cuando cobro válido y mejora real entregada estén confirmados/reconciliados por SU autoridad. Debe devolver la evidencia CANÓNICA original y la Attribution ORIGINAL persistida.
- `NOT_COMMITTED` para una compra aún no confirmada, `UNKNOWN` si hay incertidumbre o timeout, `REJECTED` para un rechazo definitivo. En esos casos evidencia/atribución son ausentes y no hay concesión.

Evidencia completa obligatoria: operationId, playerId, scope, sessionId ORIGINAL del proveedor, enchantmentKey registrado, fromTier/toTier reales, mode, vanillaLevelsConsumed REALMENTE consumidos, tariffVersion, contextHash SHA256 de los hechos inmutables de compra, deliveryReceiptId y confirmedAtEpochMillis originales. Además captureId/request/opaqueToken originales completos.

No fabricar CONFIRMED copiando datos de la solicitud, por un booleano, por disponer de un hash/awardId, por ver ahora un ítem con ese nivel, ni por haber cobrado solo una parte. Un fallo parcial de inventario/pago/callback exige reconciliar su ledger. La API de EcoEnchants no confirma por sí sola la compra durable.

EcoSkills compara el retorno con la intención/captura retenida y conserva payload/atribución. Un recibo EcoSkills ya APPLIED se devuelve de forma estable sin volver a calcular modificadores ni depender de una nueva consulta al ledger origen; cambiar deliveryReceiptId para la misma operación es conflicto.

## Concesión, progreso y cap

vEnchanting llama grantConfirmedPurchase únicamente con la misma Confirmation, después de su commit legítimo. El proveedor consulta la autoridad real para una operación nueva; no acepta una cantidad de XP del cliente.

Fórmula aprobada: base `3.5 * L^1.5`. Vanilla y vanilla superior usan los niveles Minecraft realmente consumidos. Custom shards/gemas usan `L = 6 * (toTier-fromTier)` y consumo vanilla0. III→IV custom da51.43928459844674 antes de modificadores. Omite el contador enchant_item×3; mantiene skillXPMultiplier legítimo y PlayerSkillXPGainEvent cancelable una vez. No emite EnchantItemEvent sintético ni un segundo PlayerSkillXPReceivedEvent legacy sin correlación para esta compra.

La intención pasa por CALCULATING con token/lease antes del callback de modificadores. El importe resultante se guarda PREPARED y se reutiliza. Un callback incierto/vencido pasa a REVIEW, sin dispararse otra vez a ciegas. Si falta la sesión original antes de calcular, no se escoge una sesión nueva para inventar su contexto. Una preparación ya calculada puede terminar usando el lease de escritura actual y mantener la atribución original.

El cap500000 de XP FINAL por jugador/scope/día se aplica después de modificadores. Día UTC y reloj de la BD, según el momento de concesión. Se comprometen juntos el progreso nativo INT/DOUBLE, las recompensas nativas de stats/efectos, revisión, gasto diario, recibo y outbox. Cero por cap produce un recibo completo sin entrega positiva a vPets. No cambian niveles base, multiplicadores, recompensas de skills ni almacenamiento legacy.

La ruta de concesión se serializa por operaciones completas; durante el commit se retienen llamadas nativas completas, no valores absolutos calculados antes. El escritor captura origen antes de cache/coalescer/ejecutor, drena lo pendiente y cerca SQL por sesión/revisión/secuencia. Una escritura atrasada no puede deshacer la concesión. Después del commit se publica una sola vista en la caché y solo se invalidan los holders/stat-icons afectados de ese jugador. Los modificadores decimales y la mecánica de vida sin curación se conservan.

La admisión de una MUTACIÓN puede crear el objeto de perfil (sin SQL) y calentar valores usando filas/ausencias conocidas consultadas asíncronamente y defaults nativos registrados. No crea filas por observar ni cambia las APIs de observación: sus llamadas siguen sin cargar perfiles o inventar defaults. Maná, preferencias y datos ajenos a las claves de progreso no son reemplazados por esta publicación.

## Entrega, ack y recuperación

FINAL_XP puede entregarse en otro nodo conservando evidencia, awardId, importe y atribución originales. vPets debe verificar el recibo y el attempt actual por Access.verifyDelivery, y devolver APPLIED/ALREADY_APPLIED solo después de SU commit. El proveedor confirma ese MISMO Delivery/attempt usando acknowledgeDelivery. RETRY/REVIEW/CONFLICT no son ack exitoso. Un intento antiguo/vencido no confirma otro nuevo.

El adaptador ReceiptVerifier de vPets reconstruye su fingerprint con los campos DEVUELTOS por la verificación y el attempt que esta comprobó. `receiptPayloadHash` del proveedor NO es `validatedConsumerPayload` de vPets: este último sigue siendo su XPDigest.delivery del payload validado. El SDK consumer1 conserva sus firmas.

Recuperación acotada al iniciar, registrar consumidor, admitir/reconectar/transferir, terminar una publicación y mediante recoverDeliveries explícito (1..32 por tipo). No polling periódico. Callbacks de nivel se reclaman solo para sesiones locales o cuando ya no existe lease original vivo; no se roba trabajo fresco de otro nodo. RUNNING vencido y cálculo incierto pasan a REVIEW. Recompensas de stats/efectos ya están comprometidas; el callback solo notifica PlayerSkillLevelUpEvent y la cadena nativa, sin llamar giveRewards otra vez. No se redirigen callbacks a una sesión nueva. Las operaciones legacy encoladas al retirarse una sesión se señalan para revisión, no se ejecutan contra otra sesión ni se prometen durables: la garantía durable corresponde a compras con recibo.

## Límites y semántica de fallos

MAIN: Bukkit, condiciones/callbacks nativos, captura, publicación y registro. JDBC propio fuera de MAIN. Un worker de concesiones, cola64 y admisión64 que retiene plaza mientras el flujo real siga pendiente; cola nativa hasta64 operaciones completas por perfil y hasta512 perfiles. La persistencia ordinaria de eco conserva su ejecutor/coalescer nativos; no se afirma haber acotado todas las colas legacy de todos los plugins.

Respuesta pública15s, receipt-service5s; statement timeout2s y deadline transaccional comprobado antes de statements/commit. No equivalen a interrumpir físicamente adquisición/driver/commit. Un flujo tardío puede haber comprometido: timeout/UNKNOWN no autoriza otro ID, otra concesión, devolución automática ni otro callback. Consultar el recibo original y repetir solo la misma Confirmation. Una publicación fallida conserva el commit y permite reconciliar la caché en la siguiente admisión, sin recalcular la concesión.

Cierre revoca accesos, impide nuevas admisiones, cierra workers/timers y resuelve respuestas pendientes; debe ocurrir antes del pool de eco. No convierte una operación incierta en rechazo. No se purgan recibos ni se reciclan IDs sin un contrato posterior de retención/replay. Cambios de scope, migración del handler o cambio de reglas requieren coordinación explícita; no se ejecuta un upgrade automático de un esquema draft desconocido.

## API pública de mejora de EcoEnchants

Clase `com.willfp.ecoenchants.api.EnchantUpgradeAPI`, CONTRACT_VERSION1:

- `validateUpgrade(Player, ItemStack, Enchantment, int expectedFrom, int target, LevelPolicy)` → Validation
- `applyUpgrade(Player, int storageSlot, ItemStack expectedItem, Enchantment, int expectedFrom, int target, LevelPolicy, UUID operationId)` → Result

Ambas MAIN. Ítem único en slot de almacenamiento del jugador, comparación completa de amount/metadatos; el addon debe mantener esa custodia/slot bloqueado durante checkout o adaptar su entrega explícitamente, no mover el ítem a otro inventario suponiendo que esta API lo seguirá. El objeto original se clona; se cambia solamente el encantamiento correspondiente, almacenado correctamente si es libro. Se preservan los demás componentes/PDC/Nexo/enchants. Se vuelve a comprobar el ítem y requisitos después del evento previo.

Reutiliza target/conflictos simétricos, prerrequisitos de otros encantamientos y límites nativos de tipo/cantidad. Al mejorar se excluye solo la instancia anterior del mismo enchant de esa comprobación, evitando contarla como otro enchant. Usa los bloques nativos compilados `has_skill_level` como desbloqueos de compra y exige sus niveles en caché; ausencia no se convierte en0. Las otras condiciones upstream de ACTIVACIÓN (por ejemplo vida baja o agacharse) siguen en sus holders y no se convierten artificialmente en requisitos para comprar. No cambia discovery, catálogo, pagos, precios ni genera XP.

LevelPolicy.NATIVE_MAXIMUM conserva el máximo nativo. VANILLA_ONE_ABOVE admite únicamente un nivel adicional vanilla, nunca aumenta el máximo custom. El addon sigue siendo responsable de autorizar/cobrar esa modalidad según las reglas aprobadas.

Eventos nuevos: EnchantUpgradePrepareEvent cancelable antes de escribir y EnchantUpgradeAppliedEvent después. Incluyen operationId y copias de ítems; se conserva el refresco nativo de holders. Resultados: APPLIED; APPLIED_CALLBACK_REVIEW si el ítem cambió pero falló el callback; UNKNOWN_APPLY_REVIEW si no puede afirmarse el resultado de la escritura; DENIED/ITEM_CHANGED/CANCELLED antes de aplicar. Los dos REVIEW no autorizan reintentar o reembolsar a ciegas. Bukkit gestiona por separado errores de listeners: retornar del dispatch no prueba su commit interno.

operationId en el evento correlaciona la llamada, no es un recibo durable ni deduplica compras. Esta API no almacena la compra ni demuestra cuál operación produjo un ítem que ya tiene ese nivel: vEnchanting debe resolver ese resultado con su ledger/custodia y solo entonces confirmar al proveedor.

## Implementación y aceptación

Implementado en EcoSuite: cola completa, handler MySQL inicializado, fences/flush/publicación/sesiones/recuperación, concesión pública durable y API nativa de mejora. Pendiente FUERA de EcoSuite: autoridad real de vEnchanting y adaptador/bootstrap de vPets; no se tocaron sus fuentes.

Pruebas JVM/H2 y preservación binaria detalladas en manifest.json y test-results; no equivalen a MySQL, Paper real, 60–100 jugadores o verificación de inventario/callbacks en juego. Pendientes de aceptación: MySQL/InnoDB/pool/driver, Test, dos nodos y transferencia real, caída física en cada frontera commit/ack, pago+ítem reales, metadatos Nexo, condiciones/holders/eventos en servidor y carga. No se instaló ni ejecutó lifecycle. vPets1.30.0 sigue sin instalar. Artefactos/hashes en SHA256SUMS; cambios ajenos y runtime original se preservan mediante composición explícita y verificación de miembros/ABI.

## Bootstrap y evidencia final
Registrar autoridad y consumidor al aparecer el servicio aunque readiness aún indique consumer_missing/source_missing. Condicionar solo la compra a readiness; esperar ready antes de registrar crea un bloqueo circular.
SDK compileOnly/provided; resolver dependencias normales Paper/Adventure/Guava/anotaciones. La muestra pública Java compila y el consumidor vPets4781ed1 enlaza sin construcción ni ejecución.
190 pruebas JVM/H2/simuladas sin fallos: EcoSkills109, eco-api37, eco-native6, EcoEnchants38. Pendientes MySQL real, Paper/Test, transferencia real, inventario/callbacks y carga60–100. No instalados ni lifecycle ni SQL real. Artefactos/hashes en manifest.json y SHA256SUMS de runtime-20261015.

## Admisión individual — corrección 2026-10-16

session(UUID) sigue siendo cache-only, sin efectos secundarios, y Optional.empty NO equivale a proveedor global caído. La ventana local20s y lease SQL60s se mantienen. preparePurchase usa las APIs existentes para esperar flush/admisión SQL/snapshot/publicación y un fence válido antes de capturar. Readmisiones simultáneas se agrupan; una captura nueva con sesión cambiada o jugador offline se rechaza. Si hay un grant nativo en curso, la readmisión de compra espera su pipeline para no sobrescribir su publicación. Una finalización vieja no retira ni descongela la admisión de una sesión nueva.

Timeout/error de admisión no autoriza pago ni otra operationId; el intento y la captura durable existentes conservan su identidad. No se repiten escrituras de concesión por caducar readiness. Sin polling ni TTL ampliado. Los fallos de admisión incluyen epoch/estado local/fenceRevision y causa. El comportamiento requiere adaptar la preconsulta bloqueante de vEnchanting en su chat. Ver PLAYER-SESSION-HANDOFF.md.
