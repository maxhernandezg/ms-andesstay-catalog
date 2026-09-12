# ms-andesstay-catalog

Microservicio de **catálogo de unidades y disponibilidad** de AndesStay
(asignatura DSY1107 — Desarrollo Cloud Native I, Evaluación Parcial N°1).

Acá vive el inventario de la red: habitaciones, cabañas y lodges de los 20 recintos,
con su tarifa por noche, capacidad, stock total y stock disponible. También expone las
operaciones de `reserve` / `release` que usa `ms-andesstay-reservations` cuando una
reserva se confirma o se cancela.

- **Puerto:** `8082`
- **Base del API:** `/api/catalog/units`
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
| POST | `/api/catalog/units/{id}/reserve` body `{"quantity":1}` | ADMIN, OPERADOR |
| POST | `/api/catalog/units/{id}/release` body `{"quantity":1}` | ADMIN, OPERADOR |

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
  andesstay/ms-catalog:1.0.0
```

La imagen es multi-stage (build con `maven:3.9-eclipse-temurin-21`, runtime con
`eclipse-temurin:21-jre-alpine`), corre con el usuario no root `andesstay` y trae
`HEALTHCHECK` contra `/actuator/health`.

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
- `409` → sin disponibilidad, código duplicado o choque de concurrencia.
- `502` → no se pudo hablar con un servicio aguas abajo.

---

## 5. Disponibilidad: cómo se evita el sobrecupo

`reserve` y `release` **no** hacen leer-modificar-guardar. Usan una sentencia `UPDATE`
condicional (ver `UnitRepository.decrementStock` / `incrementStock`):

```sql
update CATALOG_UNIT set AVAILABLE_STOCK = AVAILABLE_STOCK - :quantity
 where ID = :id and AVAILABLE_STOCK >= :quantity
```

La condición de stock viaja dentro del `WHERE`, así que es la propia base de datos la que
serializa dos reservas simultáneas sobre la misma fila: una gana, la otra afecta 0 filas y
recibe un `409`. Es más simple y más barato que reintentar por bloqueo optimista, y se
comporta igual en H2 y en Oracle. La entidad `Unit` igual lleva `@Version`, que protege las
actualizaciones del CRUD (`PUT /api/catalog/units/{id}`) frente a ediciones concurrentes.

`release` usa la condición espejo (`AVAILABLE_STOCK + :quantity <= TOTAL_STOCK`), de modo
que nunca se devuelven más cupos de los que la unidad tiene.

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

# Descontar un cupo (ADMIN u OPERADOR)
curl -s -X POST http://localhost:8082/api/catalog/units/1/reserve \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"quantity":1}' | jq

# Devolver el cupo
curl -s -X POST http://localhost:8082/api/catalog/units/1/release \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"quantity":1}' | jq
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

---

## 8. Estructura y pruebas

```
src/main/java/cl/andesstay/catalog/
├── config/      OpenApiConfig, DevDataSeeder
├── domain/      Unit, UnitType
├── dto/         UnitRequest, UnitResponse, QuantityRequest, ApiError
├── exception/   NotFoundException, ConflictException
├── repository/  UnitRepository
├── security/    SecurityConfig, LazyJwtDecoder, AudienceValidator, ScopeValidator,
│                AuthenticatedUser, handlers de 401 y 403, AzureAdProperties
├── service/     UnitService
└── web/         UnitController, GlobalExceptionHandler, ApiErrorSupport
```

Los tests **no** requieren red ni un tenant real: el `JwtDecoder` se reemplaza por un mock
(`@MockitoBean`) y los tokens se simulan con `spring-security-test`.

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
./mvnw -B clean verify
```

Cubren: 401 sin token, 403 con `ROLE_CLIENTE` en `POST`, 201 con `ROLE_ADMIN`, 200 con
`ROLE_CLIENTE` en `GET`, 400 con payload inválido, 404, 409 sin disponibilidad, y las reglas
de `reserve` / `release` contra H2.

---

## 9. Fuera de alcance (EP1)

Sin RabbitMQ, Kafka ni Zookeeper. La mensajería asíncrona es materia de EP2/EP3; por ahora
la comunicación entre servicios es REST y quien publica eventos es
`ms-andesstay-reservations`.
