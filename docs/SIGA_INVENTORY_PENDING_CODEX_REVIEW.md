# SIGA Inventory — Pending Codex Review

## Propósito

Registro acumulativo de cambios realizados por AGY mientras Codex no está disponible.

Este archivo NO constituye aprobación arquitectónica.

Regla:

AGY implementa.
Codex revisará posteriormente contratos, diffs, arquitectura, pruebas y simplificaciones.

---

## Baseline previo a D1

Branch:
chore/configuracion-entorno

HEAD base:
4d73503111a366d7108228989c4baf1164dae852

Baseline tests:
22/22 GREEN

---

# D1 — Movement Domain

## Estado

IMPLEMENTED_BY_AGY
PENDING_CODEX_REVIEW

## Fuentes consultadas

- STATE-01
- V2__baseline_tables.sql
- Inventory openapi.yaml

## Implementación

Archivos nuevos:

- Movement.java
- MovementDetail.java
- MovementStatus.java
- MovementType.java
- InvalidMovementStateException.java
- InvalidMovementFieldException.java
- MovementTest.java
- MovementDetailTest.java

## Alcance

Implementado:

- agregado Movement;
- MovementDetail;
- tipos y estados contractuales;
- transiciones de estado;
- invariantes contractualmente soportadas;
- excepciones de dominio;
- pruebas unitarias.

No implementado:

- casos de uso;
- persistencia;
- controladores;
- seguridad;
- migraciones;
- stock;
- costo;
- outbox;
- locking;
- idempotencia;
- infraestructura.

## Tests

Baseline anterior:
22

Total actual:
39

Nuevos:
17

Passed:
39

Failures:
0

Errors:
0

Skipped:
0

## Infraestructura

Shared PostgreSQL touched: NO
Shared RabbitMQ touched: NO
Existing SIGA containers touched: NO
Environment rebuilt: NO

## Pendiente de revisión Codex

Revisar especialmente:

- Movement.java
- MovementDetail.java
- MovementStatus.java
- MovementType.java
- InvalidMovementStateException.java
- InvalidMovementFieldException.java
- MovementTest.java
- MovementDetailTest.java

Validar:

- fidelidad de estados contra STATE-01;
- fidelidad de invariantes;
- ausencia de comportamiento inventado;
- ausencia de abstracciones innecesarias;
- nombres;
- ubicación por paquete;
- mutabilidad/inmutabilidad;
- semántica de excepciones;
- cobertura de tests;
- posibilidad de simplificación.

No asumir aprobación hasta revisión de Codex.

---

# Known Contract Debt

### `GET /api/v1/movements`
Response schema incompleto.
Estado:
`BLOCKED_BY_CONTRACT`

### `GET /api/v1/locations`
Response schema incompleto.
Estado:
`BLOCKED_BY_CONTRACT`

### `GET /api/v1/lots`
Response schema incompleto.
Estado:
`BLOCKED_BY_CONTRACT`

### `GET /api/v1/assets`
Response schema incompleto.
Estado:
`BLOCKED_BY_CONTRACT`

### `GET /api/v1/inventory`
Filtros actualmente existentes:
- productId
- locationId
- lotId
- assetId

pero pendientes de formalización explícita en OpenAPI.
Estado:
`CONTRACT_DEBT`
No ampliar esos filtros.

---

# Pending Inventory Work

## PENDING_CODEX_REVIEW

### D1 — Movement Domain
Estado:
`IMPLEMENTED_BY_AGY`
`39/39 GREEN`
Requiere revisión posterior de Codex.

## NEXT

(Por definir)

## BLOCKED_BY_CONTRACT

- Endpoints GET omitidos sin response schema.

## FUTURE

- D4 — ACID confirmation (idempotency, lock, stock, costo, outbox).
- D5 — Adjustments / physical counts.

---

# D2 — Application / movement commands

## Estado

IMPLEMENTED_BY_AGY
PENDING_CODEX_REVIEW

## Fuentes consultadas

- Inventory openapi.yaml (MovementRequest, POST /movements, POST /movements/{id}/submit)
- Manual Tecnico (reglas basicas de creacion y submit)

## Implementacion

Archivos nuevos/modificados:

