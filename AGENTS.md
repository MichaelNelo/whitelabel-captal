# Contexto del Proyecto - Portal Captivo Whitelabel

## TL;DR para Agentes

- **Stack**: Scala 3 / Mill / ZIO / Tapir / Quill / rqlite + Scala.js (Laminar / Waypoint) en el frontend.
- **Deploy**: AWS ECS Fargate + ALB + CloudFront en la cuenta `460486036288` (region `us-east-1`).
- **Dominio**: `production.captal.centauroads.com` (CloudFront) y `staging.captal.centauroads.com` (ALB legacy).

**Primer paso para cualquier tarea — leer la skill relevante**:
- Skills **operacionales** (deploy, troubleshoot, agregar location/video/survey): `cli/resources/templates/skills/<name>/SKILL.md` (también se distribuyen al operador vía `captal init` + `captal skills update`).
- Skills **de desarrollo** (agregar endpoint / phase / migración, release del API o CLI, correr local): `.agents/skills/<name>/SKILL.md` (solo viven en este repo).

**Comandos de descubrimiento del estado real** (más rápido que confiar en este doc):

```bash
# Última versión de la CLI publicada
curl -s https://captal-cli-releases-dev.s3.us-east-1.amazonaws.com/latest/version.txt

# Última imagen API/Provision en ECR
aws ecr describe-images --repository-name captal-api-dev --region us-east-1 \
  --query 'sort_by(imageDetails[?imageTags!=null],&imagePushedAt)[-1].imageTags[0]'

# Locations activas (TGs en el ALB)
aws elbv2 describe-target-groups --region us-east-1 \
  --query 'TargetGroups[?starts_with(TargetGroupName,`captal-`)].TargetGroupName'
```

Para cualquier task concreta — leé la skill primero, después AGENTS.md, después el código.

---

## Estructura de archivos

Layout actual del repo (paths relativos a la raíz):

```
.
├── core/                                # Dominio puro (cross-compilado JVM+JS)
│   └── src/whitelabel/captal/core/
│       ├── application/                 # Phases, commands, events, flow types
│       │   ├── commands/                # AnswerEmail, ProvideNextVideo, etc.
│       │   ├── queries/
│       │   └── phase.scala, event.scala, flow.scala
│       ├── i18n/I18n.scala              # I18n schema (source of truth para claves)
│       ├── infrastructure/              # SessionData, repositories trait
│       ├── survey/                      # Survey + question + AnswerValue
│       │   └── question/                # Question types, rules, options, validation
│       ├── user/                        # User + State + Id + Email
│       └── video/                       # Video.Id
│
├── endpoints/                           # Tapir endpoint definitions (cross-compilado)
│   └── src/whitelabel/captal/endpoints/
│       ├── SurveyEndpoints.scala
│       ├── LocaleEndpoints.scala
│       ├── VideoEndpoints.scala
│       ├── AdvertiserSurveyEndpoints.scala
│       ├── FinishEndpoints.scala        # POST /api/finish → StatusResponse
│       ├── StatusResponse.scala         # phase + locale + accessExpiresAt
│       ├── ApiError.scala
│       └── *Request.scala / *Response.scala
│
├── provision/                           # YAML models — minimal, sin Quill/DB deps
│   └── src/whitelabel/captal/infra/provision/
│       └── models.scala                 # LocationYaml, SurveyYaml, VideoYaml, ...
│
├── infra/                               # DB + Event handlers + Services (JVM only)
│   ├── resources/
│   │   ├── application.conf
│   │   └── db/migration/V*.sql          # Flyway migrations (V1 … V17 hoy: V16=unifi fields, V17=access_expires_at)
│   └── src/whitelabel/captal/infra/
│       ├── Migrate.scala                # Flyway runner
│       ├── RqliteDataSource.scala       # JDBC wrapper sobre rqlite HTTP
│       ├── rows.scala                   # *Row case classes (Quill)
│       ├── UnifiAccess.scala            # Per-location UniFi config (host, token, port, site, …)
│       ├── schema/                      # SchemaMeta + decoders + QuillSqlite type
│       ├── eventhandlers/               # DbEventHandler chain + post-commit handlers
│       │   ├── EventLogHandler, AnswerPersistenceHandler, UserPersistenceHandler
│       │   ├── SessionPhaseHandler, SessionSurveyHandler, SessionVideoHandler
│       │   ├── SurveyProgressHandler, TransactionalEventHandler
│       │   ├── UnifiAuthorizationHandler    # post-commit: calls UniFi authorize-guest
│       ├── repositories/                # SurveyRepositoryQuill, UserRepositoryQuill, VideoRepositoryQuill
│       ├── services/                    # LocationService, LocaleService
│       ├── session/                     # SessionService, SessionContext, CaptivePortalParams
│       └── provision/                   # ProvisionService, ProvisionPlan, EntityWriter, IdGenerator
│
├── api/                                 # HTTP server (JVM only)
│   ├── resources/application.conf
│   ├── src/whitelabel/captal/api/
│   │   ├── Main.scala                   # ZIOAppDefault + ZLayer composition
│   │   ├── SessionEndpoint.scala        # class — secured() factory
│   │   ├── SessionCookieConfig.scala    # name + path slug-aware
│   │   ├── UserCookieConfig.scala       # cross-location user cookie
│   │   ├── UserLookup.scala             # id-based user existence check
│   │   ├── CurrentLocation.scala        # boot snapshot del location row
│   │   ├── SurveyRoutes.scala / LocaleRoutes / VideoRoutes / AdvertiserSurveyRoutes
│   │   ├── HealthRoutes.scala
│   │   └── ErrorView.scala (no, ese vive en client/)
│   └── test/                            # ZIO Test suites + TestFixtures + TapirStubInterpreter
│       └── src/whitelabel/captal/api/suites/
│
├── client/                              # Scala.js SPA (Laminar + Waypoint)
│   ├── assets/
│   │   ├── styles.css                   # ~1500 lines, ~50 CSS vars en :root
│   │   └── brand-icon.svg
│   ├── index.html.template              # Renderizado por Mill con <base href> injection inline
│   └── src/whitelabel/captal/client/
│       ├── Main.scala                   # App entry point + syncPhaseOnLoad
│       ├── Router.scala                 # enum Page + Waypoint Router + SplitRender
│       ├── AppState.scala               # Vars (locale, phase, currentSurvey, error, ...)
│       ├── ApiClient.scala              # dom.fetch wrapper
│       ├── Runtime.scala                # Future error escalation
│       ├── ErrorHandler.scala           # Centralized escalate(err) → /error
│       ├── i18n/I18nClient.scala
│       └── views/
│           ├── Layout.scala             # Wrapper común (brand icon, loading state)
│           ├── WelcomeView, IdentificationQuestionView
│           ├── AdvertiserVideoView, AdvertiserVideoSurveyView
│           ├── ReadyView, ErrorView
│
├── cli/                                 # captal CLI (zio-cli + AWS SDK)
│   ├── resources/templates/
│   │   ├── shared/                      # YAML templates para `captal init`
│   │   ├── location/                    # YAML templates para `captal locations add`
│   │   ├── video/                       # YAML templates para `captal video add`
│   │   ├── dockerfiles/                 # Dockerfile.shared, Dockerfile.locations (derived)
│   │   └── skills/                      # OPERATOR-facing skills (distribuidas a usuarios)
│   └── src/whitelabel/captal/cli/
│       ├── Main.scala                   # CaptalCommand enum + cliApp
│       ├── CaptalConfig.scala           # shared/captal.yaml loader
│       ├── CliError.scala, Output.scala
│       ├── AwsLayers.scala              # ZLayer factories for S3/ECS/ELBv2/ECR/...
│       ├── docker/DockerImageBuilder.scala
│       ├── templates/                   # Catalog, TemplateWriter, Template
│       └── commands/
│           ├── InitCommand, SharedPushCommand
│           ├── LocationsAddCommand, PushCommand, PushAllCommand
│           ├── VideoCommand
│           ├── SkillsUpdateCommand, UpdateCommand
│
├── .agents/skills/                      # DEV-facing skills (solo en este repo)
│   ├── add-migration, add-api-endpoint, add-event-handler, add-phase
│   ├── bump-api-version, bump-cli-version, run-locally
│
├── Dockerfile.api                       # Producción API image (FROM eclipse-temurin:21)
├── Dockerfile.provision                 # Ephemeral provision image
├── build.mill                           # Mill build (mill 1.x)
└── docs/                                # Diagrams + extended notes
```

> Convención: módulos **JVM-only** = `infra/`, `api/`, `cli/`. **Cross-compile** (JVM+JS) = `core/`, `endpoints/`. `provision/` es JVM-only pero minimal (solo case classes + circe decoders, sin Quill/DB) para que pueda ser dep del CLI sin arrastrar 60+ MB.

---

## Architecture invariants

Cosas que NUNCA deben violarse — usadas como checklist mental al editar:

- **Una `Phase` siempre tiene** una entrada en `Router.phaseToPage`, un caso en `SessionPhaseHandler` (si tiene transition), y validación vía `sessionEndpoint.secured(allowedPhases = ...)` en los endpoints que la requieran.
- **Todo YAML schema vive en `provision/src/.../models.scala`**, no en `infra/` ni `cli/`. Mantiene `provision/` reusable cross-CLI/API.
- **`users` es global** — no tiene `location_id`. El resto de entidades provisionables (`surveys`, `advertiser_videos`, `localized_texts`) sí están scopeadas por location.
- **IDs son uuid5 determinístico** de un seed string en `IdGenerator`. Reproducible: el mismo slug + content siempre da el mismo id → upserts idempotentes.
- **Cookie de sesión**: name = `captal_session_<slug>`, `Path=/<slug>`. **NUNCA** usar `session_id` global (sesiones se mezclarían entre locations).
- **Cookie user cross-location**: name = `captal_user`, `Path=/`. Identifica al usuario entre locations para skip de email.
- **Imágenes derivadas son inmutables** — `captal-shared-dev:<tag>` y `captal-locations-dev:<tag>` se construyen con timestamp; re-push con mismo tag puede romper deploys en curso. Cada `captal locations push` genera tag nuevo.
- **Migraciones Flyway son inmutables** post-merge. Bug en `V<n>__*.sql` ya aplicada → nuevo `V<n+1>__fix_*.sql`, nunca editar la vieja.
- **`captal_session_<slug>`/`captal_user`/`X-Click-Id`** — `X-Click-Id` requerido en CREATE de sesión nueva (sin él → `SessionMissing`). En requests subsecuentes con cookie ya seteada, se ignora.
- **`localized_texts` se borra en hard-delete** (no tiene `is_active`). El resto de entidades soft-delete via `is_active=0`.

---

## Client (Scala.js SPA) — convenciones

> Detalle exhaustivo en skill `client-patterns`. Aquí solo los principios para que un agente entrante no tropiece.

