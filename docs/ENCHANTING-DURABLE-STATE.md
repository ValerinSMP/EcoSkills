# EcoSkills — incremento transaccional de mesa

## Alcance y estado

Autorizado recibo/outbox durable con awardId estable y deduplicación del consumidor por el propietario2026-10-15. Fuente canónica EcoSkills personal; cambios ajenos preservados, sin cambios a vPets/vEnchanting. Implementación del núcleo de almacenamiento y contrato de coordinación; la concesión pública completa todavía NO está conectada al runtime. No se habilitaron compras ni se instaló un plugin, no hubo SQL en datos reales ni lifecycle.

No hay nuevo canal directo entre chats: coordinación preparada en [VPETS-ECOSKILLS-DURABLE-XP-HANDOFF-20261015.md](VPETS-ECOSKILLS-DURABLE-XP-HANDOFF-20261015.md). Falta implementación/acuse del chat dueño de vPets y contrato de verificación de compra genuina de vEnchanting. No equivale a consumidores conectados.

## Implementado en código, sin activación

- DurableEnchantingStore: transacción JDBC de recibo, progreso nativo por gateway, contador diario y outbox. ID/payload repetido consulta el recibo; payload/captura/evidencia distintos generan conflicto. Hash ligado a ámbito/jugador/sesión/operación/award/captura/token/evidencia. La evidencia completa se conserva para reconstruir la entrega tras reinicio, no solo su hash.
- Límite500.000 de XP FINAL, después de modificadores; reset medianocheUTC y producción usa reloj MySQL dentro de transacción. Clock inyectado solo en constructor package-private de pruebas. Un award truncado a0 no genera notificación ni nuevos level-ups.
- Fence por ámbito/jugador: lease de sesión y revisión monotónica; guardedNativeWrite compara estado anterior y revisión, también incrementa revisión al escribir. Rechaza escrituras antiguas y casoABA. ESTO ES LA PRIMITIVA, todavía no está aplicada a cada escritura normal de eco.
- JdbcNativeEnchantingGateway: usa tablas INT/DOUBLE nativas con UUID binario y claves ecoskills, sin tabla paralela de progreso. Guarda progreso y recompensas INT nativas en la misma transacción; protege colisión de claves, overflow y doble proyección. Recibe defaults/reglas nativos del futuro adaptador, no los obtiene mediante reflexión ni desde el addon.
- Outbox FINAL_XP reclama con token/lease; al vencer permite reenvío con awardId, cantidad, evidencia y atribución original intactos. Ack de un intento viejo no confirma uno nuevo.
- LEVEL_EFFECT se reclama antes de ejecutar callbacks externos. Si el proceso cae en estadoRUNNING y el resultado es incierto, pasa aREVIEW, no ejecuta comandos/listeners de premio otra vez automáticamente. Recompensas nativas de stats/efectos se comprometen como cambios de claves; no se vuelve a llamar giveRewards al repetir un recibo.
- EnchantingXPConsumer: contrato público de captura original y consumo asíncrono con APPLIED/ALREADY_APPLIED/RETRY/REVIEW/PAYLOAD_CONFLICT. La estructura de Delivery verifica award/jugador/operación/ámbito/sesión, pero no sustituye la verificación del recibo en la autoridad.

## Frontera que falta y mantiene las compras deshabilitadas

ProfileWriter/handler actuales no transportan una revisión de perfil al encolar cada escritura; registrarla solo al ejecutar el SQL es demasiado tarde. Pendiente integrar una capacidad transaccional acotada de eco con el pool ya inicializado y el mismo fence, sin exponer DataSource/Connection/SQL arbitrario como API a consumidores. No se ha añadido un segundo pool ni se han copiado credenciales.

Hay que cubrir escrituras pendientes, cache local, transferencia/sesión y actualizaciones nuevas durante el commit. SkillLevelMap nativo ejecuta callbacks/recompensas mientras cambia nivel/XP: no basta cambiar el SQL final ni llamar gainXP con un receipt al lado. La ruta durable debe proyectar las reglas nativas, confirmar en DB, publicar el estado coherente y despachar callbacks/holders una sola vez mediante la outbox apropiada; conservar el legado y el puente final.

Pendientes también: fachada pública de concesión/capacidades/consulta/ack, autenticación de confirmación de vEnchanting, captura/consumo durable real de vPets, pool/cola/concurrencia/deadline/cancelación del servicio completo y recuperación operativa deREVIEW. El núcleo tiene query timeouts y límite de transacción, pero eso NO prueba una cola de runtime acotada ni un timeout total incluyendo adquisición del pool.

La protección real frente a escrituras tardías NO se afirma hasta instrumentar y probar el escritor normal; por ahora se comprobó exclusivamente el contrato guardedNativeWrite. Ninguna capacidad de compra se registra al cargar estas clases. level_missing sigue pendiente de captura en sesión real; su helper diagnóstico no se instaló ni se invocó en juego.

## Pruebas y límites de evidencia

Pruebas H2 persistente en disco temporal de personal, modoMySQL: reconstrucción de store/conexiones tras commit sin respuesta; repetición; payload distinto y captura distinta; rollback después de modificar progreso/recompensas; cap compartido entre instancias y resetUTC; lease de otra sesión; rechazo de escritura/revisión vieja/ABA; outbox replay/ack viejo; callbacks inciertos aREVIEW; cambios de claves INT/DOUBLE nativas y rollback de recompensas; namespace ajeno/colisión; consumidor SIMULADO con transacción propia antes de ack y deduplicación después de reenvío. No se ejecutó código de vPets en esa simulación.

Se corrigió una carrera detectada: tras esperar la fila del fence se reconsulta el recibo incluso si cambió la revisión, para que dos llamadas iguales reciban el mismoAPPLIED. Los tests no son una caída física del proceso MySQL ni una prueba de dos servidores Minecraft.

H2 no demuestra semántica MySQL/driver/pool real, registro de sesiones ni perfiles/cache vivos, callbacks reales o rendimiento60–100 jugadores. Pendientes esas verificaciones, ABI frente al runtime preservado e instalación segura en Test de la ruta completa; no instalar un coreJar íntegro sustituyendo modificaciones actuales.

## Artefactos y resultado final del incremento

Última suite completa:82tests,0fallos/errores/omisiones;16nuevos respecto de66 del primer incremento. Build personal limitado, JDK25/release21, Kotlin/Gradle nativos y Maven de observación preservado. Cambios ajenos hasheados e idénticos; H2 solo dependencia test, no runtime.

- `EcoSkills-enchanting-contract-draft2-api-2026.27.jar`: SHA256 `0a222a467469ef34600061f0523b657e276a846b1c0bc967a998d4b16e9051d3`. Desarrollo/compileOnly, NO plugin instalable.
- `EcoSkills-enchanting-durable-development-2026.27.jar`: SHA256 `b816901cbe2cb1087146d1a48e95174767a0b8b33d78d50e58301f5f48a9b4dd`. Desarrollo/compileOnly, NO plugin instalable.

El manifiesto en artifacts/eco-suite/enchanting-xp-20261015/durable-increment registra que nativeWriterIntegrated=false,vPetsIntegrated=false,installed=false,active=false y realMySqlTested=false. No hay JAR runtime de compras listo. No instalar core-plugin.jar íntegro ni estos JARs de desarrollo en plugins/.