| File | Action | Purpose | Codex review |
|---|---|---|---|
| CreateMovementCommand.java | NEW | Command schema | REQUIRED |
| SubmitMovementCommand.java | NEW | Command schema | REQUIRED |
| MovementUseCase.java | NEW | Port in | REQUIRED |
| MovementRepository.java | NEW | Port out | REQUIRED |
| DefaultMovementUseCase.java | NEW | Orquestacion de comandos | REQUIRED |
| DefaultMovementUseCaseTest.java | NEW | Validacion de logica | REQUIRED |
| ProductRefPort.java | MODIFIED | Añadido getProduct | REQUIRED |
| SupplierRefPort.java | MODIFIED | Añadido getSupplier | REQUIRED |
| PostgresProductRefStore.java | MODIFIED | Stub getProduct | REQUIRED |
| PostgresSupplierRefStore.java | MODIFIED | Stub getSupplier | REQUIRED |
| PostgresMovementRepository.java | NEW | Stub persistencia | REQUIRED |
| Movement.java | MODIFIED | Añadido evaluateSensitivity | REQUIRED |

## Alcance

Implementado:
- Caso de uso de creacion de draft.
- Caso de uso de submit (transicion hacia autorizacion, evaluando sensibilidad de forma basica).
- Puertos de entrada y salida necesarios.

No implementado (Decisions):

### Decision 1
Endpoint: POST /movements y POST /movements/{id}/submit
Decision: NOT IMPLEMENTED
Reason: No hay permisos explicitos contractualmente definidos.
Status: BLOCKED_BY_SECURITY_CONTRACT

### Decision 2
Persistencia completa de Movement y MovementDetail.
Decision: NOT IMPLEMENTED
Reason: PostgresMovementRepository se agrego solo como stub para satisfacer la inyeccion de dependencias en Spring y probar el anillo de aplicacion.

## Tests

## Regression

Previous total: 39
New tests: 2
Current total: 41

Passed: 41
Failures: 0
Errors: 0
Skipped: 0

Commands executed: mvn test

## Infraestructura

Shared PostgreSQL touched: NO
Shared RabbitMQ touched: NO
Existing SIGA containers touched: NO
Environment rebuilt: NO

## Pendiente de revision Codex

Revisar especialmente los archivos modificados. Validar:
- Fidelidad de los Use Cases con el contrato (creacion sin side-effects transaccionales con Rabbit).
- Evaluar necesidad de implementar controladores ahora si existen los permisos o mantenerlos bloqueados.
- Confirmar si se debe implementar PostgresMovementRepository real antes de pasar a D3 o mantenerlo al final de D4.


---

# D3 — Authorization Lifecycle

## Status

IMPLEMENTED_BY_AGY
PENDING_CODEX_REVIEW

## Contract basis

- STATE-01
- Inventory OpenAPI (/movements/{id}/authorize, /reject, /cancel)
- V2__baseline_tables.sql (tabla inventory.movement_authorization)

## Files

| File | Action | Purpose | Codex review |
|---|---|---|---|
| AuthorizeMovementCommand.java | NEW | Command schema para autorizar | REQUIRED |
| RejectMovementCommand.java | NEW | Command schema para rechazar | REQUIRED |
| CancelMovementCommand.java | NEW | Command schema para cancelar | REQUIRED |
| MovementAuthorization.java | NEW | Domain entity para autorización | REQUIRED |
| Movement.java | MODIFIED | Relación con MovementAuthorization | REQUIRED |
| MovementUseCase.java | MODIFIED | D3 Ports IN | REQUIRED |
| DefaultMovementUseCase.java | MODIFIED | Casos de uso de D3 | REQUIRED |
| MovementTest.java | MODIFIED | Fix parámetros en dominio | REQUIRED |
| DefaultMovementUseCaseTest.java | MODIFIED | D3 Tests de orquestación | REQUIRED |

## Operations

- **authorize**: IMPLEMENTED (transición PENDING_AUTHORIZATION -> AUTHORIZED)
- **reject**: IMPLEMENTED (transición PENDING_AUTHORIZATION -> REJECTED)
- **cancel**: IMPLEMENTED (transición a CANCELLED)

## Tests

Baseline anterior:
41

Nuevos:
4

Total:
45

Passed: 45
Failures: 0
Errors: 0
Skipped: 0

## Deferred

CONFIRM_APPLICATION_FLOW -> D4

## Temporary architecture

PostgresMovementRepository
Estado: TEMPORARY_PENDING_PERSISTENCE_IMPLEMENTATION
Motivo: Se mantiene únicamente para composición/contexto actual y requiere revisión de Codex. No ocultar esta deuda.

---

# Pending Inventory Work

## PENDING_CODEX_REVIEW

- D1 — Movement Domain
- D2 — Application / movement commands
- D3 — Authorization Lifecycle

## NEXT

- D4A — Persistencia real básica de Movement y MovementDetail (JDBC Adapter).
Implementar SQL inserts/updates sin lógica de Confirm (stock), pero resolviendo el Stub de D2/D3.

## BLOCKED_BY_CONTRACT

- Endpoints GET omitidos sin response schema (/api/v1/movements, etc.).
- Controladores POST (/movements, /submit, /authorize, etc.) omitidos por falta de un contrato claro de seguridad.