- **HTTP**: `ApiClient` usa `dom.fetch` directo, **NO** Tapir client interpreter. Los `cookie[Option[String]](...)` inputs/outputs de los endpoints son ignorados por el cliente — el browser auto-maneja todas las cookies (`captal_session_<slug>`, `captal_user`).
- **State**: `AppState` es el único `Var` compartido entre vistas (`locale`, `phase`, `currentSurvey`, `error`, `isNavigating`). Estado local a una vista (`isSubmitting`, `validationError`, `isFullscreen`) vive como `private val foo: Var[T]` en esa view.
- **Reactivity**: signals son la API primaria (`val foo: Signal[Bar]`); `Var` solo internamente. Subscribe vía `child.text <-- signal`, nunca `signal.foreach(println)`.
- **Routing**: `Router.Page` es un Scala 3 `enum`. Cada `Phase` mapea a una `Page` via `phaseToPage`. `Page.Error` vive FUERA del phase machine — se navega vía `ErrorHandler.escalate(err)`.
- **Error handling**: API call `Left(ApiError)` → `ErrorHandler.escalate(err)` (centralizado, navega a `/error`). Future failure → `Runtime.run` captura y escala. Validation errors (input del usuario malformado) → `validationError: Var[Option[String]]` inline + `.validation-error`, NO escalate.
- **i18n**: `I18nClient.i18n: Signal[I18n]` siempre disponible. Vistas leen `I18nClient.i18n.map(_.welcome.title)`. Para cambiar locale: `I18nClient.setLocale(loc)`. Claves vienen de `LocaleService` que arma desde `localized_texts` filtrado por `location_id` actual.
- **Paths siempre relativos**: el inline-script en `index.html.template` injecta `<base href="/<slug>/">`. Paths absolutos (`/brand-icon.svg`) ignoran el base y rompen en producción. Usar `src := "brand-icon.svg"`.
- **BuildInfo**: `ENVIRONMENT=dev` materializa `BuildInfo.isDevMode = true` en compile-time. Toggles dev features (e.g. reset button en ReadyView). **Caveat**: Mill cachea `client.generatedSources`; `./mill clean client.generatedSources` antes de toggleear `ENVIRONMENT`.
- **UniFi params**: `parseCaptivePortalHeaders` en `Main.scala` lee `?id=...&click_id=...&ap=...&ssid=...&url=...` y los manda como `X-*` headers a la API. `id` y `click_id` son requeridos en CREATE de sesión (sin ellos → `SessionMissing`).
- **CSS first**: antes de agregar un selector nuevo, ver skill `style-reference` para reusar (`.welcome-button` para CTAs primarios, `.question-submit-button` para form submits, etc.). Theming via CSS variables en `:root`.

---

## UniFi integration — captive portal interaction

> **Estado**: integración implementada end-to-end. El handler post-commit `UnifiAuthorizationHandler` (`infra/eventhandlers/`) llama al UCG cuando se emite `UserEvent.UserFinishedProcess`, y on success setea `session.phase = Authorized` + `accessExpiresAt`. Si UniFi falla, el session queda en `Phase.Ready` y la próxima llamada a `/api/finish` reintenta seamless.

### Modelo del flujo completo

```
[Cliente conecta a WiFi]
    ↓
[AP/UniFi Controller bloquea tráfico hacia internet]
    ↓
[UniFi redirige al cliente a: https://<our-portal>/?id=<mac>&ap=<mac>&ssid=<...>&url=<orig>&click_id=<token>]
    ↓
[SPA captal carga, usuario completa flow (email → profiling → video → encuesta)]
    ↓
[Última encuesta respondida → server retorna phase=Ready → cliente navega a /ready]
    ↓
[ReadyView.onMount → POST /api/finish → emite UserFinishedProcess]
    ↓
[Post-commit: UnifiAuthorizationHandler llama UniFi authorize-guest vía HTTPS]
    ↓
   ┌── UniFi 2xx ──────────────────────────────────────────────────┐
   ↓                                                                ↓
[setAuthorized(sessionId, expiresAt)]                  [UniFi 5xx / timeout / no config]
[Response: phase=Authorized + accessExpiresAt]         [Response: phase=Ready + None]
   ↓                                                                ↓
[Cliente Router.syncWithPhase(Authorized)]             [Queda en /ready; reload re-monta → retry]
[WelcomeView en modo Authorized con countdown]
   ↓
[accessExpiresAt vence → cliente sigue connected hasta UniFi expire de su lado]
[En reload posterior: /api/status detecta accessExpiresAt past → resetForExpiration → phase=Welcome]
```

### Modelo Phase / State

Dos pares con semánticas distintas pero acopladas:

- **Session `Phase` (server state)**:
  - `Ready` = "user terminó el survey flow, esperando UniFi". Pocos ms o más si UniFi falla.
  - `Authorized` = "UniFi confirmó el voucher; `accessExpiresAt` poblado". Transición la hace **sólo** `UnifiAuthorizationHandler` post-commit. Si UniFi no está configurado o falla, la sesión nunca llega a `Authorized` — diseño intencional para soportar retry.

- **User aggregate `State` (in-memory type-state)**:
  - `State.Ready(redirectUrl, watchedVideoId, answeredQuestionIds)` — proyectado desde DB cuando `session.phase == Ready`. Único estado que `UserRepository.findReadyUser` retorna.
  - `State.Authorized(...)` — ephemeral: sólo existe en memoria como el retorno tipado del `finish` op (`User[State.Ready] → User[State.Authorized] + emite UserFinishedProcess`). No hay `findAuthorizedUser` en el repo, porque para cuando session.phase=Authorized el flow ya está cerrado.

**Patrón retry-by-design**: como session.phase queda en `Ready` hasta que UniFi succeed, `findReadyUser` sigue retornando el user, y un nuevo POST `/api/finish` re-ejecuta `finish` → re-emite el evento → handler reintenta. El `ReadyView` del cliente lo aprovecha: re-mount = retry automático (idempotente porque UniFi `authorize-guest` es idempotente por MAC).

### Configuración

**Per-location** (en `location.yaml` → columns en `locations`):
```yaml
unifi:
  host: "192.168.1.1"           # IP/hostname del Controller
  apiToken: "YOUR_API_KEY"      # Settings → Integrations
  port: 8443                    # Opcional (default 8443)
  site: "default"               # Opcional
  unifiOs: true                 # Opcional (default true para UDM/Dream Machine)
  defaultDurationMinutes: 1440  # Opcional (default 24h)
```

**Infra-shared** (en `shared/captal.yaml` → env-var `UNIFI_PROXY_URL` en la task ECS):
```yaml
unifi:
  proxyUrl: "http://tinyproxy.captal-dev.local:8888"
  # Vacío → conexión directa (sólo local dev o API en misma LAN del UCG).
```

El proxy es necesario en deploy porque ECS Fargate no tiene visibilidad directa a la LAN del cliente. La cadena en producción es **tinyproxy (ECS daemon) → Tailscale subnet router (VM) → LAN → UCG**. `trustAllClientLayer` en `UnifiAuthorizationHandler` configura zio-http con `ClientSSLConfig.Default` (trust-all para self-signed) + `Proxy(url)` cuando hay `proxyUrl`.

### UniFi Controller API — endpoints relevantes

UniFi expone una **REST API** vía HTTPS al puerto 8443 (Controller) o 443 (Network Application en UniFi OS). Auth: login session-based (cookie `unifises` + `csrf_token`).

#### Autenticación

```http
POST https://<controller>:8443/api/login
Content-Type: application/json

{ "username": "captal-portal", "password": "<secret>", "remember": true }
```

Response: cookie `unifises=...` + header `x-csrf-token: ...`. Reusar para todas las requests subsecuentes hasta expirar.

UniFi OS variant (newer):
```http
POST https://<controller>/api/auth/login
```
Returns JWT-like token in `X-CSRF-Token` header + session cookies.

#### Authorize a guest (otorgar acceso)

Endpoint clave para captive-portal flow:

```http
POST https://<controller>:8443/api/s/<site>/cmd/stamgr
Cookie: unifises=...
X-CSRF-Token: ...
Content-Type: application/json

{
  "cmd": "authorize-guest",
  "mac": "aa:bb:cc:dd:ee:ff",
  "minutes": 1440,
  "up": 5000,        // optional: upload limit kbps
  "down": 20000,     // optional: download limit kbps
  "bytes": 5242880,  // optional: total bytes cap
  "ap_mac": "11:22:33:44:55:66"  // optional: scope to specific AP
}
```

Response: `{"meta":{"rc":"ok"}, "data":[]}`. Once authorized, the client's MAC is whitelisted on the AP for the specified duration.

#### Unauthorize a guest (forzar logout)

```http
POST https://<controller>:8443/api/s/<site>/cmd/stamgr
{ "cmd": "unauthorize-guest", "mac": "aa:bb:cc:dd:ee:ff" }
```

#### List currently-authorized guests

```http
GET https://<controller>:8443/api/s/<site>/stat/guest
```

Returns guests with: `mac`, `ap_mac`, `authorized_by`, `start`, `end`, `duration` (seconds), `bytes_total`, `tx_bytes`, `rx_bytes`.

Útil para dashboards / reconciliación: "¿el cliente con click_id X efectivamente fue autorizado?".

#### Get a specific guest's session

```http
GET https://<controller>:8443/api/s/<site>/stat/user/<mac>
```

Returns: `bytes_total`, `connect_time` (seconds connected), `last_seen`, `network`, `essid`, plus device fingerprinting.

Bueno para mostrar al usuario "llevás 23 minutos navegando" si quisiéramos.

#### List active clients on an AP

```http
GET https://<controller>:8443/api/s/<site>/stat/sta
```

Returns ALL connected clients with state (`authorized`, `noted`, `is_guest`, etc.). Filter client-side por `ap_mac`.

#### Disconnect a client (kick)

```http
POST https://<controller>:8443/api/s/<site>/cmd/stamgr
{ "cmd": "kick-sta", "mac": "aa:bb:cc:dd:ee:ff" }
```

Forces reconnect — útil para forzar re-autenticación.

### Caveats conocidos

- **CSRF token expiración**: las sesiones del Controller expiran tras inactividad (~24h). El cliente debe re-loguear automáticamente al recibir 401.
- **TLS self-signed**: la mayoría de Controllers usan cert auto-firmado. El cliente HTTP debe configurarse para confiar (o terminar TLS en un proxy intermedio).
- **Rate limits**: el Controller no documenta límites pero puede tirar 503 bajo carga. Implementar retry con backoff.
- **MAC normalization**: UniFi normaliza a lowercase con `:`. El SPA / cliente puede mandar uppercase con `-`. Normalizar en el `UnifiService` antes de llamar.
- **Multi-controller setups**: algunas locations grandes tienen un Controller dedicado. El `UnifiConfig` puede ser per-location (en `location.yaml`) en lugar de global.
- **Webhooks UniFi**: el Controller puede ser configurado para llamar a un webhook on guest-events (authorize, deauthorize, disconnect). Alternativa al polling — menos código a futuro.

### Referencias

- UniFi Controller API (no oficial, mantenida por la comunidad): https://ubntwiki.com/products/software/unifi-controller/api
- Python SDK como referencia: https://github.com/Art-of-WiFi/UniFi-API-client
- Guía de captive portal authentication oficial: https://help.ui.com/hc/en-us/articles/115000166827

---

## Common task → file map

| Tarea | Archivos primarios |
|---|---|
| Agregar API endpoint | `endpoints/.../<Name>Endpoints.scala` + `api/.../<Name>Routes.scala` + `api/test/.../suites/<Name>Suite.scala`. Skill: `add-api-endpoint`. |
| Agregar Phase | `core/.../application/phase.scala` + `infra/.../eventhandlers/SessionPhaseHandler.scala` + `client/.../Router.scala` (`Page` enum + `phaseToPage`) + nueva view. Skill: `add-phase`. |
| Migración DB | `infra/resources/db/migration/V<n>__*.sql` + `infra/.../rows.scala` (campo) + `EntityWriter` (insert + upsert) + `ProvisionService` (pasar el value). Skill: `add-migration`. |
| Nuevo evento + handler | `core/.../<aggregate>/Event.scala` + `infra/.../eventhandlers/<Name>Handler.scala` + `.andThen` en `Main.eventHandlerLayer` Y `TestLayers.eventHandlerLayer`. Skill: `add-event-handler`. |
| Nueva entidad provisionable | `provision/.../models.scala` (YAML) + `infra/.../rows.scala` + migration + `EntityWriter` + `ProvisionService.{provisionX, scanDisk, provisionEntity, softDeleteEntity}` |
| Wire un AWS service en CLI | `cli/.../AwsLayers.scala` + agregar al `.provide(...)` en `cli/Main.scala` |
| Nueva skill | Crear `.agents/skills/<name>/SKILL.md` (dev) o `cli/resources/templates/skills/<name>/SKILL.md` (operator); registrar en `cli/.../Catalog.scala` si es operator |
| Release de API base | Skill: `bump-api-version` |
| Release del CLI | Skill: `bump-cli-version` |
| Correr el stack en local | Skill: `run-locally` |

