# ms-andesstay-catalog

Microservicio de **catálogo de unidades y disponibilidad** de AndesStay
(asignatura DSY1107 — Desarrollo Cloud Native I).

Acá vive el inventario de la red: habitaciones, cabañas y lodges de los 20 recintos,
con su tarifa por noche, capacidad, stock total y stock disponible. Desde la EP3 también
**descuenta la disponibilidad cuando una reserva se confirma y la libera cuando se cancela**,
escuchando los eventos de RabbitMQ: `ms-andesstay-reservations` no llama a este servicio
(ver [§5](#5-disponibilidad-y-mensajería-ep3)).

- **Puerto:** `8082`
- **Base del API:** `/api/catalog/units`
- **Cola que consume:** `q.evt.catalog` (`reservation.confirmed` y `reservation.cancelled`)
- **Java 21 + Spring Boot 3.5.16** (resource server OAuth2 contra Azure AD / Entra ID)
- **Base de datos:** H2 en memoria (perfil `dev`) u Oracle Autonomous Database (perfil `oracle`)

---

## 1. Endpoints y roles

La matriz de autorización es la del contrato y se aplica en `SecurityConfig`:

| Método | Ruta | Roles permitidos |
|---|---|---|
| GET | `/api/catalog/units` (query: `type`, `available`) | ADMIN, OPERADOR, CLIENTE |
| GET | `/api/catalog/units/{id}` | ADMIN, OPERADOR, CLIENTE |
| POST | `/api/catalog/units` | ADMIN |
| PUT | `/api/catalog/units/{id}` | ADMIN |
| DELETE | `/api/catalog/units/{id}` | ADMIN |

Públicos (sin token): `/actuator/health`, `/actuator/info`, `/v3/api-docs/**`, `/swagger-ui/**`.

Parámetros de consulta de la lista:
- `type` → `HABITACION`, `CABANA` o `LODGE`.
- `available=true` → solo unidades activas con `availableStock > 0`.

---

## 2. Cómo lo corres

Siempre exporta el JDK 21 antes de compilar (con el JDK 25 del sistema **no** funciona):

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
```

### Perfil `dev` (por defecto, H2 + datos de ejemplo)

```bash
./mvnw -B clean verify        # compila y corre los tests
./mvnw spring-boot:run        # levanta en http://localhost:8082
```

Parte recién clonado, sin wallet y sin tenant real: el perfil activo por defecto es `dev`,
la base es H2 en memoria y el `DevDataSeeder` carga 15 unidades reales del norte y del sur
de Chile (Pucón, Puerto Varas, Futaleufú, Cochamó, Chiloé, San Pedro de Atacama y el Valle
del Elqui).

- Consola H2: http://localhost:8082/h2-console (URL `jdbc:h2:mem:andesstay`, usuario `sa`, sin clave)
- Swagger UI: http://localhost:8082/swagger-ui.html
- Salud: http://localhost:8082/actuator/health

### Perfil `oracle` (Oracle ADB con wallet)

```bash
export TNS_ADMIN=/ruta/al/wallet
export ORACLE_SERVICE=andesstay_high
export ORACLE_USER=ANDESSTAY
export ORACLE_PASSWORD=****
SPRING_PROFILES_ACTIVE=oracle ./mvnw spring-boot:run
```

### Docker

```bash
docker build -t andesstay/ms-catalog:1.0.0 .
docker run --rm -p 8082:8082 \
  -e AZURE_TENANT_ID=6f522bef-27e2-4548-89e6-c1717b61ae20 \
  -e AZURE_API_CLIENT_ID=7d348e57-2f83-4648-baf0-588cbacccc3c \
  -e ANDESSTAY_RABBIT_ENABLED=false \
  andesstay/ms-catalog:1.0.0
```

Sin broker a la vista conviene partir con `ANDESSTAY_RABBIT_ENABLED=false`: el CRUD queda
igual y no se llena el log de reintentos de conexión. Con el stack de `infra/apps` esa variable
no va, porque ahí sí hay broker (`rabbit1`).

La imagen es multi-stage (build con `maven:3.9-eclipse-temurin-21`, runtime con
`eclipse-temurin:21-jre-alpine`), corre con el usuario no root `andesstay` y trae
`HEALTHCHECK` contra `/actuator/health/liveness`. Es el grupo que **no** incluye el indicador de
RabbitMQ: si el broker se cae, el catálogo sigue atendiendo su CRUD y Docker no tiene por qué
reiniciarlo (los eventos esperan en la cola).

---

## 3. Variables de entorno

| Variable | Default | Para qué sirve |
|---|---|---|
| `SERVER_PORT` | `8082` | Puerto HTTP |
| `SPRING_PROFILES_ACTIVE` | `dev` | Perfil activo (`dev`, `oracle`, `test`) |
| `AZURE_TENANT_ID` | `6f522bef-27e2-4548-89e6-c1717b61ae20` | Tenant de Entra ID; arma el issuer |
| `AZURE_API_CLIENT_ID` | `7d348e57-2f83-4648-baf0-588cbacccc3c` | App Registration `andesstay-api`; arma las audiencias |
| `ORACLE_SERVICE` | `andesstay_high` | Alias TNS del wallet |
| `TNS_ADMIN` | `./wallet` | Carpeta del wallet de Oracle ADB |
| `ORACLE_USER` / `ORACLE_PASSWORD` | `ANDESSTAY` / `changeme` | Credenciales de la BD |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` | `localhost` / `5672` | Broker AMQP del contrato de la EP3 |
| `RABBITMQ_USER` / `RABBITMQ_PASSWORD` | `andesstay` / `andesstay` | Credenciales del broker local |
| `RABBITMQ_VHOST` | `/` | vhost del broker |
| `RABBITMQ_MAX_ATTEMPTS` | `3` | Intentos antes de mandar un mensaje a la DLQ (header `x-attempt`) |
| `ANDESSTAY_RABBIT_ENABLED` | `true` | En `false` el `@RabbitListener` no arranca **y se apaga el indicador de salud de RabbitMQ**: el catálogo funciona sin broker y `/actuator/health` sigue respondiendo `200` (es lo que hace `run-local.sh` cuando no detecta broker) |

Dentro de Compose los nombres son `SPRING_RABBITMQ_HOST`, `_PORT`, `_USERNAME` y `_PASSWORD`,
que Spring Boot lee solo y **pisan** los de arriba.

Los valores de Azure quedan como **placeholders literales** a propósito: nadie tiene que
inventar GUIDs para que el proyecto compile y arranque.

---

## 4. Cómo se valida el JWT (el 40% de la nota)

Todo pasa por `cl.andesstay.catalog.security`:

1. **Firma** — `NimbusJwtDecoder.withIssuerLocation(issuerUri)` descarga el JWKS de Azure y
   verifica la firma RS256 del token.
2. **Issuer** — `JwtValidators.createDefaultWithIssuer(issuerUri)` exige que `iss` sea
   exactamente `https://login.microsoftonline.com/6f522bef-27e2-4548-89e6-c1717b61ae20/v2.0`.
3. **Vigencia** — el mismo validador por defecto incluye `JwtTimestampValidator`
   (`exp` / `nbf` con tolerancia de reloj de 60 s).
4. **Audiencia** — `AudienceValidator` acepta `api://7d348e57-2f83-4648-baf0-588cbacccc3c` **o** `7d348e57-2f83-4648-baf0-588cbacccc3c`,
   porque Azure emite el App ID URI en tokens v1 y el GUID pelado en v2.
5. **Scope** — `ScopeValidator` exige que el claim `scp` contenga `access_as_user`.
6. **Roles** — el claim `roles` se mapea a `ROLE_<valor>` y `scp` a `SCOPE_<valor>`;
   con eso `hasRole('ADMIN')` funciona tal como pide el contrato.

Los tres validadores se combinan en un `DelegatingOAuth2TokenValidator`.

### Por qué el decoder es perezoso

`JwtDecoders.fromIssuerLocation(...)` sale a Internet apenas se crea el bean. Si alguien
clona el repo con el placeholder `6f522bef-27e2-4548-89e6-c1717b61ae20` (o sin red), eso tumbaría el contexto de
Spring y el servicio ni siquiera arrancaría. Por eso hay un `LazyJwtDecoder`: envuelve un
`Supplier<JwtDecoder>` memoizado y sólo resuelve el issuer cuando llega el primer token.
Resultado: la app **siempre** levanta, y si el tenant está mal configurado el error se
traduce en un `401` limpio en vez de un fallo de arranque.

### Formato de error

Idéntico en todos los componentes de AndesStay:

```json
{
  "timestamp": "2026-09-08T12:00:00Z",
  "status": 401,
  "error": "Unauthorized",
  "message": "Token ausente o invalido",
  "path": "/api/catalog/units",
  "traceId": "3f1c...-..."
}
```

- `401` + `WWW-Authenticate: Bearer error="invalid_token"` → sin token, token expirado,
  firma / issuer / audiencia inválidos.
- `403` → token válido pero sin el rol requerido.
- `400` → payload inválido (`@Valid`), con el detalle campo por campo en `details`.
- `404` → la unidad no existe.
- `409` → código duplicado o choque de concurrencia al editar.
- `502` → no se pudo hablar con un servicio aguas abajo.

---

## 5. Disponibilidad y mensajería (EP3)

La disponibilidad de cada unidad (`availableStock`) se mueve de **dos** formas:

1. **A mano**, por `ADMIN`, desde el CRUD: `PUT /api/catalog/units/{id}`.
2. **Por eventos**, al confirmarse o cancelarse una reserva. Esto es nuevo de la EP3 y es la
   regla del caso que antes no estaba.

### La cola que consume

| Dato | Valor |
|---|---|
| Cola | `q.evt.catalog` (durable) |
| Exchange | `events.topic` (topic, durable) |
| Bindings | `reservation.confirmed` y `reservation.cancelled` (claves **exactas**, no `reservation.#`) |
| Dead-letter | `events.dead.dlx` con routing key `catalog.dead` → `q.evt.catalog.dlq` |
| ACK | **MANUAL** (`spring.rabbitmq.listener.simple.acknowledge-mode: manual`) |
| Clase | `cl.andesstay.catalog.messaging.AvailabilityEventListener` |

`ms-andesstay-reservations` **no llama a este servicio**: publica el hecho en `events.topic` y se
desentiende. El mismo `reservation.confirmed` le llega a `q.evt.report`, a `q.evt.audit` y a
`q.evt.catalog`, cada uno con su propia cola (pub/sub de verdad). Si el catálogo está caído
cuando se confirma una reserva, el evento espera en la cola en vez de hacer fallar la
confirmación; y si el broker está caído, el CRUD del catálogo sigue funcionando igual
(`andesstay.rabbit.enabled=false` deja el consumidor sin arrancar).

Los bindings son las dos claves exactas y no el comodín `reservation.#` porque al catálogo solo
le incumben los eventos que mueven stock: con el patrón recibiría también `created`, `checkin` y
`checkout`, que no cambian la disponibilidad.

### Qué hace con cada evento

| Evento | Efecto en la unidad del evento | Si no se puede |
|---|---|---|
| `reservation.confirmed` | `availableStock - 1` | la unidad ya está en 0: log **ERROR** con el código de la reserva y el mensaje se va a `q.evt.catalog.dlq`. **El stock nunca queda negativo** |
| `reservation.cancelled` | `availableStock + 1`, topado en `totalStock` | ya tenía todos sus cupos libres (reserva cancelada que nunca se confirmó): `ACK` con log **WARN**, no es un error |
| cualquier otro `type` | ninguno | a la DLQ: esa cola no está atada a otros eventos, así que alguien se salió del contrato |

### Política de ACK y errores

Es el indicador más pesado de la pauta (16%) y está tal cual el contrato
([`docs/14-contrato-mensajeria.md`](../docs/14-contrato-mensajeria.md)):

| Situación | Qué hace |
|---|---|
| Éxito | `basicAck(deliveryTag, false)` + log INFO con el stock antes y después |
| `eventId` repetido | `basicAck` **sin repetir el efecto** + log INFO |
| Error NO recuperable (JSON inválido, envelope incompleto, unidad inexistente, sin cupos) | `basicNack(deliveryTag, false, false)` → DLQ, log ERROR con `eventId`, `traceId` y motivo |
| Error RECUPERABLE (base caída, timeout) | republica el mensaje con `x-attempt + 1` hasta `andesstay.rabbit.max-attempts` (3) y, agotados, `basicNack` → DLQ |
| No se puede republicar el reintento (broker en flow control, conexión de publicación caída) | `basicNack(deliveryTag, false, false)` → DLQ, log ERROR: el evento queda guardado y se reinyecta a mano |

**Nunca**, en ningún camino, se usa `requeue=true`: devolver el mensaje a la cola no aumenta el
contador, así que volvería a la cabeza con el mismo `x-attempt` y el consumidor giraría sobre él
sin límite. El contador viaja en el header `x-attempt` del mensaje republicado, así sobrevive a un
reinicio del consumidor y se puede ver en la Management UI.

### Idempotencia

RabbitMQ entrega *at least once*, y acá el efecto no es idempotente por naturaleza: aplicar dos
veces un `reservation.confirmed` descontaría dos cupos por una sola reserva. Por eso el `eventId`
del envelope se guarda en la tabla **`CATALOG_PROCESSED_EVENT`** (es su clave primaria) **en la
misma transacción** que el cambio de stock: si el evento vuelve, el consumidor hace `ACK` y no
repite nada. El DDL para Oracle está en
[`infra/oracle/16-catalog-processed-event.sql`](../infra/oracle/16-catalog-processed-event.sql).

### Concurrencia

El descuento **no** es "leer la unidad, restar uno y guardar". Se hace con un `UPDATE` condicional
(`UnitRepository.reserveOneIfAvailable` / `releaseOneIfNotFull`): la base evalúa la condición
(`AVAILABLE_STOCK > 0` o `AVAILABLE_STOCK < TOTAL_STOCK`) y escribe en la **misma sentencia
atómica**, así que el stock no puede quedar negativo ni pasarse del total por mucho que dos
eventos de la misma unidad se procesen a la vez. La sentencia sube igual la columna `VERSION`, o
sea que el `@Version` de la entidad sigue siendo la defensa del CRUD: si un `ADMIN` estaba
editando esa unidad con un `PUT`, su commit choca con el bloqueo optimista y recibe el `409` de
siempre en vez de pisar el movimiento de stock.

### Configuración centralizada

Ningún nombre de cola, exchange o routing key está escrito en el código: todos viven en
`application.yml` bajo `andesstay.rabbit.*`, los lee `AndesStayRabbitProperties`
(`@ConfigurationProperties`) y con ellos se arman los beans de `RabbitTopologyConfig`, la cola del
`@RabbitListener` y hasta la decisión de "este evento descuenta o libera". Este servicio declara
**solo lo suyo**: `events.topic`, `q.evt.catalog`, `events.dead.dlx` y `q.evt.catalog.dlq`.
`q.evt.report`, `q.evt.audit` y las colas `q.cmd.*` son de sus propios dueños.

---

## 6. Ejemplos con curl

Sin token → `401`:

```bash
curl -i http://localhost:8082/api/catalog/units
# HTTP/1.1 401
# WWW-Authenticate: Bearer error="invalid_token"
```

Con token (el que te entrega el SPA de Angular tras el login con MSAL):

```bash
TOKEN="eyJ0eXAiOiJKV1Qi..."

# Listar todo el catálogo (ADMIN, OPERADOR o CLIENTE)
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:8082/api/catalog/units | jq

# Sólo cabañas con cupos disponibles
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8082/api/catalog/units?type=CABANA&available=true" | jq

# Crear una unidad (sólo ADMIN; con rol CLIENTE devuelve 403)
curl -i -X POST http://localhost:8082/api/catalog/units \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{
        "code": "UN-PUC-004",
        "name": "Cabana Caburgua",
        "type": "CABANA",
        "propertyName": "Cabanas Trawun",
        "location": "Pucon, La Araucania",
        "capacity": 4,
        "nightlyRate": 128000,
        "totalStock": 3
      }'
```

Normalmente no le pegas directo: el SPA habla con el **BFF** (`http://localhost:8080`),
que hace token relay hacia este servicio.

---

## 7. Modelo de datos

Tabla `CATALOG_UNIT`:

| Campo | Tipo | Notas |
|---|---|---|
| `id` | Long | identity |
| `code` | String(20) | único, ej. `UN-PUC-001` |
| `name` | String(120) | |
| `type` | enum | `HABITACION`, `CABANA`, `LODGE` |
| `propertyName` | String(120) | recinto al que pertenece |
| `location` | String(120) | comuna / región |
| `capacity` | Integer | personas |
| `nightlyRate` | BigDecimal(12,2) | CLP |
| `totalStock` | Integer | cupos totales |
| `availableStock` | Integer | cupos libres |
| `active` | Boolean | |
| `createdAt` / `updatedAt` | Instant | |
| `version` | Long | bloqueo optimista |

Tabla `CATALOG_PROCESSED_EVENT` (EP3, bitácora de idempotencia del consumidor):

| Campo | Tipo | Notas |
|---|---|---|
| `eventId` | String(40) | **PK**: el `eventId` del envelope (= `messageId` de AMQP) |
| `eventType` | String(60) | `reservation.confirmed` o `reservation.cancelled` |
| `reservationCode` | String(20) | reserva que movió el stock |
| `unitId` | Long | unidad afectada |
| `outcome` | String(20) | `RESERVED`, `RELEASED` o `RELEASE_SKIPPED` |
| `processedAt` | Instant | cuándo lo procesó este servicio |

Sin clave foránea hacia `CATALOG_UNIT` a propósito: la bitácora tiene que sobrevivir al borrado
de una unidad, porque es la única explicación de por qué su stock se movió.

---

## 8. Estructura y pruebas

```
src/main/java/cl/andesstay/catalog/
├── config/      OpenApiConfig, DevDataSeeder,
│                AndesStayRabbitProperties (nombres del contrato de mensajería),
│                RabbitTopologyConfig (beans Queue/Exchange/Binding)
├── domain/      Unit, UnitType, ProcessedEvent
├── dto/         UnitRequest, UnitResponse, ApiError
├── exception/   NotFoundException, ConflictException
├── messaging/   AvailabilityEventListener (ACK manual), ReservationEventParser,
│                ReservationEventEnvelope, ReservationEventPayload,
│                NonRecoverableEventException, InvalidEventException
├── repository/  UnitRepository, ProcessedEventRepository
├── security/    SecurityConfig, LazyJwtDecoder, AudienceValidator, ScopeValidator,
│                AuthenticatedUser, handlers de 401 y 403, AzureAdProperties
├── service/     UnitService, UnitAvailabilityService, AvailabilityOutcome
└── web/         UnitController, GlobalExceptionHandler, ApiErrorSupport
```

La configuración de mensajería va en clases **aparte** de los servicios de dominio: el
`@Configuration` declara la topología, el `@ConfigurationProperties` tiene los nombres y el
`@RabbitListener` solo coordina el ACK. Ningún `@Service` conoce un nombre de cola.

Los tests **no** requieren red ni un tenant real: el `JwtDecoder` se reemplaza por un mock
(`@MockitoBean`) y los tokens se simulan con `spring-security-test`.

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
./mvnw -B clean verify
```

Son **33 pruebas** y ninguna necesita red ni broker:

| Clase | Qué cubre |
|---|---|
| `UnitControllerSecurityTest` (9) | 401 sin token, 403 con `ROLE_CLIENTE` en `POST`, 201 con `ROLE_ADMIN`, 200 con `ROLE_CLIENTE` en `GET`, 400 con payload inválido, 404 y `/actuator/health` público |
| `UnitServiceTest` (3) | reglas del CRUD contra H2: `availableStock` por defecto, código duplicado y filtros |
| `CatalogApplicationTests` (1) | el contexto arranca sin salir a Internet |
| `RabbitTopologyConfigTest` (5) | la topología declarada es la del contrato, los nombres salen del `application.yml`, los bindings son las dos claves exactas, el ACK es MANUAL y **no** se declaran colas de otros servicios |
| `UnitAvailabilityServiceTest` (7) | confirmar baja el stock, cancelar lo libera, el mismo `eventId` dos veces lo deja igual, sin cupos no queda negativo, cancelar una unidad full no se pasa de `totalStock`, unidad inexistente y `type` que no mueve stock |
| `AvailabilityEventListenerTest` (8) | la política de ACK con el `Channel` de AMQP mockeado: `ack`, `nack` a la DLQ, reintento con `x-attempt + 1`, intentos agotados, republicación imposible (a la DLQ, nunca `requeue=true`) y evento repetido |

---

## 9. Fuera de alcance

Kafka y Zookeeper: la reportería releíble es materia de la evaluación siguiente
(ver `infra/kafka/README.md`). Este servicio **no publica** mensajes y no habla con ningún otro
microservicio por REST: solo consume `q.evt.catalog`.