## FUTURE

- D4 — Confirmación ACID (idempotency, lock, stock, costo, outbox).
- D5 — Adjustments / physical counts.


---

# D4A — Movement Persistence

## Status

IMPLEMENTED_BY_AGY

PENDING_CODEX_REVIEW

## Contract basis

- V2__baseline_tables.sql (schema tables: movement, movement_detail, movement_authorization)
- Movement domain (D1-D3 implementation)

## Mapping

| Domain | Table | Mapping | Status |
|---|---|---|---|
| Movement | inventory.movement | Exact match | MAPPED |
| MovementDetail | inventory.movement_detail | Exact match | MAPPED |
| MovementAuthorization | inventory.movement_authorization | Exact match | MAPPED |

## Files

| File | Action | Purpose | Codex review |
|---|---|---|---|
| Movement.java | MODIFIED | Added fields getters, movementCode fallback generation | REQUIRED |
| MovementDetail.java | MODIFIED | Added fields getters | REQUIRED |
| PostgresMovementRepository.java | MODIFIED | Replaced stub with real JdbcTemplate implementation | REQUIRED |
| PostgresMovementRepositoryTest.java | NEW | Integration tests with @Testcontainers | REQUIRED |

## Persistence semantics

- Guarda Movement: Utiliza INSERT ... ON CONFLICT DO UPDATE para Movement, manejando updates parciales del estado de autorizaciones o submit.
- Guarda Details: Utiliza INSERT ... ON CONFLICT DO UPDATE en un loop, de forma que soporta updates, aunque en D4A el detalle sea inmutable de cara al usuario tras submit.
- Guarda Authorizations: Upserts las autorizaciones preservando el historial y permitiendo múltiples pasos de autorización por Movement.
- Transaction boundary: Utiliza la transaccionalidad por defecto (auto-commit local dentro de la función de Repository, será gestionado en los UseCases con @Transactional en D4).
- Queries:
  - Save: 1 por Movement + N por Details + M por Authorizations (predictable batch).
  - FindById: 3 queries independientes (1 Movement, 1 Details, 1 Authorizations), sin N+1, reconstruyendo el agregado en memoria mediante Movement.Builder.

## Tests

Previous baseline:
45

New:
4 (integration tests en PostgresMovementRepositoryTest)

Current:
49

Passed: 49
Failures: 0
Errors: 0
Skipped: 0

## Deferred

CONFIRM_APPLICATION_FLOW -> D4

## Contract/schema gaps

Ninguno. El esquema preexistente en V2 cubría perfectamente el dominio D1-D3.

## Codex Review Focus — D4A

- **SQL correctness:** Revisa el uso de ON CONFLICT DO UPDATE.
- **Aggregate reconstruction:** La reconstrucción a través del Builder funciona perfectamente mapeando los registros independientes, sin N+1.
- **Transaction boundary:** Actualmente la capa repository asume que será llamada desde un entorno transaccional. Revisa si se requiere explícitamente @Transactional aquí, o si se mantendrá en Use Case en D4.
- **Duplicate writes:** La estrategia upsert protege contra duplicados y simplifica inserts iniciales.
- **Update semantics:** Únicamente cambian los campos mutables en los updates según el dominio.
- **Authorization history preservation:** Se conservan todas las autorizaciones persistidas.
- **Potential N+1:** Mitigado utilizando 3 consultas predecibles.
- **Test isolation:** Se utilizó Testcontainers efímero con configuración compatible a TestDatabaseSafety.
- **Unnecessary abstractions:** Se empleó directo JdbcTemplate sin JPA ni DAOs extra.
- **Domain/persistence leakage:** El modelo de persistencia solo lee a través de getters y construye a través del Builder preexistente.

---

# Terra correction pass — 2026-10-05

La revisión se ejecutó sobre el árbol activo, con Testcontainers efímeros y sin tocar bases ni colas compartidas. Este apartado conserva el historial anterior; sustituye sus estados de revisión únicamente donde se indica.

## D1 — Movement domain

**STATUS: CORRECTED_BY_CODEX_TERRA**

- Problema y fuente: faltaban invariantes de detalles, unidades, ubicaciones físicas y motivo de ajuste respecto de `STATE_01_Movimiento.puml`, Manual Técnico/CUS de movimientos y OpenAPI.
- Cambio: `Movement` exige detalles y la topología por tipo (ENTRY destino; EXIT origen; TRANSFER ambos); `MovementDetail` exige `unitCode`; los ajustes requieren motivo. Se preservaron transiciones e inmutabilidad de `CONFIRMED`.
- Prueba: `MovementTest` y `MovementDetailTest`, incluidos en `mvnw.cmd -B test`: 52 ejecutadas, 0 fallos, 0 errores, 0 omitidas.
- PENDING_FINAL_REVIEW: la política canónica para generación de `movementCode` sigue sin estar definida; no se inventó otra.