---

## Symptom → first thing to check

| Síntoma | Primero |
|---|---|
| `401 SessionMissing` inesperado en `/api/status` | ¿El request lleva `X-Client-Mac` Y `X-Click-Id`? Ambos requeridos en CREATE flow. |
| Frontend muestra `[welcome.title]` literal | Location no provisionada (i18n rows missing) o cookie apunta a location distinta. `captal locations push <slug>`. |
| Video da 403 al cargarse | URL del `video.yaml` apunta a `<bucket>.s3.amazonaws.com/...` (bucket privado). Cambiar a `https://production.captal.centauroads.com/<slug>/<filename>` (vía CloudFront). |
| `survey.yaml` muestra "TODO" en prod | El operador no editó el placeholder de `captal video add`. Editar in-place (preserva respuestas) o borrar (soft-delete real desde API v1.3.0+). |
| API task no arranca, exit code 1 | Logs `/ecs/captal-<slug>`: probable `ProvisionService.run` con `PROVISION_DIR` o `LOCATION_SLUG` missing/inválido. |
| `captal locations push` falla en createService 400 "no associated load balancer" | TG creado pero ALB rule no — bug histórico. Verificar que `upsertAlbRule` corre ANTES de `createOrUpdateService` (orden: step 3 = ALB, step 4 = ECS). |
| `captal update` falla en Windows con "process cannot access the file" | Wrapper viejo (pre-1.5.3) sin lógica de swap. Bajar nuevo `captal.bat` manualmente. |
| rqlite queries devuelven 500 `no result set returned` después de redeploy | Fargate ephemeral perdió data. `aws ecs update-service ... --force-new-deployment` API services + `captal shared push` + `captal locations push-all`. Skill: `recover-data`. |
| Cookies no se isolan entre locations | Verificar en DevTools: nombre debe ser `captal_session_<slug>` con `Path=/<slug>`. Si dice solo `session_id`, API en versión vieja (< v1.1.0). |
| SPA muestra error page sin razón clara | `Runtime.run` escala fallos de Future. Revisar consola del browser para el error original. |

---

## Estado Actual del Proyecto

### Completado ✅
- **Backend**: API REST completa con Tapir + ZIO
- **Event Sourcing**: Handlers de eventos transaccionales
- **Base de datos**: Rqlite con Quill (sustituye a SQLite local), migraciones con Flyway (V1–V13)
- **Multi-tenancy**: Aislamiento por `location_id` en sesiones, i18n, surveys, advertisers, videos
- **Provisioning system**: Sync declarativo de YAML a BD con manifest hashing, soft-delete e idempotencia
- **CLI (`captal`)**: Inicializa proyecto, gestiona locations, sube videos/promos, deploya a AWS
- **Cliente web**: Laminar + Scala.js con routing (Waypoint), HTTP via `dom.fetch`
- **i18n**: Sistema de traducciones (ES/EN) cargadas desde BD por location
- **Cross-compilation**: Core y endpoints compartidos entre JVM y JS
- **Validacion client-side**: Usa logica del core via `Op.run`
- **Fase de video publicitario** (AdvertiserVideo) + tests E2E
- **Fase de encuesta de anunciante** (AdvertiserQuestion)
- **Loading screen** en todas las transiciones de vista
- **Phase validation**: Endpoints protegidos por fase con Tapir partial server endpoints
- **Docker**: `Dockerfile.api` y `Dockerfile.provision` (imagenes base) + Mill commands para bundle del cliente
- **Guix manifest**: Empaqueta el assembly del CLI (`manifest.scm`)
- **BuildInfo**: Variables de entorno en build time para cliente (ENVIRONMENT)
- **Skills portables**: 8 skills en `.agents/skills/<name>/SKILL.md` (compatible Claude Code + OpenCode)
- **Despliegue producción vivo**: dev environment con CloudFront + ALB en coexistencia (production.captal.centauroads.com -> CloudFront, staging.* -> ALB Lambda CDN)
- **Imagenes derivadas baked-in**: CLI construye y pushea imagenes con provision data via docker build local
- **CloudFront SPA**: distribution con SPA fallback CloudFront Function + base href dinamico para soportar slug-aware paths
- **Integración UniFi (authorize-guest)**: `UnifiAuthorizationHandler` post-commit en `infra/eventhandlers/` llama al UCG con trust-all SSL + proxy opcional, idempotente, con retry-by-design (session.phase queda en Ready si UniFi falla → próximo `/api/finish` reintenta)
- **Fase Authorized**: nueva fase + `accessExpiresAt` en sessions, `WelcomeView` muestra countdown, `/api/status` resetea fase al expirar (`resetForExpiration`)
- **HTTP proxy support**: `UNIFI_PROXY_URL` env var en el API server, configurado via CLI (`shared/captal.yaml: unifi.proxyUrl`) — habilita acceso al UCG on-prem vía tinyproxy + Tailscale en deploys cloud
- **`/api/finish` endpoint**: emite `UserFinishedProcess`, retorna `StatusResponse` post-commit con `accessExpiresAt` para que el cliente no necesite round-trip extra a `/api/status`

### Pendiente 📋
- **rqlite EFS**: migrar storage efimero Fargate a EFS para evitar perdida de datos en redeploys
- **JDBC resilience**: HikariCP test-on-borrow + reconexion auto cuando un nodo rqlite reinicia
- **rqlite deployment min healthy %**: configurar para mantener quorum durante restarts escalonados
- **CI release flow**: GH Action que automatice docker build/push de bases + bundle a S3
- **`captal bundle promote`**: comando para sincronizar el bundle global a todas las locations + invalidar CDN
- **`captal locations rm`**: command para limpiar service + TG + ALB rule + log group + ECR tags + S3 prefix de un location
- **Cleanup ECR legacy**: borrar repo `captal-dev` (orfanado de TF) cuando ya no se use
- **Mill task release.bundle**: integrar `client.bundle` + `aws s3 cp` con metadata correcta en un solo target

---

## Modelo de Negocio

### Propuesta de Valor

- **Para usuarios**: Acceso gratuito a WiFi
- **Para empresas (clientes del hotspot)**: Recoleccion de datos de usuarios, canal de publicidad
- **Para anunciantes**: Audiencia cautiva con datos demograficos

### Flujo Principal

```
Usuario escanea QR / accede al hotspot
         |
    Registra email (primera visita)
         |
    Responde 1-2 preguntas de perfilado (aleatorias)
         |
    Responde 1-2 preguntas de ubicacion (jerarquicas)
         |
    Visualiza publicidad o propaganda
         |
    [Si publicidad] Responde encuesta
         |
    Recibe acceso WiFi (max 5 min)
         |
    Al reconectarse -> repite ciclo con preguntas pendientes
```

---

## Arquitectura Tecnica

### Stack Tecnologico

| Tecnologia | Uso |
|------------|-----|
| Scala 3 | Lenguaje principal |
| Mill | Build tool |
| ZIO | Runtime, efectos, layers |
| Quill | Acceso a base de datos (con MappedEncoding) |
| Rqlite | Base de datos distribuida (driver JDBC) |
| Tapir | Definicion de endpoints HTTP |
| Cats | Typeclasses (Monad, Parallel) |
| Laminar + Waypoint | UI reactiva + routing en cliente |
| zio-cli | CLI `captal` para provisioning y deploy |
| AWS SDK v2 | S3 + ECS + ELBv2 desde el CLI |

### Estructura de Modulos

```
whitelabel-captal/
├── build.mill                    # Mill build (cross-compile, BuildInfo, rqlite container)
├── .mill-version                 # Version de Mill
├── mill                          # Mill launcher (tracked en repo)
├── Dockerfile.api                # Base image captal-api (API server)
├── Dockerfile.provision          # Base image captal-provision (task efimera)
├── manifest.scm                  # Guix manifest (incluye assembly del CLI)
├── .dockerignore                 # Exclusiones para Docker build
├── AGENTS.md                     # Este archivo
├── core/                         # Dominio y logica de negocio (cross-compiled JVM/JS)
│   └── src/whitelabel/captal/core/
│       ├── Op.scala              # Tipo Op[E, Er, A] para operaciones
│       ├── application/          # Handlers, eventos, fases, NextStep
│       ├── infrastructure/       # Traits de repositorios y SessionData
│       ├── survey/               # Agregado Survey (con AdvertiserQuestion)
│       └── user/                 # Agregado User
├── infra/                        # Implementaciones Quill + Rqlite
│   └── src/whitelabel/captal/infra/
│       ├── schema/                  # MappedEncoding y SchemaMeta
│       ├── rows.scala               # Row types con tipos de dominio
│       ├── RqliteDataSource.scala   # DataSource para Rqlite
│       ├── Migrate.scala            # Runner de migraciones Flyway
│       ├── SharedProvision.scala    # Entry point para provisioning shared
│       ├── eventhandlers/           # Handlers transaccionales
│       ├── repositories/            # Implementaciones Quill de repos
│       ├── services/                # LocaleService, LocationService, SurveyService
│       ├── session/                 # SessionContext, SessionService
│       └── provision/               # Sistema de provisioning declarativo
│           ├── ProvisionService.scala   # Sync YAML → BD (shared y por location)
│           ├── ProvisionPlan.scala      # Diff disk vs DB → Create/Update/Delete/Skip
│           ├── EntityWriter.scala       # Upserts y manifest tracking
│           ├── IdGenerator.scala        # Ids deterministicos a partir de slugs
│           └── models.scala             # Tipos del provisioning
├── api/                          # Capa HTTP con Tapir
│   ├── resources/
│   │   ├── application.conf
│   │   └── provision/dev/        # Provisioning de desarrollo (shared/ + locations/)
│   ├── src/whitelabel/captal/api/
│   │   ├── Main.scala                  # Entry point, layers, ServerSettings
│   │   ├── SessionEndpoint.scala       # Partial server endpoints (session + phase)
│   │   ├── SurveyRoutes.scala          # Rutas de identification surveys
│   │   ├── AdvertiserSurveyRoutes.scala # Rutas de encuesta del anunciante
│   │   ├── VideoRoutes.scala           # Rutas de video publicitario
│   │   ├── LocaleRoutes.scala          # i18n + dev reset
│   │   ├── HealthRoutes.scala          # /health para ALB
│   │   └── LocaleDetector.scala        # Detecta locale desde Accept-Language
│   └── test/
│       ├── resources/provision/        # Fixtures: basic, reduced, updated, updated-shared
│       └── src/whitelabel/captal/api/
│           ├── E2ETests.scala
│           ├── TestFixtures.scala  TestLayers.scala  TestHelpers.scala
│           └── suites/
│               ├── LocaleSessionSuite.scala
│               ├── SessionManagementSuite.scala
│               ├── SessionIsolationSuite.scala       # Aislamiento multi-location
│               ├── EmailSurveySuite.scala
│               ├── SurveyProgressionSuite.scala
│               ├── MultiQuestionSurveySuite.scala
│               ├── ValidationSuite.scala
│               ├── PhaseValidationSuite.scala
│               ├── VideoSuite.scala                  # Fase AdvertiserVideo
│               ├── AdvertiserVideoSurveySuite.scala  # Fase AdvertiserQuestion
│               └── ProvisioningSuite.scala           # ProvisionService E2E
├── endpoints/                    # Endpoints Tapir (cross-compiled JVM/JS)
│   └── src/whitelabel/captal/endpoints/
│       ├── SurveyEndpoints.scala
│       ├── AdvertiserSurveyEndpoints.scala
│       ├── VideoEndpoints.scala
│       ├── LocaleEndpoints.scala
│       ├── ApiError.scala  AnswerRequest.scala  StatusResponse.scala
│       ├── SetLocaleRequest.scala
│       └── schemas.scala
├── client/                       # Cliente Laminar (Scala.js)
│   ├── index.html.template       # Template renderizado por Mill (JS_PATH/CSS_PATH)
│   ├── assets/styles.css         # CSS extraido
│   ├── STYLING.md
│   └── src/whitelabel/captal/client/
│       ├── Main.scala            # Entry point + syncPhaseOnLoad
│       ├── Router.scala          # Routing con Waypoint
│       ├── AppState.scala        # Estado global (Var)
│       ├── ApiClient.scala       # Llamadas HTTP con dom.fetch (sin sttp)
│       ├── Runtime.scala         # Runtime ZIO para Scala.js
│       ├── BuildInfo.scala       # [GENERADO] ENVIRONMENT, isDevMode
│       ├── i18n/I18nClient.scala
│       └── views/
│           ├── Layout.scala                       # Layout con loading state
│           ├── WelcomeView.scala
│           ├── IdentificationQuestionView.scala
│           ├── VideoView.scala                    # Reproductor del video publicitario
│           ├── AdvertiserSurveyView.scala         # Encuesta tras video
│           └── ReadyView.scala
├── cli/                          # CLI `captal` (zio-cli)
│   ├── resources/templates/      # Templates copiados por `captal init`/`locations add`
│   │   ├── shared/               # captal.yaml, surveys/, advertisers/
│   │   ├── location/             # i18n, location.yaml, assets
│   │   ├── video/                # promo.yaml, video.yaml, surveys
│   │   └── skills/               # 8 skills (configure-aws, add-survey, etc.)
│   └── src/whitelabel/captal/cli/
│       ├── Main.scala            # zio-cli command tree
│       ├── CaptalConfig.scala    # Config leida desde shared/captal.yaml
│       ├── AwsLayers.scala       # Layers ZIO con clientes AWS
│       ├── CliError.scala
│       ├── Output.scala          # Helpers de impresion (colores)
│       ├── commands/
│       │   ├── InitCommand.scala            # `captal init [--claude]`
│       │   ├── SharedPushCommand.scala      # `captal shared push`
│       │   ├── LocationsAddCommand.scala    # `captal locations add <slug>`
│       │   ├── PushCommand.scala            # `captal locations push <slug>`
│       │   └── VideoCommand.scala           # `captal video add` y `add-promo`
│       └── templates/
│           ├── Catalog.scala     # Lista de templates
│           └── Template.scala    # Modelo de template
└── docs/
    └── EVENT_SOURCING_SUMMARY.md
```

