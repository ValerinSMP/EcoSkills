# Incremento preliminar de XP de mesa — 2026-10-15

- Añadidos tipos públicos puros de evidencia/hash/identidad estable, fórmula3,5×L^1,5 y diagnóstico cache-only con motivo. Ninguna mutación de XP, fachada, fuente ajena, vEnchanting o vPets modificada.
- Compilado personal/ejecutor limitado/JDK25/release21 con Maven observation-20261013. 66tests pasaron,10nuevos. Contrato preliminar compileOnly y hash en docs/ENCHANTING-PURCHASE-XP.md; no es proveedor de concesión.
- Pendientes: resolver semántica de notificación tras crash con propietario, proveedor durable/atomicidad/progreso/cap/fences/outbox e integración real. No afirmar idempotencia durable ni corrección runtime de level_missing. Sin instalación, SQL o lifecycle; Custom/Sync intactos.
- Estado anterior y cambios ajenos a continuación preservados; commit solo de esta sección y archivos nuevos propios.

# Estado actual 2026-10-13 — API de observación EcoSkills

- Fuente canónica personal; eco/EcoSkills modificados sin normalizar upstream. Cambios previos ajenos preservados y excluidos del commit.
- Implementados cache UUID/schema1/schema2 y almacenamiento asíncrono individual/batch; DTO inmutable, presencia explícita, decimales nativos, backend MySQL inicial y límites de cola/concurrencia/deadline/cancelación. Contrato docs/OBSERVATION-API.md.
- Compilado solo personal, ejecutor limitado/toolchains nativos; bytecode 21. Pruebas ejecutadas: EcoSkills 56, eco-api 33 y NativeObservation 4; cero fallos. Nueve tests antiguos de DisplayFrame en reportes almacenados NO se ejecutaron en esta tarea y no se cuentan.
- Runtime compuesto sobre baselines a45736e0 (eco) y 8f7a682e (EcoSkills). 7743/655 entradas restantes y 205/132 métodos anteriores verificados; solo constructor/hook de escritura normal de MagicMap agregan observación. PersistentDataHandler submit/Future.get y puente XP final se preservan. Un build completo del árbol no sustituye esta entrega.
- Instalado/activo únicamente Test: eco c79d520ebadf2cfff54e9fb275dfdf3b5785361ad2bcf7ba1363cf54ec02ed55; EcoSkills 1ad3d5524adaa56fb0e9be88a9b76b479615b38c03b12d9a3b5d3d81b6e73378. API compilación 2bc2d96965eb26dffefaf857a00f96d0bd5c5c10e170f9943c9a1a5b44a980aa. No descriptor de plugin en API; compileOnly/provided.
- Respaldo original HDD /mnt/storage/backups/ai/survival-custom/ecoskills-observation-20261013T233634Z; entregas /ecoskills-observation-delivery-20261014T005511Z y /ecoskills-observation-delivery-20261014T014342Z en el mismo ámbito HDD. Probe temporal deshabilitado/retirado del volumen con copia verificada en HDD.
- Test reiniciado/arranque y reload observados con cero jugadores y autorización de ESTA tarea. Cache fría no cargó perfil; consulta SQL de UUID conocido recuperó 64 campos propios en batch de dos UUID; DTO inmutable y rechazo async de caché comprobados. Reload elevó generación 3 a 4. vPets informó puente XP final conectado y 11/11 stats de 12 especies.
- Pendiente verificación en juego con jugador real, comparación cálculos/efectos/mana actuales, reconexión/transferencia real y carga de 60–100 usuarios. Identidad de sesión/reconexión se probó con mocks; no presentarla como prueba multiserver real. Sin benchmark medido.
- Custom/Sync no instalados ni lifecycle. vNetwork sin editar; handoff en docs de HomeSV. Fuente ajena pendiente de XP/AI permanece intacta; no ejecutar instrucciones antiguas inferiores.

## Contexto previo — sin órdenes vigentes

# EcoSkills — estado

- Fuente canónica: personal `/root/development/projects/eco-suite/EcoSkills`. HomeSV conserva la referencia sin editar. Original comparado: `upstream-review/main`, SHA en `upstream-differences-final.json`; sin merge, checkout o actualización completa.
- Tarea autorizada: las ocho prioridades pendientes (3–10) y las dos funciones de EcoArmor, solo Test.
- Modificado: Placeholder percentage_progress nativo en GainXPDisplay. Preserva XP ofensiva, protección frente a muertes duplicadas, regeneración, clasificación y callbacks existentes. Configuraciones productivas intactas.
- Archivos: código/configuración de fuente y tests nativos del cambio; inventario exacto en git-final.json. DEVELOPMENT_HANDOFF.md ajeno/no versionado preservado.
- Compilado: sí, ejecutor personal 2 CPU/4 GiB/sin swap, una build, Gradle Wrapper original, un worker y Kotlin en proceso. Toolchains/release por módulo conservados. Eco/EcoEnchants/EcoSkills utilizan la opción nativa `paperweight.experimental.genSources=false`, que evita generar fuentes auxiliares y conserva los adaptadores.
- Probado: 15 pruebas JVM, 0 fallos, 0 errores, 0 omitidas. Suite total 200. EcoItems no tiene tests propios. No es medición de rendimiento ni prueba en Minecraft de las funciones nuevas.
- Dependencias: eco/LibreForge propios en Maven aislado; versiones/hashes resueltos en los manifests de la tarea. La recompilación final de LibreForge conserva todas las clases usadas por consumidores; solo cambia CRLF del mismo config.yml (libreforge-final-abi.json). Loader y bibliotecas empaquetadas preservados.
- Instalado: sí, únicamente Test `/var/lib/pterodactyl/volumes/f2ea450e-02a0-416c-9838-e73458d37b1c/plugins/EcoSkills-2026.27-all.jar`, SHA256 `490f719edf5bba310ec933466b0647717c74107cbbae6a146fe4b01090c7deca`. Hash en disco verificado; dueño/modo preservados.
- Respaldo verificado: `/mnt/storage/backups/ai/survival-custom/ecosuite-upstream-remaining-20261010-test/`. Anterior SHA256 `1273599d07dfec88e9b5cd7cd55e102faca0b9027a0f35fb9b591bf1e7419efa`. Manifest y reversión junto al respaldo; staging/evidencia en `/home/mtyn/staging/survival-custom/eco-upstream-remaining-20261010/`.
- Activo Test: inicio de los siete plugins observado en latest.log y hashes instalados coincidentes. Gameplay: el propietario informa «funciona todo»; aceptación básica de la entrega, sin registro individual de todas las pruebas dirigidas. El agente nunca reinicia ni recarga, incluido Test. No se ejecutó lifecycle, BD ni pack; no se editaron YAML vivos. Custom/Sync no instalados/modificados; los 14 JAR productivos conservaron sus hashes al verificar la entrega.
- Siguiente paso: entrega aceptada de forma básica en Test. Candidato siguiente: revisión acotada de homing usado por el Arco de Enderita, todavía sin aplicar. Custom/Sync y transferencia requieren autorización futura; Test 1.21.11 observado, Minecraft 26.2 no probado en juego.
- Git: cierre de cambios propios autorizado tras aceptación en Test. Resultado de commit/push normal en git-closeout.json; restauraciones preexistentes bajo target y DEVELOPMENT_HANDOFF.md se mantienen fuera del commit.