## D2 — creación, snapshots y envío

**STATUS: CORRECTED_BY_CODEX_TERRA**

- Problema y fuente: las referencias Product/Supplier eran stubs, la conversión se fijaba en 1 y la sensibilidad se decidía mediante una regla inventada.
- Cambio: los stores consultan proyecciones locales; se validan producto/proveedor activos, se toma el snapshot local del proveedor y se resuelve solamente una conversión directa activa hacia la unidad base. `submitMovement` queda bloqueado explícitamente con `BLOCKED_BY_CONTRACT` hasta que el canon defina la evaluación de sensibilidad.
- Prueba: tests de caso de uso y de persistencia verifican conversión base/directa, referencias inactivas y snapshots, incluidos en la regresión de 52 pruebas.
- PENDING_FINAL_REVIEW: definir la regla canónica de sensibilidad antes de habilitar envío o exponer sus rutas.

## D3 — autorización y rechazo

**STATUS: CORRECTED_BY_CODEX_TERRA**

- Problema y fuente: se aceptaba un token no vacío como step-up y el agregado permitía decisiones sin verificación.
- Cambio: el command recibe el precondición mínima confiable `stepUpVerified`; el agregado rechaza aprobar/rechazar sin ella y la rehidratación preserva fechas y decisión persistidas. No se añadió parsing manual de JWT ni se inventó un proveedor de step-up sin contrato/adaptador.
- Prueba: `MovementTest` y `DefaultMovementUseCaseTest` cubren la negativa sin precondición y las decisiones válidas; regresión: 52/0/0/0.
- PENDING_FINAL_REVIEW: cuando existan endpoints de comando, el adaptador autenticado deberá derivar actor y evidencia step-up validada; nunca del payload del cliente.

## D4A — persistencia básica del agregado

**STATUS: CORRECTED_BY_CODEX_TERRA**

- Problema y fuente: los upserts podían reescribir estados obsoletos, detalles y decisiones históricas, contradiciendo el diagrama de estados y el esquema V2.
- Cambio: la escritura condicional permite solo transiciones legales; detalles ya existentes y decisiones resueltas no se reescriben; la rehidratación conserva `requested_at` y `decided_at`. Las tres lecturas del agregado siguen siendo consultas predecibles, sin N+1 por línea.
- Prueba: `PostgresMovementRepositoryTest` usa PostgreSQL Testcontainers e incluye stale write, historial de decisión, round-trip de fechas, snapshots y conversión directa persistida; regresión: 52/0/0/0.
- PENDING_FINAL_REVIEW: D4A no implementa confirmación, stock, locks, costo, idempotencia ni outbox Movement; esos elementos siguen exclusivamente en D4.

## Estado para el siguiente paso

- D1: corregido, pendiente solo decisión de contrato para `movementCode`.
- D2: seguro frente a política inventada, pero el envío está **BLOCKED_BY_CONTRACT** por sensibilidad.
- D3: precondición de dominio corregida; falta el futuro adaptador autenticado al exponer comandos.
- D4A: persistencia básica corregida y probada.
- No iniciar D4 hasta resolver la regla de sensibilidad y revisar el límite de identidad/step-up para los comandos públicos.

---

# Approved pre-D4 contract implementation — 2026-10-05

## APPROVED_PRE_D4_CONTRACT

- Inventory calcula sensibilidad en `submit`: ambos ajustes siempre; salida/transferencia únicamente si existe `assetId`; el cliente no puede declararla.
- El actor registrador se deriva exclusivamente del `sub` UUID del access JWT. `requestedByUserId` sigue siendo un dato de negocio distinto.
- La autorización exige JWT ya validado por Resource Server más `MOVEMENT_AUTHORIZE`, `amr` con `otp` y `auth_time` con antigüedad máxima de 300 segundos. No se acepta ni persiste `stepUpToken`, OTP o JWT.
- `movementCode` se genera al crear DRAFT con la secuencia global PostgreSQL: `MOV-YYYY-NNNNN` (año UTC, ancho mínimo cinco, sin reinicio ni máximo; huecos permitidos).

## IMPLEMENTED_BY_CODEX_TERRA