---

## Patrones de Arquitectura

### 1. Tipo Op (Operacion con Eventos y Errores)

El tipo `Op[E, Er, A]` representa una operacion que puede:
- Emitir eventos de tipo `E`
- Fallar con errores de tipo `Er`
- Retornar un valor de tipo `A`

```scala
// core/src/whitelabel/captal/core/Op.scala
opaque type Op[+E, +Er, +A] = WriterT[Validated[NonEmptyChain[Er], _], Chain[E], A]

object Op:
  def pure[A](a: A): Op[Nothing, Nothing, A]
  def emit[E](event: E): Op[E, Nothing, Unit]
  def fail[Er](error: Er): Op[Nothing, Er, Nothing]
  def run[E, Er, A](op: Op[E, Er, A]): Either[NonEmptyChain[Er], (Chain[E], A)]
```

### 2. Handler Pattern

Los handlers encapsulan casos de uso. Usan parametros explicitos (no context bounds) para compatibilidad con ZIO layers:

```scala
// Patron de Handler
trait Handler[F[_], C]:
  type Result
  def handle(command: C): F[Op[Event, Error, Result]]

object Handler:
  type Aux[F[_], C, R] = Handler[F, C] { type Result = R }

// Ejemplo de implementacion
object AnswerProfilingHandler:
  def apply[F[_]: Monad: Parallel](
      surveyRepo: SurveyRepository[F],
      userRepo: UserRepository[F]
  ): Handler.Aux[F, AnswerProfilingCommand, QuestionAnswer] =
    new Handler[F, AnswerProfilingCommand]:
      type Result = QuestionAnswer
      def handle(cmd: AnswerProfilingCommand) = ...
```

### 3. Session Management con FiberRef

La sesion se maneja con `SessionContext` (FiberRef) y `SessionData`:

```scala
// core/infrastructure/Session.scala
final case class SessionData(
    sessionId: user.SessionId,
    userId: Option[user.Id],
    locale: String,
    phase: Phase,
    currentSurveyId: Option[survey.Id],
    currentQuestionId: Option[survey.question.Id])

// infra/SessionContext.scala
trait SessionContext:
  def get: UIO[Option[SessionData]]
  def getOrFail: Task[SessionData]
  def set(data: SessionData): UIO[Unit]

object SessionContext:
  val make: ZLayer[Any, Nothing, SessionContext] =
    ZLayer.scoped:
      FiberRef.make(Option.empty[SessionData]).map: ref =>
        new SessionContext:
          def get = ref.get
          def getOrFail = ref.get.someOrFailException
          def set(data: SessionData) = ref.set(Some(data))

// infra/SessionService.scala
trait SessionService:
  def findById(sessionId: user.SessionId): Task[Option[SessionData]]
  def create(deviceId: user.DeviceId, locale: String, phase: Phase): Task[SessionData]
  def setPhase(sessionId: user.SessionId, phase: Phase): Task[Unit]
  def setCurrentSurvey(sessionId: user.SessionId, surveyId: survey.Id, questionId: survey.question.Id): Task[Unit]
  def clearCurrentSurvey(sessionId: user.SessionId): Task[Unit]
```

### 4. Event Handlers

Los eventos se procesan mediante `EventHandler`. Para operaciones transaccionales se usa `DbEventHandler`:

```scala
// core/application/EventHandler.scala
trait EventHandler[F[_], -E]:
  def handle(events: List[E]): F[Unit]

object EventHandler:
  extension [F[_]: Monad, E](self: EventHandler[F, E])
    def andThen(other: EventHandler[F, E]): EventHandler[F, E] =
      events => self.handle(events) *> other.handle(events)

// infra/DbEventHandler.scala
trait DbEventHandler[-E]:
  def handle(events: List[E], quill: QuillSqlite): Task[Unit]

object DbEventHandler:
  extension [E](self: DbEventHandler[E])
    def andThen(other: DbEventHandler[E]): DbEventHandler[E] =
      (events, quill) => self.handle(events, quill) *> other.handle(events, quill)
```

### 5. Composicion de Event Handlers

Los handlers se componen con `andThen` y se envuelven en una transaccion:

```scala
// api/Main.scala
private val eventHandlerLayer: ZLayer[QuillSqlite & SessionContext, Nothing, EventHandler[Task, Event]] =
  ZLayer.fromFunction: (quill: QuillSqlite, ctx: SessionContext) =>
    val dbHandler = AnswerPersistenceHandler(ctx)
      .andThen(UserPersistenceHandler(ctx))
      .andThen(SessionPhaseHandler(ctx))
      .andThen(SessionSurveyHandler(ctx))
      .andThen(SurveyProgressHandler())
    TransactionalEventHandler(dbHandler, quill)
```

### 6. MappedEncoding para Tipos de Dominio

Los Row types usan tipos de dominio directamente gracias a `MappedEncoding`:

```scala
// infra/schema.scala
type QuillSqlite = Quill.Sqlite[SnakeCase]

object QuillSchema:
  // Schema Meta
  inline given SchemaMeta[UserRow] = schemaMeta[UserRow]("users")
  inline given SchemaMeta[SessionRow] = schemaMeta[SessionRow]("sessions")
  // ...

  // Mapped Encodings - User types
  @targetName("userIdToString")
  inline given MappedEncoding[user.Id, String] = MappedEncoding(_.asString)
  @targetName("stringToUserId")
  inline given MappedEncoding[String, user.Id] = MappedEncoding(user.Id.unsafe)

  @targetName("sessionIdToString")
  inline given MappedEncoding[user.SessionId, String] = MappedEncoding(_.asString)
  @targetName("stringToSessionId")
  inline given MappedEncoding[String, user.SessionId] = MappedEncoding(user.SessionId.unsafe)

  // Mapped Encodings - Survey types
  @targetName("surveyIdToString")
  inline given MappedEncoding[survey.Id, String] = MappedEncoding(_.asString)
  @targetName("stringToSurveyId")
  inline given MappedEncoding[String, survey.Id] = MappedEncoding(survey.Id.unsafe)

  // Mapped Encodings - Phase
  @targetName("phaseToString")
  inline given MappedEncoding[Phase, String] = MappedEncoding(Phase.toDbString)
  @targetName("stringToPhase")
  inline given MappedEncoding[String, Phase] = MappedEncoding(Phase.fromDbString)
```

### 7. Row Types con Tipos de Dominio

```scala
// infra/rows.scala
final case class UserRow(
    id: user.Id,
    email: Option[user.Email],
    locale: String,
    createdAt: String,
    updatedAt: String)

final case class SessionRow(
    id: user.SessionId,
    userId: Option[user.Id],
    deviceId: user.DeviceId,
    locale: String,
    phase: Phase,
    currentSurveyId: Option[survey.Id],
    currentQuestionId: Option[survey.question.Id],
    createdAt: String)

final case class AnswerRow(
    id: String,
    userId: user.Id,
    sessionId: user.SessionId,
    questionId: survey.question.Id,
    answerValue: String,
    answeredAt: String,
    createdAt: String)
```

### 8. Repository Pattern

Los repositorios siguen el patron:
- Trait abstracto en `core/infrastructure/`
- Implementacion Quill en `infra/`

```scala
// core/infrastructure/SurveyRepository.scala
trait SurveyRepository[F[_]]:
  def findAssignedEmailSurvey(): F[Option[Survey[State.WithEmailQuestion]]]
  def findWithProfilingQuestion(surveyId: survey.Id, questionId: survey.question.Id): F[Option[Survey[State.WithProfilingQuestion]]]
  def findWithLocationQuestion(surveyId: survey.Id, questionId: survey.question.Id): F[Option[Survey[State.WithLocationQuestion]]]
  def findNextIdentificationSurvey(): F[Option[NextIdentificationSurvey]]

// infra/SurveyRepositoryQuill.scala
object SurveyRepositoryQuill:
  def apply(quill: QuillSqlite, ctx: SessionContext): SurveyRepository[Task] = ...
  val layer: ZLayer[QuillSqlite & SessionContext, Nothing, SurveyRepository[Task]] = ...
```

---

## Modelo de Dominio

### Phase (Fases del Usuario)

```scala
// core/application/phase.scala
enum Phase:
  case Welcome                 // Pantalla de bienvenida (locale selection)
  case IdentificationQuestion  // Respondiendo preguntas de identificacion (email/profiling/location)
  case AdvertiserVideo         // Viendo video publicitario
  case AdvertiserVideoSurvey   // Encuesta atada al video que se acaba de ver
  case AdvertiserQuestion      // Encuesta general del anunciante
  case Ready                   // Survey terminado, esperando que UniFi autorice
  case Authorized              // UniFi confirmó voucher; accessExpiresAt poblado
```

`Ready → Authorized` solo lo transiciona `UnifiAuthorizationHandler` post-commit (no `SessionPhaseHandler`). Si UniFi falla / no está configurado, la sesión queda en `Ready` permitiendo retry vía nueva llamada a `/api/finish`.

### IdentificationSurveyType

```scala
enum IdentificationSurveyType:
  case Email, Profiling, Location
```

### Agregado Survey

```scala
// Estados del Survey
enum State:
  case WithEmailQuestion(question: QuestionToAnswer)
  case WithProfilingQuestion(question: QuestionToAnswer)
  case WithLocationQuestion(question: QuestionToAnswer, hierarchyLevel: HierarchyLevel)
  case WithAdvertiserQuestion(advertiserId: AdvertiserId, question: QuestionToAnswer)

// Entidad Survey tipada por estado
final case class Survey[S <: State](id: survey.Id, state: S)

// Tipos de pregunta
enum QuestionType:
  case Radio(options: List[QuestionOption])
  case Select(options: List[QuestionOption])
  case Checkbox(options: List[QuestionOption], selectionRules: List[SelectionRule])
  case Input(textRules: List[TextRule])
  case Rating(rangeRules: List[RangeRule])
  case Numeric(rangeRules: List[RangeRule])
  case Date(rangeRules: List[RangeRule])
```

