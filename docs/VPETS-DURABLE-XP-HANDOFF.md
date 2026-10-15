# vPets — proveedor runtime, 2026-10-15
Contrato definitivo: ENCHANTING-PROVIDER-CONTRACT.md. Consumidor4781ed1 conserva ABI v1:10 clases idénticas y enlace probado sin ejecutar bootstrap.
Descubrir servicio al iniciar/ServiceRegisterEvent; registerConsumer aunque readiness aún esté incompleto, conservar Access y revocarlo al retirar servicio. capture/consume entran MAIN; ledger propio async.
Verificar Access.verifyDelivery(delivery), deduplicar durablemente por awardId y conservar mascota/sesión originales. APPLIED/ALREADY solo tras commit durable. Proveedor hace ack del mismo intento; timeout no permite otra concesión.
Runtime implementado, no instalado. Adaptador/bootstrap y prueba real MySQL/transferencia/crash pendientes. vPets1.30.0 sigue fuera. No se editó vPets.