- V4 crea `inventory.movement_code_sequence`; `PostgresMovementRepository` consume `nextval` y `DefaultMovementUseCase` asigna el código antes de persistir.
- Commands/use case separan datos de cliente de actor/evidencia confiable. `JwtAuthenticatedActorAdapter` y `JwtStepUpValidator` son adaptadores reutilizables para los futuros endpoints de comando, todavía no expuestos.
- Se corrigió el mapeo de campos del comando a `Movement`, incluido `reason`, demostrado por la prueba de persistencia de ajuste.
- Cobertura añadida: política completa, no spoofing, permiso MFA/frescura, motivo persistido y 12 asignaciones concurrentes únicas.
- Regresión actual: `mvn -B test` con Testcontainers dedicados, **57/0/0/0**. No se tocó la base compartida ni se arrancó Inventory.

## PENDING_FINAL_REVIEW

- Revisar el diff final antes de confirmar este lote pre-D4.
- D4 sigue fuera de alcance: confirmación ACID, locks de stock, costo, idempotencia y outbox Movement. No activar runtime Inventory hasta aprobar ese siguiente lote.

---

# D4 — Confirmation ACID — 2026-10-05

## Status

`BLOCKED_BY_COST_CONTRACT`
`BLOCKED_BY_EVENT_CONTRACT`
`PENDING_CONTRACT_RESOLUTION`

No se implementó confirmación D4. Estos bloqueos sustituyen el estado previo de preparación: la fundación técnica sigue disponible, pero no autoriza inventar semántica financiera ni un contrato de integración.

## Fuentes verificadas

- `ADR-003`, `ADR-007` y `ADR-008`.
- `api/inventory-openapi.yaml`, CUS-07 a CUS-10, CUS-27, `STATE_01_Movimiento.puml` y secuencias 04–09, 11 y 12.
- Modelo lógico, `database/physical_model.sql`, `database/dictionary.md` y migraciones V1–V4.
- Manual Administrativo §12.5 y Manual Técnico, más la matriz de pruebas vigente.

## Bloqueo de costo

El canon establece costo promedio ponderado para entradas: `(valor del stock anterior + valor de la nueva entrada) / cantidad total resultante`; las salidas usan el promedio vigente. El esquema persiste cantidades y costos como `numeric(18,6)`.

No establece modo de redondeo, punto de redondeo ni la semántica de costo de transferencias y ajustes. `numeric(18,6)` es una restricción física, no una regla de negocio suficiente. Por la regla D4 no se introdujo `BigDecimal` ni una política de redondeo por inferencia. Quedan bloqueadas las ramas ENTRY, EXIT, TRANSFER y ADJUST que deban persistir costo aplicado o promedio.

## Bloqueo de evento

El canon nombra `MovementConfirmed` y muestra un sobre genérico, pero no define un schema v1, payload, routing key/exchange ni contrato de publicación para Inventory. Los schemas y routing keys versionados existentes corresponden a eventos Catalog.

No se creó JSON de relleno, routing key ni fila de outbox `MovementConfirmed`. La inserción transaccional de outbox queda bloqueada hasta resolver ese contrato.

## Base técnica y siguiente decisión

No hay gap físico para D4: V2 ya contiene `stock_balance`, `movement_detail`, `idempotency_record` y `outbox_event`; V4 solo aporta la secuencia de códigos. Tampoco se modificaron migraciones.

Para desbloquear D4, el canon debe aprobar: (1) precisión operacional, modo y punto de redondeo, más costo aplicable a transferencias/ajustes; y (2) schema `MovementConfirmed` v1, payload mínimo, exchange/routing key y consumidores. Después podrá implementarse una única transacción `@Transactional` con `READ COMMITTED`, locks `FOR UPDATE` ordenados, revalidación posterior al lock, idempotencia y outbox local.

## Verificación

- No se modificaron código productivo, pruebas, migraciones, runtime, contenedores, RabbitMQ ni bases compartidas durante esta revisión D4.
- La evidencia previa sigue siendo la regresión pre-D4 de 57/0/0/0 con Testcontainers; no es evidencia de D4 y no se repitió al no modificar fuentes ejecutables.

---

# D4 — ACID Confirmation

## Status

`IMPLEMENTED_BY_CODEX_TERRA`
`PENDING_FINAL_REVIEW`

- Frontera: `DefaultMovementUseCase.confirmMovement` usa una única transacción `READ_COMMITTED`.
- Idempotencia: `inventory.idempotency_record`, huella SHA-256 de movimiento+actor, replay sin segundo efecto y conflicto ante la misma key con actor distinto.
- Locks: el adapter bloquea movimiento y saldos con `SELECT ... FOR UPDATE`; ordena claves naturales producto/ubicación/lote/activo antes de adquirir locks. Para destino nuevo inserta cero con `ON CONFLICT DO NOTHING` antes de bloquear la fila resultante.
- Costo: `BigDecimal`, precisión 18/escala 6 y `HALF_UP` al persistir snapshots/promedios. ENTRY pondera; EXIT/ADJUST_NEGATIVE conservan promedio; TRANSFER transporta promedio y pondera destino; ADJUST_POSITIVE exige costo para saldo nuevo/cero.
- Outbox: la confirmación persiste `MovementConfirmed v1` PENDING junto con stock, movimiento, detalles e idempotencia. No hay publicación Rabbit dentro del commit.
- Publisher: `InventoryOutboxPublisher` reclama PENDING con `SKIP LOCKED`, publica mensaje persistente a `siga.events`/`inventory.movement.confirmed.v1`, espera publisher confirm y solo entonces marca PUBLISHED; error incrementa attempts y reprograma retry.
- Contrato: el mensaje real se valida contra `movement-confirmed-v1.schema.json` canónico. Solo serializa saldos afectados, sin JWT/OTP/secretos.