### Agregado User

```scala
// Estados del User (type-state pattern)
enum State:
  case Guest                                       // pre-email
  case WithEmail(email: Email)
  case AnsweringQuestion(surveyId, questionId)     // mid-identification survey
  case WatchingVideo(videoId: video.Id)
  case AnsweringVideoSurvey(advertiserId, surveyId, questionId)
  case Ready(                                      // post-survey, pre-UniFi
      redirectUrl: String,
      watchedVideoId: Option[video.Id],
      answeredQuestionIds: List[FullyQualifiedQuestionId])
  case Authorized(                                 // ephemeral; retorno tipado de `finish` op
      redirectUrl: String,
      watchedVideoId: Option[video.Id],
      answeredQuestionIds: List[FullyQualifiedQuestionId])

// Entidad User tipada por estado
final case class User[S <: State](id: user.Id, state: S)
```

**Nota**: `State.Authorized` no se proyecta desde DB. El único path para obtener un `User[State.Authorized]` es el retorno del `finish` op (`User[State.Ready] → User[State.Authorized]` + emite `UserFinishedProcess`). El `UserRepository` sólo expone `findReadyUser(): Option[User[State.Ready]]`.

### Eventos

```scala
// Eventos de Survey
enum Event:
  case QuestionAnswered(surveyId: survey.Id, questionEvent: question.Event)

// Eventos de Question
enum Event:
  case EmailQuestionAnswered(userId, surveyId, questionId, answer, occurredAt)
  case ProfilingQuestionAnswered(userId, surveyId, questionId, answer, occurredAt)
  case LocationQuestionAnswered(userId, surveyId, questionId, hierarchyLevel, answer, occurredAt)
  case AdvertiserQuestionAnswered(userId, advertiserId, surveyId, questionId, answer, occurredAt)

// Eventos de User
enum Event:
  case UserCreated(userId, email, occurredAt)
  case NewUserArrived(userId, nextQuestion, occurredAt)
  case SurveyAssigned(userId, question, occurredAt)
  case IdentificationCompleted(userId, occurredAt)
  case VideoAssigned(userId, videoId, advertiserId, videoType, occurredAt)
  case VideoVisualized(userId, videoId, durationWatched, completed, occurredAt)
  case VideoSurveyAssigned(userId, advertiserId, question, occurredAt)
  case UserFinishedProcess(userId, videoId, answeredQuestionIds, occurredAt)
```

---

## Event Handlers Disponibles

**Chain transaccional** (`DbEventHandler`, todos corren en una sola transacción Quill vía `TransactionalEventHandler`):

| Handler | Funcion |
|---------|---------|
| `EventLogHandler` | Persiste todos los eventos en `event_log` para audit + analytics |
| `AnswerPersistenceHandler` | Persiste respuestas en `answers` |
| `UserPersistenceHandler` | Crea usuarios (o vincula existentes), actualiza session |
| `SessionPhaseHandler` | Actualiza fase de la session (Welcome → Identification → … → Ready) |
| `SessionSurveyHandler` | Actualiza survey/question actual en session |
| `SessionVideoHandler` | Actualiza `currentVideoId` y `currentAdvertiserId` en session |
| `SurveyProgressHandler` | Actualiza progreso del survey, marca completado |

**Post-commit** (`EventHandler[Task, Event]`, compuesto con `.andThen` después del `TransactionalEventHandler`; fallos no rollbackean DB):

| Handler | Funcion |
|---------|---------|
| `UnifiAuthorizationHandler` | Listen `UserFinishedProcess` → HTTPS al UCG con `cmd=authorize-guest` → on 2xx llama `sessionService.setAuthorized(sessionId, expiresAt)` (transición Ready→Authorized). HTTP failures: log + skip; session queda en Ready para retry. |

---

## Command Handlers Disponibles

| Handler | Comando | Resultado | Dependencias |
|---------|---------|-----------|--------------|
| `AnswerEmailHandler` | `AnswerEmailCommand` | `NextStep` | `SurveyRepository`, `nextStep` |
| `AnswerProfilingHandler` | `AnswerProfilingCommand` | `NextStep` | `SurveyRepository`, `UserRepository`, `nextStep` |
| `AnswerLocationHandler` | `AnswerLocationCommand` | `NextStep` | `SurveyRepository`, `UserRepository`, `nextStep` |
| `ProvideNextIdentificationSurveyHandler` | `ProvideNextIdentificationSurveyCommand` | `NextIdentificationSurvey \| NextStep` | `SurveyRepository`, `UserRepository`, `terminalPhase` |

**Nota:** Los handlers de respuesta ahora requieren un `NextStep` que indica la fase siguiente tras completar la accion.

---

## API Endpoints

### Identification Survey Endpoints

| Endpoint | Metodo | Path | Request | Response |
|----------|--------|------|---------|----------|
| Status | GET | `/api/status` | - | `StatusResponse` (phase, locale, **accessExpiresAt**) |
| Next Survey | GET | `/api/survey/next` | - | `SurveyResponse` (Survey \| Step) |
| Answer Email | POST | `/api/survey/email` | `AnswerRequest` | `SurveyResponse` |
| Answer Profiling | POST | `/api/survey/profiling` | `AnswerRequest` | `SurveyResponse` |
| Answer Location | POST | `/api/survey/location` | `AnswerRequest` | `SurveyResponse` |

### Video / Advertiser Endpoints

| Endpoint | Metodo | Path | Request | Response |
|----------|--------|------|---------|----------|
| Get Next Video | GET | `/api/video/next` | - | `VideoResponse` (Video \| Step) |
| Mark Watched | POST | `/api/video/watched` | `MarkVideoWatchedRequest` | `VideoWatchedResponse` |
| Get Advertiser Survey | GET | `/api/survey/advertiser/next` | - | `AdvertiserSurveyResponse` |
| Answer Advertiser Survey | POST | `/api/survey/advertiser` | `AnswerRequest` | `AdvertiserSurveyResponse` |

### Finish / Authorization

| Endpoint | Metodo | Path | Request | Response |
|----------|--------|------|---------|----------|
| Finish | POST | `/api/finish` | - | `StatusResponse` (post-commit: phase=Authorized + accessExpiresAt si UniFi succeed; Ready + None si falló/no-config) |

`/api/finish` emite `UserEvent.UserFinishedProcess` y deja que `UnifiAuthorizationHandler` post-commit llame al UCG. Tras el commit, se re-lee `sessionService.findById` para devolver el snapshot actualizado en la response (evita un round-trip extra a `/api/status`).

### Locale/i18n Endpoints

| Endpoint | Metodo | Path | Request | Response |
|----------|--------|------|---------|----------|
| Get Locales | GET | `/api/locales` | - | `List[String]` |
| Get I18n | GET | `/api/i18n/{locale}` | - | `I18n` |
| Set Locale | PUT | `/api/session/locale` | `SetLocaleRequest` | `StatusResponse` + Set-Cookie |

### Health & Dev Endpoints

| Endpoint | Metodo | Path | Notas |
|----------|--------|------|-------|
| Health | GET | `/health` | Para ALB health check |
| Reset Phase | POST | `/api/dev/reset-phase` | Solo si `server.dev-endpoints=true` |

Todos los endpoints usan autenticacion por cookie (`session_id`). La cookie se crea automaticamente en la primera request. La sesion ademas se asocia a un `location_id` (por subdominio o resolucion del ALB).

### Phase Validation

Los endpoints validan la fase del usuario via partial server endpoints (`SessionEndpoint.withPhase`):

| Endpoint | Fases Permitidas |
|----------|------------------|
| `GET /api/status` | Cualquiera |
| `GET /api/survey/next` | Welcome, IdentificationQuestion |
| `POST /api/survey/{email,profiling,location}` | IdentificationQuestion |
| `GET /api/video/next` | AdvertiserVideo |
| `POST /api/video/watched` | AdvertiserVideo |
| `GET /api/survey/advertiser/next` | AdvertiserVideoSurvey, AdvertiserQuestion |
| `POST /api/survey/advertiser` | AdvertiserVideoSurvey, AdvertiserQuestion |
| `POST /api/finish` | Ready |

Si la fase no coincide, retorna `ApiError.WrongPhase(current, expected)`.

---

## Provisioning System

El sistema de provisioning sincroniza configuracion declarativa (YAML) con la base de datos al arranque del servidor. Es **idempotente** y soporta **soft-delete** mediante un manifest hashed.

### Estructura Esperada

```
provision/<env>/
├── shared/                      # Recursos globales (compartidos entre locations)
│   ├── surveys/                 # email.yaml, profiling.yaml, location.yaml
│   └── advertisers/             # <slug>.yaml
└── locations/<slug>/            # Por location
    ├── location.yaml
    ├── i18n/{en,es}.yaml
    ├── promo/<slug>.yaml        # Videos promocionales (sin advertiser)
    └── videos/<slug>/
        ├── video.yaml           # Referencia a advertiser + slug
        └── surveys/<slug>.yaml  # Encuestas atadas a este video
```

### Flujo de Provisioning

`ProvisionService.runShared` y `ProvisionService.run`:

1. Escanea YAMLs en disco, calcula hash de cada entidad
2. Lee manifest actual de la BD (filtrado por location)
3. Compara → genera `Action.Create | Update | Delete | Skip`
4. Ejecuta upserts (creates+updates) y soft-deletes en transaccion
5. Actualiza el manifest con los nuevos hashes

**Nota**: El shared provisioning es **aditivo** (no borra). El por-location SI puede borrar, pero solo entidades cuyo `location_id` coincide.

---

## CLI (`captal`)

CLI implementado con zio-cli en el modulo `cli/`. Permite inicializar proyectos, agregar locations, subir videos a S3, y deployar a AWS (ECS + S3 + ALB).

### Comandos

```bash
captal init [--claude]                          # Crea shared/, locations/, .agents/skills/
                                                # --claude tambien crea symlink .claude/skills
captal shared push                              # Deploy de recursos shared via ECS task
captal locations add <slug>                     # Crea locations/<slug>/ desde template
captal locations push <slug>                    # Deploy de location (S3 + ECS + ALB rule)
captal video add <slug> <advertiser> <file>     # Sube video a S3 + crea video.yaml
captal video add-promo <slug> <file>            # Sube promo a S3 + crea promo.yaml
```

### Configuracion

`shared/captal.yaml` define AWS region, ECR images (4 repos), bucket S3 + bundlePrefix, cluster ECS, subnets/SGs, ALB listener, CloudFront distributionId, database URL y opcionalmente `unifi.proxyUrl` (HTTP proxy para que el handler UniFi llegue al UCG on-prem en deploys cloud — vacío = conexión directa). La skill `configure-aws` guia su llenado con comandos `aws` CLI. Si no se incluyen credenciales explicitas, el SDK usa la cadena default.

### Flujo de despliegue (que hace cada comando bajo el capot)

**`captal shared push`** (task efimera):
1. Build imagen `captal-shared:<ts>` FROM `images.provision` + COPY `shared/` → `/etc/captal/shared/`
2. Push a ECR
3. Register task definition `captal-shared-provision` con esa imagen
4. RunTask Fargate (one-shot)
5. Container ejecuta `java -cp infra.jar SharedProvision` que aplica YAMLs de surveys + advertisers a la DB
6. Poll hasta STOPPED, verificar exit code

