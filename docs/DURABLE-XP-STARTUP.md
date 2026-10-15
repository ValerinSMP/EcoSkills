# Arranque durable XP — causa y orden, 2026-10-15

## Evidencia viva del fallo, lectura sin lifecycle
Test tenía EcoSkills SHA256 7682b7d9e2c12b8e435b3cd37d92e13d59a37dc4953c89d3a57d19cff1f057fb y eco 9ddab98da90bc69e740cb26337012bec574ecc6cf2c5f586daa2314120b4261a.
latest.log:18:14:28 EcoSkills registra Durable XP configuration unavailable:IllegalStateException;18:14:38 vEnchanting registra xp_provider_unavailable.
El logger anterior eliminaba mensaje y stack: no existe una excepción completa histórica para recuperar. El control de flujo y orden del bytecode instalado explican el fallo: handleEnable inicia el proveedor antes de poblar Skills; error(Native enchanting skill absent) lanza IllegalStateException. No se ha fabricado una traza del proceso anterior.

## Orden comprobado contra el bytecode real entregado/instalado
Se guardan EcoPlugin.javap.txt, EcoScheduler.javap.txt, EcoImpl.javap.txt, ProfileHandler.javap.txt y LibreforgePlugin.javap.txt (loader reubicado dentro de EcoSkills).
1. EcoImpl construye ProfileHandler; este crea defaultHandler con PersistentDataHandlerFactory.create. MySQL crea su HikariDataSource en construcción. Si esa construcción falla, eco no proporciona un handler MySQL listo.
2. EcoPlugin.onEnable programa reload(false) para1tick y afterLoad para2ticks, y ejecuta handleEnable inmediatamente. LibreForge solo precarga categorías shouldPreload; Skills usa el valor por defecto sin precarga.
3. En reload, handleLifecycle ejecuta START (LibreForge carga categorías), handleReload y END.
4. EcoPlugin.afterLoad ejecuta callbacks afterLoad y DESPUÉS vuelve a llamar reload(), cancelando tareas Bukkit del plugin antes de handleLifecycle. Una tarea programada desde afterLoad se cancelaría.
5. Corrección: enable solo arma el registro una vez. El callback afterLoad marca esa fase; onReload END inicia el proveedor una sola vez tras las categorías y handleReload de la última recarga interna. La primera recarga no lo inicia.
6. initialize conecta/verifica el handler real, writer y esquema async; solo tras su finalización registra EnchantingXPProvider. Baja/desactivación evita registro tardío.
7. Recargas posteriores mantienen configurationChanged_requires_restart; no reconstruyen proveedor, sesiones ni outbox.

## Validación realizada y límites
114 pruebas EcoSkills sin fallos/errores/omisiones. Cinco nuevas: doble enable/doble reload y dependencias listas; cierre previo; fallo conserva causa y no reintenta; plugin desactivado; error de registro único. Siete regresiones RuntimeProvider pasaron también en la ejecución dirigida.
Ensayo de orden determinista JVM más contraste estático del bytecode real; NO es un arranque Minecraft nuevo. Sin instalación/lifecycle/SQL real. El arranque corregido, registro de servicio y compra real siguen pendientes de aceptación Test por el propietario.

## Entrega
Solo cambia EcoSkills. Contrato público de compra y DTO idénticos byte a byte; no hace falta SDK nuevo. Recibos, grant, cap, deduplicación, progreso y outbox conservados.
No ediciones vEnchanting/vPets/eco/LibreForge. Fallos de configuración, backend y registro ahora conservan mensaje, excepción y causas completas mediante Logger.log(SEVERE,message,Throwable).