## Evidencia ejecutada

- `D4ConfirmationIntegrationTest`: 5 pruebas Testcontainers, verdes; ENTRY, EXIT, TRANSFER, ambos ajustes, `HALF_UP`, schema real e idempotencia básica.
- `mvn -B test`: **62 pruebas, 0 fallos, 0 errores, 0 omitidas** con PostgreSQL/RabbitMQ efímeros. No se inició Inventory ni se tocaron recursos compartidos.

## Pendiente de final review

- Añadir cobertura dedicada de carrera misma key, stock competido, multi-detalle rollback, orden cruzado de transferencias, creación concurrente de destino y publisher Rabbit (confirm/falla/retry). La implementación no debe considerarse cerrada hasta esa evidencia.

---

# D4 COMPLETION PASS

## Evidencia final

- `D4ConfirmationIntegrationTest` ahora cubre rollback multi-detalle, dos confirmaciones concurrentes con la misma `Idempotency-Key`, salidas competidas contra un saldo, transferencias cruzadas con orden determinista y creación concurrente del saldo destino. Usa PostgreSQL Testcontainers; los futures se esperan, propagan su resultado y los ejecutores se cierran en `finally`.
- La prueba RabbitMQ usa RabbitMQ Testcontainers y un exchange/cola de prueba: verifica `siga.events`, routing `inventory.movement.confirmed.v1`, entrega persistente, publisher confirm, estado `PUBLISHED` únicamente tras ACK, fallo por exchange ausente, retry y estabilidad de `eventId`.
- No se identificó defecto de producción: los fallos iniciales pertenecían al harness recién agregado (`List.of` con éxito `null` y mensajes PENDING de otras pruebas) y se corrigieron sin cambiar código funcional.
- `mvn -B -Dtest=D4ConfirmationIntegrationTest test`: **11/0/0/0**.
- `mvn -B test`: **68/0/0/0**. PostgreSQL y RabbitMQ fueron Testcontainers efímeros; no se inició Inventory ni se tocaron recursos `siga-local`.

## Canon

- Actualizadas exclusivamente `SEQ_04`, `SEQ_05`, `SEQ_08`, `SEQ_09`, `SEQ_11` y `SEQ_12`: idempotencia, locks/revalidación, costos/snapshots, outbox PENDING, commit y publicación posterior con confirm/retry.
- La matriz añade `TC-INV-D4-003` a `TC-INV-D4-006` para rollback, concurrencia y publisher.

## Estado

`D4_COMPLETE_FOR_FINAL_SOL_REVIEW`. D5 sigue fuera de alcance hasta la revisión final; no se modificaron migraciones ni controladores.

---

# D4 BLOCKER FIX PASS

## Resolución

- Los destinos creables se deduplican y ordenan con `BalanceKey.ORDER` antes de `INSERT ... ON CONFLICT DO NOTHING`; el lock posterior conserva el mismo orden. La prueba PostgreSQL concurrente cubre dos destinos nuevos en orden inverso.
- `MovementConfirmed v1` añade los campos aprobados para Reporting/Audit: centro de costo, snapshot de proveedor, referencia, motivo, sensibilidad, autorizador y `confirmedByUserId` requerido. La prueba valida contra schema el payload persistido y el mensaje Rabbit realmente recibido.
- `InventoryOutboxPublisher` expone `publishScheduled()` con `@Scheduled`; ambos delays son configurables. Los tests difieren el worker para no interferir y lo invocan explícitamente.
- En fallo Rabbit el publisher conserva PENDING/attempts/retry y registra un warning estructurado por `eventId`, sin payload ni secretos. El retry publica el mismo `eventId` tras restaurar el exchange.

## Evidencia

- Focal D4: **14/0/0/0** con PostgreSQL/RabbitMQ Testcontainers.
- Regresión: **71/0/0/0** mediante `mvn -B test`.
- Sin migraciones, runtime, controladores ni recursos compartidos. D5 permanece fuera de alcance.

## Estado