**`captal locations push <slug>`** (service de larga duracion):
1. S3 copy server-side de `bundle/` → `<slug>/` (idempotente; preserva metadata Content-Encoding)
2. Sube custom assets (`locations/<slug>/assets/*`) a `<slug>/` con gzip
3. Build imagen `captal-locations:<slug>-<ts>` FROM `images.api` + COPY `locations/<slug>/` → `/etc/captal/provision/`
4. Push a ECR
5. Crea log group `/ecs/captal-<slug>` (idempotente, via SDK)
6. Crea/asegura target group `captal-<slug>` con health check `/<slug>/api/health`
7. Register task definition `captal-<slug>` con env vars `LOCATION_SLUG`, `PROVISION_DIR=/etc/captal/provision`, `DB_URL`, `SERVER_DEV_*` y — si `captal.yaml` tiene `unifi.proxyUrl` no vacío — `UNIFI_PROXY_URL` + `taskRoleArn` (requerido para ECS Exec)
8. Create/Update service ECS `captal-<slug>` con `loadBalancers` block (TG attached) + `enableExecuteCommand=true` + healthCheckGracePeriodSeconds=180
9. Upsert ALB rule path-pattern `/<slug>/api/*` → target group
10. CloudFront `createInvalidation` para `/<slug>/*`

### Bug conocido de zio-cli