`D4_BLOCKERS_FIXED_PENDING_FINAL_SOL_REVIEW`.

---

# FINAL D4 CLOSEOUT

Fecha: 2026-10-06 (America/Lima).

- Regresión única del árbol final: `mvn -B test` — **71/0/0/0**, `BUILD SUCCESS`, con PostgreSQL/RabbitMQ Testcontainers y Flyway V1–V4.
- Destination ordering: PASS. Deduplicación y `BalanceKey.ORDER` preceden el insert; el lock usa el mismo comparator; la carrera inversa de dos claves termina sin deadlock y verifica ambos resultados.
- `MovementConfirmed v1`: PASS. Schema, payload persistido y mensaje Rabbit real cubren Reporting/Audit, `confirmedByUserId`, snapshots, referencia/motivo, sensibilidad/autorización y saldos afectados; no contienen secretos.
- Publisher worker: PASS. `@Scheduled` está cableado por `@EnableScheduling` y usa delays configurables.
- Failure semantics: PASS. El fallo es observable por `eventId`, permanece PENDING con attempts/next retry, no se marca PUBLISHED y recupera el mismo evento/ID.

Estado final: `D4=CLOSED`; `ACID=VERIFIED`; `CONCURRENCY=VERIFIED`; `PUBLISHER=VERIFIED`; `CANON=ALIGNED`.

No bloqueantes: rechazo tardío de una segunda confirmación con otra idempotency key; transacción DB abierta durante broker confirm; posible extracción futura de cost policy y del builder/serializer del evento.

# NEXT INVENTORY SLICE ANALYSIS

Recomendación: **Movement HTTP API**.

- El canon define `GET/POST /api/v1/movements`, `GET /movements/{id}` y POST de submit, authorize, reject, cancel y confirm. Actualmente solo se expone el GET por ID; los casos de uso de comandos ya existen.
- El slice debe incorporar listado paginado/filtrado para cerrar el gap Web y adaptar los comandos existentes, sin cambiar D4.
- Actor y step-up se derivan del JWT validado en el adaptador HTTP. Permisos: `INVENTORY_READ`, `MOVEMENT_CREATE`, `MOVEMENT_AUTHORIZE` e `INVENTORY_ADJUST` según operación/tipo; los ajustes requieren control condicional aunque compartan ruta.
- Debe incluir `Idempotency-Key` para confirm, respuestas/Problem Details canónicos y pruebas 400/401/403/404/409.

Fuentes: OpenAPI Inventory v1.2; Manual Técnico; CUS-07, CUS-08, CUS-09, CUS-10 y CUS-27. Implementación aún no autorizada.

---

# MOVEMENT HTTP API

Status: `IMPLEMENTED_BY_CODEX_TERRA` — `PENDING_FINAL_REVIEW`.

## Endpoints implementados

- `GET /api/v1/movements`: página `{content,page,size,totalElements}`, orden `created_at DESC, id DESC`; `page` default 0 y `size` default 20, con rango 1..100. No se añadieron filtros no especificados.
- `GET /api/v1/movements/{id}`: preservado.
- `POST /api/v1/movements`: crea DRAFT mediante `CreateMovementCommand`, responde 201 y deriva `registeredByUserId` exclusivamente del JWT `sub` UUID.
- `POST /api/v1/movements/{id}/submit`: reutiliza `SubmitMovementCommand`; la sensibilidad continúa calculándose en aplicación.
- `POST /api/v1/movements/{id}/authorize|reject`: usa el actor autenticado y `JwtStepUpValidator`; no recibe actor, OTP ni token step-up en el body.
- `POST /api/v1/movements/{id}/cancel`: reutiliza `CancelMovementCommand`.
- `POST /api/v1/movements/{id}/confirm`: exige `Idempotency-Key` UUID y delega íntegramente en D4; la respuesta vuelve a consultar el snapshot persistido.

## Seguridad y errores

- Consultas: `INVENTORY_READ`, conforme a IAM/seed y al baseline vigente.
- Flujo ordinario: `MOVEMENT_CREATE`; ajustes: `INVENTORY_ADJUST`, con autorización condicional por tipo en las rutas compartidas; authorize/reject: `MOVEMENT_AUTHORIZE` y step-up reciente.
- Campos desconocidos se rechazan, por lo que `registeredByUserId`, sensibilidad, estado, código o actor de autorización no pueden introducirse silenciosamente desde el body.
- Problem Details cubre 400/401/403/404/409 sin exponer mensajes internos.

## Query y pruebas

- La lista usa count + una consulta paginada de movimientos + una consulta batch de detalles para toda la página; no realiza N+1.
- Pruebas nuevas: 14 de controller HTTP y 2 de query/paginación PostgreSQL.
- Regresión final: `mvn -B test` — **87 pruebas, 0 fallos, 0 errores, 0 omitidas**, `BUILD SUCCESS`, con PostgreSQL/RabbitMQ Testcontainers aislados.