`captal --help` no lista todos los subcomandos (Issue zio-cli #448). Workaround: usar `CliConfig.default.copy(finalCheckBuiltIn = false)` en `Main.scala`. Ejecutar `captal` sin args muestra todos los comandos correctamente.

### Caveats operativos descubiertos en deploy

- **rqlite + storage efimero Fargate**: cada redeploy de un nodo rqlite pierde su data local; el cluster recupera via Raft solo si manten quorum. Si 2/3 nodos reinician simultaneamente, datos pueden perderse. Mitigacion temporal: re-correr `shared push` + `locations push` para reinsertar. Solucion permanente pendiente: EFS mount para `/rqlite/file`.
- **JDBC connection stale tras rqlite redeploy**: el driver rqlite-jdbc no hace fail-over; si el nodo al que apunta desaparece, las queries fallan con `SQLException: No result set returned`. Mitigacion: `aws ecs update-service --force-new-deployment` para conexiones frescas.
- **Mill BuildInfo cache**: `client.generatedSources` no se invalida por cambio en env var `ENVIRONMENT`. Hay que `./mill clean client.generatedSources` antes de un bundle con env distinto. Sin esto, los flags de dev (`isDevMode`, boton reset) quedan stale en el bundle.
- **Bundle metadata**: subir `.gz` files a S3 requiere `--content-encoding gzip` y `--content-type` correcto. `aws s3 sync` por si solo no lo setea; usar `aws s3 cp` por archivo (ver "Release flow" arriba).

---

## Skills (Cross-agent)

Las skills viven en `.agents/skills/<name>/SKILL.md` con frontmatter YAML (`name`, `description`, `version`). Esta ubicacion es reconocida por **Claude Code** y **OpenCode**. Con `captal init --claude` se crea ademas el symlink `.claude/skills → ../.agents/skills`.

| Skill | Proposito |
|-------|-----------|
| `configure-aws` | Llenar `captal.yaml` con comandos AWS CLI |
| `add-survey` | Agregar surveys a `shared/surveys/` |
| `add-advertiser` | Agregar advertisers a `shared/advertisers/` |
| `deploy-shared` | Ejecutar `captal shared push` |
| `add-video` | Subir video con `captal video add` |
| `add-promo` | Subir promo con `captal video add-promo` |
| `edit-i18n` | Editar `i18n/{en,es}.yaml` por location |
| `deploy-location` | Ejecutar `captal locations push` |
| `add-location` | Onboarding end-to-end de una location nueva (compone init + i18n + assets + videos + push) |
| `recover-data` | Recuperar el SPA cuando rqlite perdió datos: force-deploy + re-correr shared/locations push |
| `troubleshoot-deployment` | Diagnosticar problemas comunes (target unhealthy, 500s, CloudFront cache, ECS DRAINING, etc.) |

---

## Testing

### E2E Tests con Tapir Stub

```scala
// api/test/.../E2ETests.scala
object E2ETests extends ZIOSpecDefault:
  private val clearAndSeedNoise = TestFixtures.clearAllData *> TestFixtures.seedNoiseData
  def spec = (
    suite("E2E")(
      suite("Identification Survey Flow")(
        LocaleSessionSuite.suite,
        SessionManagementSuite.suite,
        EmailSurveySuite.suite,
        SurveyProgressionSuite.suite,
        MultiQuestionSurveySuite.suite,
        ValidationSuite.suite,
        PhaseValidationSuite.suite,
        SessionIsolationSuite.suite,
        VideoSuite.suite,
        AdvertiserVideoSurveySuite.suite
      ) @@ TestAspect.before(clearAndSeedNoise.orDie),
      ProvisioningSuite.suite
    ) @@ TestAspect.sequential
  ).provideShared(TestLayers.testEnv, ZLayer.fromZIO(TestFixtures.migrate.unit))
```

### Suites Disponibles

| Suite | Cubre |
|-------|-------|
| `LocaleSessionSuite` | Deteccion de locale, set-locale, cookie de sesion |
| `SessionManagementSuite` | Sesion nueva, recurrente, perdida con usuario existente |
| `SessionIsolationSuite` | Aislamiento entre locations (multi-tenancy) |
| `EmailSurveySuite` | Email crea usuario, transicion de fase, validacion |
| `SurveyProgressionSuite` | Email → Profiling → Location → siguiente fase |
| `MultiQuestionSurveySuite` | Multiples preguntas dentro de un mismo survey |
| `ValidationSuite` | Reglas de validacion (vacio, formato) |
| `PhaseValidationSuite` | Endpoints rechazan requests en fase incorrecta |
| `VideoSuite` | Fase AdvertiserVideo (next, watched) |
| `AdvertiserVideoSurveySuite` | Encuestas atadas al video y al advertiser |
| `ProvisioningSuite` | `ProvisionService` aplica YAMLs a BD (basic, reduced, updated) |

### Rqlite en Tests

`build.mill` arranca un contenedor Docker de `rqlite/rqlite:8.36.2` antes de los tests (`ensureRqlite`). El driver JDBC apunta a `jdbc:rqlite:http://localhost:<port>`.

---

## Decisiones de Diseno

### 1. SessionContext con FiberRef

Se usa `FiberRef` para mantener el contexto de sesion durante una request. Los handlers acceden a la sesion via `SessionContext` inyectado.

### 2. Event Handlers Transaccionales

Los `DbEventHandler` comparten el contexto Quill y corren en una sola transaccion. Esto garantiza consistencia entre:
- Persistencia de respuestas
- Creacion de usuarios
- Actualizacion de fase
- Actualizacion de progreso

### 3. MappedEncoding para Tipos de Dominio

Los Row types usan tipos de dominio directamente (`user.Id`, `survey.Id`, `Phase`, etc.) gracias a `MappedEncoding`. Esto elimina conversiones manuales `.asString` / `.fromString` en las queries.

### 4. UserPersistenceHandler con Deteccion de Usuario Existente

Cuando un usuario responde con un email que ya existe, el handler vincula la sesion al usuario existente en lugar de crear un duplicado.

### 5. Typed States

Las entidades (`Survey`, `User`) estan tipadas por su estado, lo que hace imposible invocar operaciones invalidas en tiempo de compilacion.

---

## Convenciones de Estilo

### 1. Sintaxis Scala 3 con indentacion

Usar `:` en vez de `{}` para bloques. Scalafmt lo aplica automaticamente.

### 2. Nombres descriptivos en queries

```scala
// Correcto
inline def findByUserAndSurveyQuery = quote: (userIdParam: user.Id, surveyIdParam: survey.Id) =>
  query[UserSurveyProgressRow].filter(progress =>
    progress.userId == userIdParam && progress.surveyId == surveyIdParam)

// Incorrecto (nombres de una letra)
inline def findByUserAndSurveyQuery = quote: (u: user.Id, s: survey.Id) =>
  query[UserSurveyProgressRow].filter(p => p.userId == u && p.surveyId == s)
```

### 3. @targetName para MappedEncoding

Usar `@targetName` para evitar conflictos de erasure en encodings bidireccionales:

```scala
@targetName("userIdToString")
inline given MappedEncoding[user.Id, String] = MappedEncoding(_.asString)
@targetName("stringToUserId")
inline given MappedEncoding[String, user.Id] = MappedEncoding(user.Id.unsafe)
```

### 4. Formateo automatico

```bash
# Formatear
./mill mill.scalalib.scalafmt.ScalafmtModule/reformatAll __.sources

# Aplicar scalafix
./mill __.fix
```

---

## Comandos Utiles

```bash
# Compilar todo
./mill __.compile

# Tests (arrancan container de rqlite automaticamente)
./mill __.test
./mill core.test
./mill api.test                 # Incluye E2E + ProvisioningSuite
./mill infra.test
./mill cli.test

# Formatear / scalafix
./mill mill.scalalib.scalafmt.ScalafmtModule/reformatAll __.sources
./mill __.fix

# Build assembly JARs
./mill api.assembly             # -> out/api/assembly.dest/out.jar
./mill cli.assembly             # CLI ejecutable (consumido por manifest.scm)

# Cliente (Mill renderiza index.html con paths del JS/CSS)
./mill client.bundle            # Production
ENVIRONMENT=dev ./mill client.bundle

# Docker - imagenes base
./mill api.dockerBuild   <ecr-uri>/captal-api v1.0.0
./mill infra.dockerBuild <ecr-uri>/captal-provision v1.0.0

# CLI
./mill cli.run -- init --claude
./mill cli.run -- locations add cafe-centro
./mill cli.run -- video add cafe-centro chromecast ./blazes.mp4
```

---

## TODOs Pendientes

### Pruebas E2E en UI usando Playwright
Implementar tests E2E del cliente web (Welcome → IdentificationQuestion → Video → AdvertiserSurvey → Ready → Authorized) usando Playwright MCP.

### Tests para UnifiAuthorizationHandler
Mock del UCG con zio-http test server; cubrir happy-path (2xx → setAuthorized), 4xx/5xx (sin transición + retry funciona), timeout, no-config.

---

## Docker e imagenes base

El proyecto produce **dos imagenes base** que se pushean a ECR:

- `captal-api` (`Dockerfile.api`): JVM + `api.jar` assembly. Base del ECS service per-location. Incluye clases de `infra` (Migrate) por el assembly.
- `captal-provision` (`Dockerfile.provision`): JVM + `infra.jar` assembly. Base de la task efimera de `shared push`. Mas pequena (no incluye `api/`).

Ambas imagenes tienen `/etc/captal/` vacio. El CLI `captal` construye **imagenes derivadas** (FROM base + COPY provision yamls) durante `shared push` y `locations push`.

### Release flow del proyecto

```bash
# 1. Login a ECR
aws ecr get-login-password --region us-east-1 | \
  docker login --username AWS --password-stdin <account>.dkr.ecr.us-east-1.amazonaws.com

# 2. Build + push imagen API base
./mill api.dockerBuild <account>.dkr.ecr.us-east-1.amazonaws.com/captal-api v1.0.0
./mill api.dockerPush  <account>.dkr.ecr.us-east-1.amazonaws.com/captal-api v1.0.0

# 3. Build + push imagen Provision base
./mill infra.dockerBuild <account>.dkr.ecr.us-east-1.amazonaws.com/captal-provision v1.0.0
./mill infra.dockerPush  <account>.dkr.ecr.us-east-1.amazonaws.com/captal-provision v1.0.0

# 4. Bundle del cliente a S3 (con Content-Encoding: gzip para los .gz)
./mill client.bundle
B=s3://<bucket>/bundle
aws s3 cp out/client/bundle.dest/main.js.gz    $B/main.js.gz    --content-type application/javascript --content-encoding gzip
aws s3 cp out/client/bundle.dest/styles.css.gz $B/styles.css.gz --content-type text/css --content-encoding gzip
aws s3 cp out/client/bundle.dest/index.html    $B/index.html    --content-type "text/html; charset=utf-8"
aws s3 cp out/client/bundle.dest/brand-icon.svg $B/brand-icon.svg --content-type "image/svg+xml"

# 5. Actualizar `images.api` y `images.provision` en shared/captal.yaml de cada workspace operativo
```

Para desarrollo local, sigue funcionando `./mill api.dockerBuildDev` (con `SERVER_DEV_ENDPOINTS=true`).

---

## BuildInfo (Cliente)

`build.mill` genera `BuildInfo.scala` con la variable de entorno `ENVIRONMENT`. En production, `BuildInfo.isDevMode = false` activa dead-code elimination del codigo dev (botones reset, etc.).

```bash
ENVIRONMENT=dev ./mill client.bundle    # incluye features dev
./mill client.bundle                    # production
```

---

## Cambios Mayores Desde el Ultimo Update de AGENTS.md (commit 3a1f761)

> Resumen para alguien que no leyo el repo en un tiempo. Los detalles tecnicos viven en las secciones de arriba.

### Multi-tenancy a nivel de location
- Migracion `V12__add_location_id_to_localized_texts.sql` y campos `location_id` en surveys, advertisers, sessions
- `LocationService`, `LocaleDetector` resuelven la location desde la request
- `SessionIsolationSuite` valida que datos de una location no se filtren a otra

### Switch SQLite → Rqlite
- `RqliteDataSource` reemplaza al driver SQLite local
- `build.mill` arranca un container `rqlite/rqlite` para tests via `ensureRqlite`
- Eliminado `sqlite-jdbc`

### Sistema de Provisioning Declarativo
- `infra/provision/`: `ProvisionService`, `ProvisionPlan`, `EntityWriter`, `IdGenerator`
- Reemplazo del seeding manual por migraciones repetibles + sync de YAML
- Manifest hashed con soft-delete e idempotencia
- Estructura split: `shared/` (global) + `locations/<slug>/` (per-tenant)

### Nuevas Fases y Endpoints
- `Phase.AdvertiserVideo`, `AdvertiserVideoSurvey`, `AdvertiserQuestion`, `Welcome`
- `VideoRoutes`, `AdvertiserSurveyRoutes`, `HealthRoutes`
- `VideoEndpoints`, `AdvertiserSurveyEndpoints` cross-compilados
- Suites E2E nuevas: `VideoSuite`, `AdvertiserVideoSurveySuite`, `LocaleSessionSuite`, `SessionIsolationSuite`, `ProvisioningSuite`

### CLI `captal` (modulo nuevo)
- zio-cli con: `init [--claude]`, `shared push`, `locations add/push`, `video add`, `video add-promo`
- Templates en `cli/resources/templates/{shared,location,video,skills}`
- Workaround del bug de `--help` (`finalCheckBuiltIn = false`)
- Manifest Guix incluye el assembly del CLI

### Skills Cross-agent
- 8 skills en `.agents/skills/<name>/SKILL.md`
- `--claude` en `init` crea symlink `.claude/skills → ../.agents/skills`
- Compatible con Claude Code y OpenCode

### Cliente
- `dom.fetch` reemplaza al cliente sttp
- Loading screen en todas las transiciones de vista
- Vistas nuevas: `VideoView`, `AdvertiserSurveyView`
- `index.html` ahora se renderiza desde un template con `{{JS_PATH}}` y `{{CSS_PATH}}`

### Build / Deploy
- `Dockerfile.api` y `Dockerfile.provision` produce dos imagenes base distintas (la API completa + una minima para tasks efimeras de provisioning)
- `manifest.scm` para Guix (assembly del CLI como package)
- `cli.assembly` y `api.assembly` son los artefactos primarios

### Coexistencia CloudFront + Lambda CDN (deploy actual dev)
- `production.captal.centauroads.com` → CloudFront → S3 (bundle por location) + ALB (`/<slug>/api/*`)
- `staging.captal.centauroads.com` → ALB → Lambda CDN legacy + ECS legacy `captal-dev` (sin reglas `/api/*`, los endpoints van por las path-pattern rules creadas por la CLI)
- Apex sin registro DNS (decision operativa)
- Cert ACM regional para ALB + cert ACM en us-east-1 para CloudFront
- 4 repos ECR: `captal-{api,provision,shared,locations}-dev`. Las dos primeras son bases (push manual via Mill); las otras dos las crea la CLI on each push.

### CloudFront SPA fallback + slug routing
- CloudFront Function en viewer-request:
  - `/<slug>` (sin trailing slash) → 301 a `/<slug>/`
  - `/<slug>/` o `/<slug>/<sub-path>` → rewrite a `/<slug>/index.html` (sin redirect)
  - Files con extension pasan unchanged a S3
- Bucket policy de assets permite `s3:GetObject` (objects) y `s3:ListBucket` (bucket) a CloudFront via OAC; sin ListBucket S3 devuelve 403 en lugar de 404 para keys ausentes (rompe el `<link onerror>` para custom-styles opcional).
- `index.html.template` injecta `<base href="/<slug>/">` via inline `<script>` antes de `<link>`/`<script>` siguientes, para que paths relativos resuelvan bajo `/<slug>/` aunque la URL bar sea `/<slug>/<spa-route>` (waypoint usa pushState para SPA routes).

### Bundle release flow
```bash
# Imagenes base (cuando hay release nuevo del proyecto)
aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin <ecr-host>
./mill api.dockerBuild   --repoUri <ecr>/captal-api-dev      --tag vX.Y.Z
./mill api.dockerPush    --repoUri <ecr>/captal-api-dev      --tag vX.Y.Z
./mill infra.dockerBuild --repoUri <ecr>/captal-provision-dev --tag vX.Y.Z
./mill infra.dockerPush  --repoUri <ecr>/captal-provision-dev --tag vX.Y.Z

# Bundle del cliente con metadata correcta (gzip + content-type)
ENVIRONMENT=dev ./mill client.bundle   # o sin ENVIRONMENT=dev para production
B=s3://<bucket>/bundle
aws s3 cp out/client/bundle.dest/main.js.gz    $B/main.js.gz    --content-type application/javascript --content-encoding gzip
aws s3 cp out/client/bundle.dest/styles.css.gz $B/styles.css.gz --content-type text/css                --content-encoding gzip
aws s3 cp out/client/bundle.dest/index.html    $B/index.html    --content-type "text/html; charset=utf-8"
aws s3 cp out/client/bundle.dest/brand-icon.svg $B/brand-icon.svg --content-type "image/svg+xml"

# Si cambiaste ENVIRONMENT desde el ultimo build:
./mill clean client.generatedSources    # invalida BuildInfo cache antes de bundle

# CLI release (jar + wrappers cross-platform)
./mill cli.publishS3 --bucket captal-cli-releases --version 1.0.0
# Sube a s3://captal-cli-releases/v1.0.0/{captal.jar, captal, captal.bat}
# y a s3://captal-cli-releases/latest/ con los mismos archivos
```

### CLI distribution (operator install)

`./mill cli.releaseAssets` produce 3 archivos en `out/cli/releaseAssets.dest/`:
- `captal.jar` — assembly ejecutable
- `captal` — bash wrapper (Linux/macOS) — `chmod +x` ya seteado
- `captal.bat` — batch wrapper (Windows)

`./mill cli.publishS3 --bucket <b> --version <v>` los sube a `s3://<b>/v<v>/` y `s3://<b>/latest/`.

`./mill cli.installLocal` instala localmente en `~/.local/bin/` (atajo para developers del proyecto, no para releases).

**Operador instala (Linux/macOS)**:
```bash
mkdir -p ~/.local/bin
aws s3 cp s3://captal-cli-releases/latest/captal.jar ~/.local/bin/captal.jar
aws s3 cp s3://captal-cli-releases/latest/captal     ~/.local/bin/captal
chmod +x ~/.local/bin/captal
captal init --claude
```

**Operador instala (Windows)**:
```powershell
$dir = "$env:USERPROFILE\AppData\Local\captal"
mkdir $dir -Force
aws s3 cp s3://captal-cli-releases/latest/captal.jar "$dir\captal.jar"
aws s3 cp s3://captal-cli-releases/latest/captal.bat "$dir\captal.bat"
$env:Path += ";$dir"   # o agregar permanentemente via System Properties
captal init --claude
```

**TODO**: el bucket `captal-cli-releases` es nuevo; agregar a Terraform infra (versioning + lifecycle policy para cleanup de releases viejos).

### Recovery: rqlite perdio data
Si tras redeploy de rqlite (o force-deploy del API) las queries devuelven 500 / "no result set returned":
1. Force-deploy del API service (`aws ecs update-service ... --force-new-deployment`) para conexiones JDBC frescas
2. Re-correr `captal shared push` para reinsertar surveys + advertisers
3. Re-correr `captal locations push <slug>` para reinsertar location + i18n + videos + promos
4. CloudFront invalidacion automatica via locations push

---

## Cambios Recientes (sesiones dev/iteracion)

> Resumen de lo trabajado en sesiones recientes despues del primer deploy a CloudFront. Detalles tecnicos por archivo en cada subseccion.

### Aislamiento de sesiones por location (API v1.1.0)

- **Bug**: el SPA sirve `/cafe-centro/` y `/valmy/` bajo el mismo dominio; la cookie `session_id` con `Path=/` se enviaba a TODAS las locations → sesiones mezcladas.
- **Solucion**: cookie name + path slug-aware:
  - Nombre `captal_session_<slug>` (e.g. `captal_session_cafe-centro`)
  - Path `/<slug>` (e.g. `Path=/cafe-centro`)
  - El browser indexa por `(domain, path, name)` → entradas separadas en DevTools, solo manda la relevante a cada location.
- **Nuevo `SessionCookieConfig` case class** (`api/SessionCookieConfig.scala`): `fromSlug(Option[String])` produce config con `tapirInput`/`tapirOutput` para Tapir y `asMeta` para `CookieValueWithMeta`. Single source of truth derivada de `ServerSettings.locationSlug`.
- **Refactor a clases**: `SessionEndpoint`, `SurveyRoutes`, `LocaleRoutes`, `VideoRoutes`, `AdvertiserSurveyRoutes` pasaron de `object` a `final class` parametrizada (Tapir requiere el nombre del cookie en construction time del endpoint). Wiring via ZLayer en `api/Main.scala` `appLayers`.
- **El cliente NO cambia** — usa `dom.fetch` puro y deja al browser manejar cookies. El nombre del cookie es transparente para Scala.js.
- **`endpoints/` modulo intacto** — los `val`s de `SurveyEndpoints` (answerEmail, nextSurvey, etc.) NO se usan en runtime por la API; `SurveyRoutes` redefine via `sessionEndpoint.secured(...).post.in(...)`. Solo aparecen en OpenAPI/Swagger.
- **Migracion de cookies legacy**: no se hace cleanup activo del `session_id` huerfano; queda inerte porque nadie lo lee.

### Soft-validate de AP MAC (API v1.1.0+)

- **`CurrentLocation` case class** (`api/CurrentLocation.scala`): snapshot al startup de `(id, slug, apMac)` de la location del API instance. Cargada via `LocationService.findBySlug`. Empty para dev/test sin slug.
- **`SurveyRoutes.softValidateApMac`**: en cada `/api/status`, si el header `X-Ap-Mac` no matchea `location.ap_mac` provisionado, **logea warning** (no rechaza). Catchea typos en `location.yaml` sin bloquear sesiones. MAC se normaliza (`lower`, `-` → `:`).
- **Promote a hard fail (403)** si en el futuro queremos AP discipline.

### User cookie cross-location para skip de email (API v1.2.0)

- **Feature**: si un usuario respondio email en `/cafe-centro/`, al visitar `/valmy/` debe saltarse esa pregunta y avanzar al siguiente paso de identificacion.
- **Disponible porque la `users` tabla es global** (sin `location_id`) y `UserPersistenceHandler` reusa user_id existente cuando matchea por email. `NextIdentificationSurveyHandler` ya skip-ea preguntas ya respondidas por `user_id`.
- **Solucion**: cookie `captal_user=<userId>` con `Path=/` (compartida entre todas las locations).
  - Set en `answerEmailRoute` despues de que el flow popule `session.userId` via `SessionContext`.
  - Read en `statusRoute`: si hay cookie con UUID valido + el user existe en DB (vía nuevo `UserLookup` service), crea la sesion con `userId` pre-populado (`OnMissing.CreateForUser`).
  - Atributos: `HttpOnly; Secure; SameSite=Lax; Max-Age=2592000` (30 dias).
- **`UserCookieConfig`** (`api/UserCookieConfig.scala`): config estatica (no slug-aware).
- **`UserLookup` service** (`api/UserLookup.scala`): id-based existence check, separado de `UserRepository` para evitar shadow `Tapir.query`/`Quill.query`.
- **`SessionService.createForUser`**: variante de `create` que setea `userId` desde el inicio.
- **Anti-forgery**: el userId del cookie se valida contra `users` table; un UUID forjado no matchea ninguna fila y se ignora. Sin HMAC (consecuencia de un cookie valido limitada a "skip-ear email"; documentado como TODO si crece superficie).

### Click ID requerido (API v1.4.0 — pendiente de deploy)

- **Migracion V14**: `ALTER TABLE sessions ADD click_id TEXT NOT NULL DEFAULT ''`. Filas legacy: `''`. Nuevas: requeridas.
- **`SessionRow.clickId: String`**, `SessionData.clickId: String`, `CaptivePortalParams.clickId: String`.
- **`SurveyRoutes.statusRoute`**: nuevo `securityIn(header[Option[String]]("X-Click-Id"))`. Construye `portalParams` solo si AMBOS `clientMac` y `clickId` estan presentes (y `clickId` no vacio). Si falta cualquiera en CREATE flow, falla con `ApiError.SessionMissing`. Existing sessions (cookie presente) ignoran los params.
- **SPA**: `parseCaptivePortalHeaders` en `client/Main.scala` y `views/ErrorView.scala` mapea `click_id` (query param) → `X-Click-Id` (header).
- **Skill `add-location`**: URL de test ahora incluye `&click_id=<token>` y la tabla de params marca `id` y `click_id` como `Required: yes`.

### Bug fixes del cliente

- **Brand-icon path absoluto** (`views/Layout.scala`, `AdvertiserVideoView.scala`, `Main.scala`): cambio de `src := "/brand-icon.svg"` → `src := "brand-icon.svg"`. Las URLs absolutas ignoraban el `<base href="/<slug>/">` inyectado por el inline script de `index.html.template` y aterrizaban en `/brand-icon.svg` (404 fuera del slug). Con relativa, resuelve a `/<slug>/brand-icon.svg`.
- **Video URLs apuntaban a S3 directo** (`cli/commands/VideoCommand.scala`): `captal video add` generaba `url: https://<bucket>.s3.amazonaws.com/...` que daba 403 por bucket policy (allow only CloudFront via OAC). Cambiado a `https://${config.alb.domain}/<s3Key>` (CloudFront dominio publico). La SPA fallback function passthrough archivos con extension.
- **ALB rule + ECS service order** (`cli/commands/PushCommand.scala`): TG se creaba antes del ECS service createService, pero el TG no tenia ALB rule asociada → AWS rechazaba el createService. Reordenado: step 3 = "Configuring ALB rule" (crea TG + ALB rule); step 4 = "Updating ECS service".
- **TG health check too strict**: defaults (timeout 5s, healthy 5, unhealthy 2) tiraban targets sanos por jitter. Ajustados a `interval 30s, timeout 10s, healthy 2, unhealthy 5` via `modifyTargetGroup` (corre en cada push para que TGs existentes hereden settings nuevos).
- **ECS grace period**: subido a 300s (de 180s) para que la API tenga tiempo de Migrate + provision antes que TG marque unhealthy.

### Pagina de error centralizada

- **`ErrorView` + `Router.ErrorPage`** (`client/views/ErrorView.scala`, `client/Router.scala`): ruta `/error`, renderiza con i18n keys `error.title`/`error.generic`/`error.retry`. Botón retry vuelve a hacer `getStatus` y deja al router sincronizar fase.
- **`AppState.error: Var[Option[ApiError]]`** — fuente de verdad del ultimo error.
- **`ErrorHandler.escalate(err)` / `escalateMessage(s)`** (`client/ErrorHandler.scala`): unico punto de fallback. Setea `AppState.error` y navega a `/error`.
- **Refactor de views**: `IdentificationQuestionView`, `AdvertiserVideoSurveyView` ya no tienen `serverError` Var ni el helper `errorToMessage`. Todos los `case Left(_)` silenciosos (en `Main`, `WelcomeView`, `ReadyView`, `AdvertiserVideoView`) ahora llaman `ErrorHandler.escalate`. **Validation errors siguen inline** (`.validation-error` con `question.invalidEmail` etc.) — no son API errors.
- **`Runtime.run` escala Future failures** (timeout, decode crash) tambien.
- **Video load error**: nuevo `videoEl.addEventListener("error", ...)` en `AdvertiserVideoView` escala cuando el browser falla cargando el `<video>` (404 de S3, codec mismatch, network) — sin esto, el usuario veia un player negro sin feedback.
- CSS selectors (`.error-view`, `.error-content`, `.error-icon`, `.error-title`, `.error-message`) ya estaban definidos en `client/assets/styles.css`.

### Wiring de soft-delete (API v1.3.0)

- **Bug**: `ProvisionService.softDeleteEntity` solo loggeaba "Don't know how to soft-delete" para `video:`, `video-survey:`, `i18n:` (catch-all). Borrar un `survey.yaml` o `<locale>.yaml` no hacia nada en DB → SPA seguia sirviendo el contenido viejo (e.g. surveys placeholder con "TODO").
- **Solucion**: wireados los casos faltantes:
  - `video:<slug>/<videoSlug>` — brute-force: por cada advertiser activo, llama `deactivateVideo(videoId(slug, adv.id, videoSlug))`. Solo el id que existe pega; los demas son UPDATE no-op. Helper nuevo `EntityWriter.listActiveAdvertisers`.
  - `video-survey:<slug>/<videoSlug>/<surveySlug>` — `deactivateSurvey(advertiserSurveyId(...))`. SPA filtra por `survey.is_active=1`.
  - `i18n:<slug>/<locale>` — `EntityWriter.deleteI18nForLocation(locationId, locale)` (hard-delete; `localized_texts` no tiene `is_active`).
- **Caveat**: las `answers` rows siguen apuntando a question_ids que quedan "orfanas" (las tablas `questions`/`question_options` no tienen `is_active` propio). La SPA filtra antes de descender, no las ve, pero quedan en DB como ruido para analytics. Manual SQL purge si se quiere limpiar.
- **`softDeleteEntity` ahora recibe `locationId`** como tercer parametro (necesario para `i18n:`).

### Coexistencia rqlite single-node + multi-node (Terraform)

- `modules/rqlite/main.tf`: branch interno por `var.desired_count`:
  - `desired_count = 1` (dev): **EFS persistente** mount en `/rqlite/file`. Capacity provider FARGATE (no eviction). Deploy stop-then-start (`max=100, min=0`) para garantizar que solo una rqlited escribe sobre EFS. Nuevo `aws_efs_file_system` + mount targets + access point + EFS SG + task role policy `elasticfilesystem:Client*`.
  - `desired_count >= 3` (prod): ephemeral + FARGATE_SPOT + rolling (`max=200, min=50`) como antes. S3 auto-backup activo.
- Validacion: `desired_count` debe ser 1 o impar >= 3.
- Dev `terraform.tfvars`: `rqlite_desired_count = 1` ahora; prod sigue en 3.

### CLI distribution publica + self-update

- **Bucket `captal-cli-releases-dev` ahora publico** para `latest/*` y `v*/*` (cambio en `modules/cli-releases/main.tf`): bucket policy + relajar `block_public_policy`/`restrict_public_buckets` (manteniendo `block_public_acls=true`). Operadores descargan sin AWS creds.
- **`captal update`** (`cli/commands/UpdateCommand.scala`):
  - Fetch via `HttpURLConnection` (no AWS SDK, no creds) de `<bucket-url>/latest/version.txt`.
  - Compara con `Main.cliVersion`. Si distintos, descarga `<bucket-url>/latest/captal.jar` a `<currentJarPath>.new`.
  - **NO reemplaza in-place** (Windows lockea el JAR mientras la JVM corre).
- **Wrapper scripts hacen el swap** al startup (en `build.mill`):
  - `captal` (bash): `if [ -f "$DIR/captal.jar.new" ]; then mv -f ... captal.jar; fi; exec java -jar captal.jar "$@"`.
  - `captal.bat` (Windows): `if exist "%DIR%captal.jar.new" move /Y captal.jar.new captal.jar; java -jar captal.jar %*`.
- **`captal skills update`** (`cli/commands/SkillsUpdateCommand.scala`): syncea `.agents/skills/` con las skills bundleadas en el JAR (via `Catalog.skillsTemplates`). Solo agrega faltantes; no sobreescribe existentes.
- **`cli.publishS3` Mill task** ahora tambien publica `latest/version.txt` y `v<version>/version.txt`.

### Docker auth automatica en push

- `DockerImageBuilder.buildAndPush` (`cli/docker/DockerImageBuilder.scala`): el `ecrLogin` ahora corre **antes** del `docker build` (no despues como antes). Necesario porque `FROM $BASE` en el Dockerfile derivado tira de ECR — si el operador no esta logueado, el pull falla. Ya no hace falta `aws ecr get-login-password | docker login` manual antes de `captal shared push` o `captal locations push`.

### Skills nuevas y actualizadas

- `yaml-reference` (NUEVA): referencia completa de schemas YAML del proyecto (captal.yaml, surveys/, advertisers/, location.yaml, i18n/, videos/, promo/) con tabla de tipos de pregunta y reglas de validacion.
- `i18n-reference` (NUEVA): listado completo de las 36 claves i18n requeridas con placeholders + baselines copy-paste para es/en.
- `style-reference` (NUEVA, v1.1.0): API completa de CSS variables (~50 properties) + anatomia DOM por pantalla (Welcome, Question, Video, Survey, Ready, Error, Initial loader, Nav overlay) + recipes practicos + class hooks compartidos + state modifiers.
- `add-location`: paso 8 expandido con URL de test UniFi (`?id=...&ap=...&ssid=...&url=...&click_id=...`) + tabla de params + `click_id` marcado como required.
- `add-video`: bloque ⚠️ critico explicando que `surveys/survey.yaml` se crea con `text: "TODO"` y opciones `"TODO"`/`"TODO"`; el operador DEBE editarlo o borrarlo antes de push. Aclara que el soft-delete del video-survey ahora funciona (API v1.3.0+).
- `add-survey`: nota arriba que solo cubre identification surveys (email/profiling/location); para video surveys remite a `add-video`.
- 13 skills totales se distribuyen con `captal init` y `captal skills update`.

### Versiones publicadas en ECR / S3

- **API**: `captal-api-dev:v1.0.0` → `v1.1.0` (cookie isolation + soft-validate + cross-location user) → `v1.2.0` (= v1.1.0 + soft-delete wiring) → `v1.3.0` (= v1.2.0 + click_id pendiente push como v1.4.0).
- **Provision**: `captal-provision-dev:v1.0.0` → `v1.3.0` (con el soft-delete wiring).
- **CLI**: `1.0.0` → `1.1.x` (ALB rule fix, target group settings) → `1.2.0` (modify TG settings via push) → `1.3.x` (video CloudFront URL fix, brand-icon path fix, skills nuevas) → `1.4.0` (rolloutApiBase, revertida) → `1.5.0` (self-update + skills update) → `1.5.1` (HTTP fetch publico) → `1.5.2` (ECR auth antes de build) → `1.5.3` (wrapper-based JAR swap para Windows) → `1.5.4` (skills add-video/add-survey TODO note) → `1.6.3`. Publicada en `s3://captal-cli-releases-dev/latest/` y `v<version>/`.

---

## Documentacion Adicional

- **docs/EVENT_SOURCING_SUMMARY.md**: Detalle completo del modelo de Event Sourcing
- **client/STYLING.md**: Variables CSS y theming del cliente
- **Imagenes del Board de Miro** (*.jpg): Diagramas visuales originales del diseno