## Gaps canónicos registrados

1. `api/inventory-openapi.yaml` declara `GET /movements` pero no define parámetros de paginación/filtros ni schema de respuesta. La implementación reutiliza el patrón Inventory y la obligación de historial paginado de CUS-11/Manual Técnico; no añade filtros hasta que se enumeren.
2. CUS-11/Manual Técnico nombran `MOVEMENT_READ`, pero ese permiso no existe en IAM V3 ni en `seed_demo.sql`; el baseline aprobado y la seguridad existente usan `INVENTORY_READ`. Debe resolverse documentalmente antes de afirmar paridad completa de permisos.

La copia `docs/openapi.yaml` conserva paridad textual con el OpenAPI canónico salvo finales de línea; no se modificó el contrato para acomodar el código.

# CANONICAL LOCATIONS READ

Fecha: 2026-10-06 (America/Lima).

- Ownership: `inventory-service`; la tabla canónica existente es `inventory.location` y no requirió migración.
- Endpoint implementado: `GET /api/v1/locations`, protegido exclusivamente con `INVENTORY_READ`.
- Respuesta: colección plana con `id`, `code`, `name`, `locationType`, `parentId`, `active` y `version`; no expone timestamps ni construye árbol.
- Semántica operativa: solo ubicaciones activas, ordenadas por `code ASC, id ASC`; `parentId` preserva la jerarquía y puede ser `null`.
- Arquitectura: read model y tipos canónicos, puerto de consulta, query de aplicación, adaptador PostgreSQL de una sola sentencia y controller delgado. No hay N+1.
- Pruebas focalizadas: **7/0/0/0**. Cubren delegación, seed, activas, orden, padre nullable, once tipos canónicos, 200/401/403, schema HTTP y colección vacía.
- Regresión completa: `mvn -B test` — **94 pruebas, 0 fallos, 0 errores, 0 omitidas**, con PostgreSQL/RabbitMQ Testcontainers aislados y Flyway V1–V4.
- OpenAPI canónico y copia Inventory sincronizados con `LocationResponse` y `x-required-permission: INVENTORY_READ`.
- Este checkpoint histórico dejó pendientes las mutaciones de Location. El contrato posterior las cerró como POST create, PUT completo y POST deactivate/reactivate (sin PATCH/DELETE), con `LOCATION_MANAGE`; la implementación runtime sigue diferida a Slice B. Lots, Assets y Responsibles permanecen fuera de ese slice.
- No se modificaron D4, Web, Identity, Catalog, infraestructura, recursos compartidos ni runtime; no hubo commit ni push.

Estado: `CANONICAL_LOCATIONS_GET=IMPLEMENTED`; pendiente de revisión final antes de conectar Web.

# LOTS + ASSETS OPERATIONAL READ

Fecha: 2026-10-06 (America/Lima).

- Implementados `GET /api/v1/lots` y `GET /api/v1/assets` como selectores operacionales de solo lectura, ambos protegidos con `INVENTORY_READ`.
- Lots exige `productId`, admite `locationId`, `availableOnly` y paginación; devuelve solo `ACTIVE` no vencidos a fecha UTC, agrega `stock_balance` y usa orden estable `lotNumber/heatNumber/id` con nulos al final.
- Assets exige `productId` y `availability=UNASSIGNED|IN_STOCK`, admite `locationId`, `lotId` y paginación; devuelve solo `OPERATIVO`, deriva ubicación/lote de `stock_balance` y ordena por `assetCode/id`.
- Arquitectura: DTOs de lectura, puertos, queries/casos de uso, adaptadores JDBC y controllers delgados; no se modificó el dominio D4 ni se añadieron migraciones.
- Contrato canónico actualizado en OpenAPI, CUS-25, manuales pertinentes y matriz de testing. Se separa lectura operacional (`INVENTORY_READ`) de la administración futura (`TRACEABILITY_MANAGE`).
- Evidencia focalizada: **16 pruebas, 0 fallos, 0 errores, 0 omitidas**, con PostgreSQL Testcontainers dedicado. Regresión completa: **110 pruebas, 0 fallos, 0 errores, 0 omitidas**, con PostgreSQL/RabbitMQ aislados.
- Fuera de alcance: POST/PATCH de lotes/activos, administración, provisión de `TRACEABILITY_MANAGE`, Web y cambios de runtime.

Estado: `LOTS_ASSETS_OPERATIONAL_READ=IMPLEMENTED_REGRESSION_GREEN_PENDING_FINAL_REVIEW`.
