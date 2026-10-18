# Contexto del Proyecto - Portal Captivo Whitelabel

## TL;DR para Agentes

- **Stack**: Scala 3 / Mill / ZIO / Tapir / Quill / rqlite + Scala.js (Laminar / Waypoint) en el frontend.
- **Deploy**: AWS ECS Fargate + ALB + CloudFront en la cuenta `460486036288` (region `us-east-1`).
- **Dominio**: `production.captal.centauroads.com` (CloudFront) y `staging.captal.centauroads.com` (ALB legacy).

**Primer paso para cualquier tarea — leer la skill relevante**:
- Skills **operacionales** (agregar location/video/survey, troubleshoot, recuperar datos): viven en
  `mcp/resources/skills/<name>/SKILL.md` y se sirven al modelo con la tool `read_skill`. Ya no se
  distribuyen a un workspace: el operador no tiene archivos, tiene tools.
- Skills **de desarrollo** (agregar endpoint / phase / migración, correr local): `.agents/skills/<name>/SKILL.md` (solo viven en este repo).

**Comandos de descubrimiento del estado real** (más rápido que confiar en este doc):

```bash
# Última imagen API en ECR
aws ecr describe-images --repository-name captal-api-dev --region us-east-1 \
  --query 'sort_by(imageDetails[?imageTags!=null],&imagePushedAt)[-1].imageTags[0]'

# Target groups en el ALB
aws elbv2 describe-target-groups --region us-east-1 \
  --query 'TargetGroups[?starts_with(TargetGroupName,`captal-`)].TargetGroupName'
```

Para cualquier task concreta — leé la skill primero, después AGENTS.md, después el código.

> **La CLI `captal` y el provisioning por archivos ya no existen.** Se borraron enteros: el binario,
> los YAML, el árbol `locations/<slug>/` y `shared/`, el motor de diff, las imágenes derivadas y el
> bundle de OpenTofu que la CLI extraía. El contenido se escribe hoy por las tools del servidor MCP
> directamente a la base, y la infraestructura vive en un repo aparte
> (`../whitelabel-captal-infrastructure/`) que se clona cuando hace falta. Si este documento o una
> skill menciona `captal <algo>`, es un resto que sobrevivió a la limpieza y hay que tratarlo como un
> bug, no como instrucción — el modelo obedece la documentación.

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
│   └── src/whitelabel/captal/platform/provision/
│       └── models.scala                 # LocationYaml, SurveyYaml, VideoYaml, ...
│
├── platform/                               # DB + Event handlers + Services (JVM only)
│   ├── resources/
│   │   ├── application.conf
│   │   └── db/migration/V*.sql          # Flyway migrations (V1 … V17 hoy: V16=unifi fields, V17=access_expires_at)
│   └── src/whitelabel/captal/platform/
│       ├── Migrate.scala                # Flyway runner
│       ├── RqliteDataSource.scala       # JDBC wrapper sobre rqlite HTTP
│       ├── rows.scala                   # *Row case classes (Quill)
│       ├── UnifiAccess.scala            # Per-location UniFi config (host, token, port, site, …)
│       ├── schema/                      # SchemaMeta + decoders + QuillSqlite type
│       ├── eventhandlers/               # DbEventHandler chain + post-commit handlers
│       │   ├── EventLogHandler, AnswerPersistenceHandler, UserPersistenceHandler
│       │   ├── SessionPhaseHandler, SessionSurveyHandler, SessionVideoHandler
│       │   ├── SurveyProgressHandler, TransactionalEventHandler
│       │   ├── UnifiAccessHandler    # post-commit: calls UniFi authorize-guest
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
├── mcp/                                 # Servidor MCP — el backoffice
│   ├── resources/
│   │   ├── db/migration-mcp/            # Historial propio: V1__mcp_tokens.sql
│   │   └── skills/                      # Las 12 skills operacionales, servidas por read_skill
│   └── src/whitelabel/captal/mcp/
│       ├── Main.scala, CaptalMcp.scala  # Wiring, instructions, visibilidad de tools
│       ├── Auth.scala, Tokens.scala     # Scopes y tokens hasheados en mcp_tokens
│       ├── *Tools.scala                 # Las 40 tools, por entidad
│       ├── UploadUi.scala, UnifiUi.scala  # MCP Apps renderizadas server-side
│       └── UiRoutes.scala, UiTicket.scala # El POST de las páginas, firmado con HMAC
│
├── client/                              # Un linker run, cuatro módulos públicos:
│   ├── src/.../client/                  #   el portal (Laminar), `@main`
│   ├── src/.../mcpui/                   #   las dos MCP Apps (solo scalajs-dom)
│   ├── src/.../maintenance/             #   la página de pausa que sirve el edge
│   └── entries/                         #   los stubs que llaman a `start` — uno por módulo
│                                        #   público que no sea el portal, porque un run da un
│                                        #   solo module initializer y lo tiene `main`
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

> Convención: módulos **JVM-only** = `platform/`, `api/`, `cli/`. **Cross-compile** (JVM+JS) = `core/`, `endpoints/`. `provision/` es JVM-only pero minimal (solo case classes + circe decoders, sin Quill/DB) para que pueda ser dep del CLI sin arrastrar 60+ MB.

---

## Architecture invariants

Cosas que NUNCA deben violarse — usadas como checklist mental al editar:

- **Una `Phase` siempre tiene** una entrada en `Router.phaseToPage`, un caso en `SessionPhaseHandler` (si tiene transition), y validación vía `sessionEndpoint.secured(allowedPhases = ...)` en los endpoints que la requieran.
- **`users` es global** — no tiene `location_id`. El resto de entidades (`surveys`, `advertiser_videos`, `localized_texts`) sí están scopeadas por location.
- **IDs son UUID aleatorio.** Eran uuid5 determinístico de un seed string, y eso **ya no es cierto**: existía para que re-aplicar el mismo YAML fuera un upsert y no un duplicado, y con CRUD explícito no tiene sentido. Consecuencia que importa: `IdGenerator` era el guardián implícito de las claves naturales de **todas** las entidades, así que la unicidad hay que validarla en Scala antes de escribir — es lo que hacen `create_advertiser` con el nombre y `create_location` con el slug.
- **Las escrituras verifican filas afectadas.** rqlite reporta una sentencia rechazada dentro de un HTTP 200 y el driver lo convierte en cero filas, así que una constraint violada **no** llega como excepción. `Writes.expectOne` es la red; sin él una escritura rechazada reporta éxito.
- **Cookie de sesión**: name = `captal_session_<slug>`, `Path=/<slug>`. **NUNCA** usar `session_id` global (sesiones se mezclarían entre locations).
- **Cookie user cross-location**: name = `captal_user`, `Path=/`. Identifica al usuario entre locations para skip de email.
- **No hay imágenes derivadas.** Eran `captal-shared-dev:<tag>` y `captal-locations-dev:<tag>`, construidas por location con el contenido horneado dentro. Desaparecieron con el provisioning por archivos: hoy hay una sola imagen base y el contenido viaja por la base. Los repos ECR siguen existiendo sin que nada los escriba.
- **Migraciones Flyway son inmutables** post-merge. Bug en `V<n>__*.sql` ya aplicada → nuevo `V<n+1>__fix_*.sql`, nunca editar la vieja. Y **Flyway no migra concurrente sobre SQLite** — lo dice en el log y sigue igual; un rebuild de tabla corrido dos veces a la vez borra la tabla. Por eso `mcp.test` migra una vez antes de forkear.
- **`captal_session_<slug>`/`captal_user`/`X-Click-Id`** — `X-Click-Id` requerido en CREATE de sesión nueva (sin él → `SessionMissing`). En requests subsecuentes con cookie ya seteada, se ignora.
- **Todo es soft-delete, `localized_texts` incluido.** V20 le agregó `is_active` junto a `questions`, `question_options` y `question_rules`, precisamente porque un backoffice necesita retirar una pregunta sin desactivar la encuesta entera. Antes esta línea decía que se borraba en hard-delete; ya no. **Y la otra mitad es el filtro en la lectura**: una columna que ninguna query consulta es un borrado que no borra, y el síntoma es que la tool reporta éxito y el portal sigue mostrando la pregunta.

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

> **Estado**: integración implementada end-to-end. El handler post-commit `UnifiAccessHandler` (`platform/eventhandlers/`) llama al UCG cuando se emite `UserEvent.UserFinishedProcess`, y on success setea `session.phase = Authorized` + `accessExpiresAt`. Si UniFi falla, el session queda en `Phase.Ready` y la próxima llamada a `/api/finish` reintenta seamless.

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
[Post-commit: UnifiAccessHandler llama UniFi authorize-guest vía HTTPS]
    ↓
   ┌── UniFi 2xx ──────────────────────────────────────────────────┐
   ↓                                                                ↓
[setAuthorized(sessionId, expiresAt)]                  [UniFi 5xx / timeout / no config]
[Response: phase=Authorized + accessExpiresAt]         [Response: phase=Ready + None]
   ↓                                                                ↓
[Cliente Router.syncWithPhase(Authorized)]             [Queda en /ready; reload re-monta → retry]
[WelcomeView countdown desde secondsRemaining + 1 setTimeout]
[UnifiAccessHandler encola ScheduledAt(expiresAt, ExpireAccess(sessionId)) en JobQueue]
   ↓
[JobRunner · fiber scheduler: espera hasta el deadline → publica a la cola `due`]
   ↓
[JobRunner · fiber worker: ctx.locally(session) → Flow[ExpireAccessCommand]]
   ↓
[ExpireAccessHandler → findAuthorizedUser → user.expire → emite AccessExpired]
   ├─ SessionExpirationHandler (transaccional) → resetForExpiration → phase=Welcome
   ├─ EventLogHandler → fila "user.access_expired"
   └─ UnifiAccessHandler (post-commit) → UNAUTHORIZE_GUEST_ACCESS → el UCG desconecta
   ↓
[El corte ocurre aunque el usuario haya cerrado la pestaña — no depende del cliente]
[/api/status conserva su rama isExpired como red de seguridad (scheduler caído, task recién
 reiniciada antes de rehidratar, o location sin config de UniFi)]
```

### Modelo Phase / State

Dos pares con semánticas distintas pero acopladas:

- **Session `Phase` (server state)**:
  - `Ready` = "user terminó el survey flow, esperando UniFi". Pocos ms o más si UniFi falla.
  - `Authorized` = "UniFi confirmó el voucher; `accessExpiresAt` poblado". Transición la hace **sólo** `UnifiAccessHandler` post-commit. Si UniFi no está configurado o falla, la sesión nunca llega a `Authorized` — diseño intencional para soportar retry.

- **User aggregate `State` (in-memory type-state)**:
  - `State.Ready(redirectUrl, watchedVideoId, answeredQuestionIds)` — proyectado desde DB cuando `session.phase == Ready`. Único estado que `UserRepository.findReadyUser` retorna.
  - `State.Authorized(...)` — proyectado desde DB cuando `session.phase == Authorized`, vía `UserRepository.findAuthorizedUser`. Es a la vez el retorno tipado del `finish` op (`User[State.Ready] → User[State.Authorized] + emite UserFinishedProcess`) y la entrada del `expire` op (`User[State.Authorized] → emite AccessExpired`), que ejecuta el `JobRunner` al vencer el acceso.

> **Cambió respecto de versiones previas de este doc**: `State.Authorized` ya no es ephemeral y `findAuthorizedUser` sí existe. Dejó de serlo cuando se agregó un evento de ciclo de vida *después* de `Authorized` — el vencimiento —, que necesita proyectar ese estado desde la tabla.

`expire` **no devuelve estado destino**. El type-state de `User` modela dónde estás dentro de un flujo activo, y el vencimiento lo termina en vez de moverte a otro paso. En particular **no** se vuelve a `State.Guest`: la fila en `users` persiste con su email y respuestas, y la cookie `captal_user` sigue apuntando al usuario, así que en la próxima visita `ProvideNextIdentificationSurveyHandler` lo encuentra vía `findWithEmail` y saltea lo ya respondido. `State.Guest` es de escritura únicamente — sólo lo produce `createGuest`, siempre con un `Id.generate` fresco.

**Patrón retry-by-design**: como session.phase queda en `Ready` hasta que UniFi succeed, `findReadyUser` sigue retornando el user, y un nuevo POST `/api/finish` re-ejecuta `finish` → re-emite el evento → handler reintenta. El `ReadyView` del cliente lo aprovecha: re-mount = retry automático (idempotente porque UniFi `authorize-guest` es idempotente por MAC).

### Configuración

**Per-location** (en `location.yaml` → columns `unifi_*` en `locations`):
```yaml
unifi:
  host: "192.168.1.1"           # IP/hostname del UCG en la LAN
  apiToken: "YOUR_API_KEY"      # Settings → Control Plane → Integrations
  apMac: "AA:BB:CC:DD:EE:FF"    # MAC del AP — usado por el dispatcher Lambda
                                # para resolver el slug desde la GA static IP
  port: 443                     # Opcional (default 443 — Integration v1 vive bajo HTTPS estándar)
  siteId: "00000000-0000-..."   # UUID del site (NO el nombre "default"). Discover:
                                # curl https://<ucg>/proxy/network/integration/v1/sites \
                                #   -H "X-API-KEY:<token>" -k
  defaultDurationMinutes: 1440  # Opcional (default 24h)
  redirectUrl: ""               # Opcional. Override del destino del dispatcher Lambda.
                                # Si está set, el Lambda 302 a esa URL en vez de
                                # <cloudfront>/<slug>/, preservando los params del UCG.
                                # Use case: location con portal SPA externo (no captal).
```

**Infra-shared** (en `shared/captal.yaml` → env-var `UNIFI_PROXY_URL` en la task ECS):
```yaml
unifi:
  proxyUrl: "http://tailscale-proxy.captal.local:1055"
  # Vacío → conexión directa (sólo local dev o API en misma LAN del UCG).
```

El proxy es necesario en deploy porque ECS Fargate no tiene visibilidad directa a la LAN del cliente. La cadena en producción es **API task → Tailscale proxy (Fargate userspace) → tailnet → Tailscale subnet router (VM on-prem) → LAN cliente → UCG**. `trustAllClientLayer` en `UnifiAccessHandler` configura zio-http con `ClientSSLConfig.Default` (trust-all para self-signed) + `Proxy(url)` cuando hay `proxyUrl`.

### UniFi Network Integration v1 API — endpoints relevantes

Desde captal v2.0.0 usamos la **Integration v1 API** del UniFi Network Application (no la legacy `/api/s/<site>/cmd/stamgr`). Base URL para deploys on-prem (UCG, UDM):

```
https://<ucg-host>:<port>/proxy/network/integration/v1
```

Port típicamente `443` (UCG Ultra y UniFi OS). Auth: header `X-API-KEY` con un token generado en **Settings → Control Plane → Integrations → Create API Key**.

> **Importante**: `api.ui.com` es para Site Manager (cloud); on-prem va siempre por `/proxy/network/integration/v1/` contra el host del UCG.

#### Autenticación

```http
GET https://<ucg-host>:443/proxy/network/integration/v1/sites
X-API-KEY: <token>
```

Response: `{"data": [{"id": "<uuid>", "internalReference": "default", ...}]}` — anotar el `id` del site de interés (es un UUID, **no** el nombre `"default"`). Ese UUID es lo que va en `location.yaml: unifi.siteId`.

#### Authorize a guest (flow de dos pasos)

1. **Lookup del clientId por MAC**:
   ```http
   GET https://<ucg>/proxy/network/integration/v1/sites/<siteId>/clients?filter=macAddress.eq('aa:bb:cc:dd:ee:ff')
   X-API-KEY: <token>
   ```
   Response: `{"data": [{"id": "<clientId>", "macAddress": "...", ...}]}` — extraer `id`.

2. **POST de la action de autorización**:
   ```http
   POST https://<ucg>/proxy/network/integration/v1/sites/<siteId>/clients/<clientId>/actions
   X-API-KEY: <token>
   Content-Type: application/json

   {
     "action": "AUTHORIZE_GUEST_ACCESS",
     "timeLimitMinutes": 1440,
     "dataUsageLimitMBytes": 500,        // optional
     "rxRateLimitKbps": 100,             // optional
     "txRateLimitKbps": 100              // optional
   }
   ```
   Response 2xx → device autorizado por `timeLimitMinutes` minutos.

#### Revoke guest access

**No existe una acción `DISCONNECT` / `KICK` / `FORCE_RECONNECT` para clients.** Verificado contra el OpenAPI 3.1.0 que sirve el propio UCG en `https://<ucg>/proxy/network/api-docs/integration.json` (UniFi Network 10.6.97): el schema `Client action request` está discriminado por `action` y su mapping admite **exactamente dos** valores — `AUTHORIZE_GUEST_ACCESS` y `UNAUTHORIZE_GUEST_ACCESS`.

No hace falta un disconnect aparte porque ya está incluido; el schema lo describe como *"Unauthorizes network access **and disconnects** a guest client"*. Mismo lookup previo de `clientId` por MAC, mismo path, mismos headers — sólo cambia el body, que es únicamente el discriminador:

```http
POST https://<ucg>:443/proxy/network/integration/v1/sites/<siteId>/clients/<clientId>/actions
X-API-KEY: <token>
Content-Type: application/json

{"action": "UNAUTHORIZE_GUEST_ACCESS"}
```

Response 200 con `revokedAuthorization` (required): `authorizationMethod`, `authorizedAt`, `expiresAt`, `usage`, rate limits.

Dato colateral: el authorize *"cancels existing active authorization (if exists), creates a new one with new limits and resets guest traffic counters"* — o sea que el retry-by-design de `/api/finish` es idempotente por diseño de la API, no por casualidad.

`UnifiClient` (`platform/UnifiClient.scala`) implementa ambos flows; `UnifiAccessHandler` (`platform/eventhandlers/`) es sólo el adaptador de eventos.

### Caveats conocidos

- **siteId es UUID**, no nombre**: la doc usa `{siteId}` ambiguamente. En la práctica el endpoint sólo acepta el UUID retornado por `GET /sites`. Si pegás `"default"` recibís 404.
- **TLS self-signed**: los UCG usan cert auto-firmado. El zio-http Client en `trustAllClientLayer` usa `ClientSSLConfig.Default` (trust-all). En prod la conexión va por el túnel Tailscale, así que la seguridad de transporte la provee el overlay.
- **Lookup fallido (404)**: el device tiene que estar conectado al guest network (pre-auth state) para aparecer en `GET /clients`. Si todavía no se asoció o el ESSID no es el correcto, el filter devuelve `data: []`. El handler loggea ERROR y deja la session en `Phase.Ready` para reintento.
- **MAC normalization**: UniFi normaliza a lowercase con `:`. El handler hace `clientMac.toLowerCase.replace("-", ":")` antes de armar el filter.
- **API key scope**: el token se asocia al admin que lo creó. Si el admin se da de baja, el token revoca. Rotar manualmente.
- **Legacy `/api/s/<site>/cmd/stamgr`**: deprecado, no usar. Funcionaba pero requería login session + CSRF token + manejo de cookies.

### Referencias

- Ubiquiti docs (External Hotspot API): https://help.ui.com/hc/en-us/articles/31228198640023
- Ubiquiti docs (Network Integration API): https://help.ui.com/hc/en-us/articles/30076656117655

---

## Common task → file map

| Tarea | Archivos primarios |
|---|---|
| Agregar API endpoint | `endpoints/.../<Name>Endpoints.scala` + `api/.../<Name>Routes.scala` + `api/test/.../suites/<Name>Suite.scala`. Skill: `add-api-endpoint`. |
| Agregar Phase | `core/.../application/phase.scala` + `platform/.../eventhandlers/SessionPhaseHandler.scala` + `client/.../Router.scala` (`Page` enum + `phaseToPage`) + nueva view. Skill: `add-phase`. |
| Migración DB | `platform/resources/db/migration/V<n>__*.sql` + `platform/.../rows.scala` (campo) + `EntityWriter` (insert + upsert) + `ProvisionService` (pasar el value). Skill: `add-migration`. |
| Nuevo evento + handler | `core/.../<aggregate>/Event.scala` + `platform/.../eventhandlers/<Name>Handler.scala` + `.andThen` en `Main.eventHandlerLayer` Y `TestLayers.eventHandlerLayer`. Skill: `add-event-handler`. |
| Trabajo programado con deadline | `platform/.../session/JobScheduler.scala`: agregar un `case` a `ScheduledJob` y su rama en `JobRunner.execute`. El payload debe ser **datos, no un efecto** — la rehidratación de arranque reconstruye las tareas leyendo la DB, y un closure no se puede reconstruir desde una fila. |
| Nueva entidad provisionable | `provision/.../models.scala` (YAML) + `platform/.../rows.scala` + migration + `EntityWriter` + `ProvisionService.{provisionX, scanDisk, provisionEntity, softDeleteEntity}` |
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
| Frontend muestra `[welcome.title]` literal | Faltan filas de i18n, o la cookie apunta a otra location. `get_i18n_status` dice qué claves faltan por locale; `set_translations` las escribe. Un `[key]` entre corchetes con la clave **sí** existente es otra cosa: la fila está retirada (`remove_translation` es soft-delete). |
| Video da 403 al cargarse | `video_url` apunta al bucket directo en vez de a CloudFront. `AssetStore.publicUrl` lo deriva de `PUBLIC_BASE_URL`, así que el 403 suele ser esa env var apuntando al lugar equivocado — ver `AssetUrlSpec`. |
| Una pregunta muestra texto equivocado o viejo | `update_question` la corrige en su lugar y conserva las respuestas. Retirar y crear de nuevo también funciona pero parte el historial: `answers.question_id` sigue apuntando a la retirada. |
| API task no arranca, exit code 1 | Logs `/ecs/captal-<slug>`: `LOCATION_SLUG` missing o con un slug que no existe en la tabla `locations` — `currentLocationLayer` falla con `LocationNotFound` y el arranque muere. (`PROVISION_DIR` ya no se lee.) |
| Una location nueva no responde nada | No hay ECS service, target group ni regla de ALB para ese slug: `create_location` sólo escribe la fila. Los creaba `captal locations push`, que se borró, y no tienen reemplazo. Ver la sección CLI. |
| rqlite queries devuelven 500 `no result set returned` después de redeploy | En prod, Fargate efímero perdió data y **no hay replay desde archivos**: el contenido tiene una sola copia. En dev hay EFS montado en `/rqlite/file`. Skill: `recover-data`. Ojo que el mismo mensaje aparece cuando la tabla no existe, no sólo cuando la fila falta. |
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
- **Integración UniFi (authorize-guest)**: `UnifiAccessHandler` post-commit en `platform/eventhandlers/` llama al UCG con trust-all SSL + proxy opcional, idempotente, con retry-by-design (session.phase queda en Ready si UniFi falla → próximo `/api/finish` reintenta)
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
├── platform/                        # Implementaciones Quill + Rqlite
│   └── src/whitelabel/captal/platform/
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

// platform/SessionContext.scala
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

// platform/SessionService.scala
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

// platform/DbEventHandler.scala
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
// platform/schema.scala
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
// platform/rows.scala
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
- Implementacion Quill en `platform/`

```scala
// core/infrastructure/SurveyRepository.scala
trait SurveyRepository[F[_]]:
  def findAssignedEmailSurvey(): F[Option[Survey[State.WithEmailQuestion]]]
  def findWithProfilingQuestion(surveyId: survey.Id, questionId: survey.question.Id): F[Option[Survey[State.WithProfilingQuestion]]]
  def findWithLocationQuestion(surveyId: survey.Id, questionId: survey.question.Id): F[Option[Survey[State.WithLocationQuestion]]]
  def findNextIdentificationSurvey(): F[Option[NextIdentificationSurvey]]

// platform/SurveyRepositoryQuill.scala
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

`Ready → Authorized` solo lo transiciona `UnifiAccessHandler` post-commit (no `SessionPhaseHandler`). Si UniFi falla / no está configurado, la sesión queda en `Ready` permitiendo retry vía nueva llamada a `/api/finish`.

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
  case AccessExpired(userId, occurredAt)
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
| `SessionExpirationHandler` | Reacciona a `AccessExpired` → `resetForExpirationQuery` (un UPDATE: phase=Welcome + limpia userId/current*/accessExpiresAt) |

**Post-commit** (`EventHandler[Task, Event]`, compuesto con `.andThen` después del `TransactionalEventHandler`; fallos no rollbackean DB):

| Handler | Funcion |
|---------|---------|
| `UnifiAccessHandler` | Simétrico en los dos extremos del ciclo. `UserFinishedProcess` → `AUTHORIZE_GUEST_ACCESS`; on 2xx llama `sessionService.setAuthorized` (Ready→Authorized) **y encola el vencimiento en `JobQueue`**. `AccessExpired` → `UNAUTHORIZE_GUEST_ACCESS` (que además desconecta); no escribe la sesión, eso ya lo hizo `SessionExpirationHandler`. HTTP failures en ambas ramas: log + skip. |

---

## Command Handlers Disponibles

| Handler | Comando | Resultado | Dependencias |
|---------|---------|-----------|--------------|
| `AnswerEmailHandler` | `AnswerEmailCommand` | `NextStep` | `SurveyRepository`, `nextStep` |
| `AnswerProfilingHandler` | `AnswerProfilingCommand` | `NextStep` | `SurveyRepository`, `UserRepository`, `nextStep` |
| `AnswerLocationHandler` | `AnswerLocationCommand` | `NextStep` | `SurveyRepository`, `UserRepository`, `nextStep` |
| `ProvideNextIdentificationSurveyHandler` | `ProvideNextIdentificationSurveyCommand` | `NextIdentificationSurvey \| NextStep` | `SurveyRepository`, `UserRepository`, `terminalPhase` |
| `ExpireAccessHandler` | `ExpireAccessCommand` | `Unit` | `UserRepository` (vía `findAuthorizedUser`). Lo ejecuta el `JobRunner`, no un endpoint. Ante `None` es no-op silencioso — la tarea quedó obsoleta porque el usuario se re-autorizó. |

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

`/api/finish` emite `UserEvent.UserFinishedProcess` y deja que `UnifiAccessHandler` post-commit llame al UCG. Tras el commit, se re-lee `sessionService.findById` para devolver el snapshot actualizado en la response (evita un round-trip extra a `/api/status`).

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

## Provisioning System — eliminado

Hubo un motor que sincronizaba YAML con la base al arranque del servidor, con diff de tres vías
contra una tabla `provision_manifest`. Se borró entero (commit `240efd6`), junto con la CLI que
escribía esos archivos, y `provision_manifest` se dropeó en V23.

La razón no fue limpieza: corría en **cada** arranque de contenedor —deploy, scale-out, OOM,
reemplazo de task— y decidía qué hacer con el contenido escrito desde el último. Un YAML sin tocar
sobrevivía una mutación; uno editado la pisaba; y un manifest perdido trataba todo como nuevo,
re-upserteando la location desde disco y poniendo `is_active = 1` sobre cada fila retirada a
propósito. Con el backoffice escribiendo a la misma base, dos escritores sin árbitro no es algo que
se deje andando.

**Hoy el API sólo lee.** El contenido se escribe por las tools del servidor MCP.

---

## CLI (`captal`) — eliminada

<details>
<summary>Lo que hacía <code>captal locations push &lt;slug&gt;</code>, conservado porque todavía no
tiene reemplazo</summary>

Estos diez pasos levantaban el portal de un slug. Los pasos 5 a 9 **no existen hoy en ningún lado**:
el repo de platform crea el cluster, los log groups y un único target group compartido, y
`aws_ecs_service` sólo aparece en `modules/rqlite` y `modules/tailscale-proxy`. `create_location`
escribe la fila en la base y nada en AWS la sirve. Es la especificación pendiente de
`deploy_location`.

### Comandos (históricos)

```bash
captal init [--claude]                          # Crea shared/, locations/, .agents/skills/
                                                # + .captal/state.json (CLI version actual)
                                                # --claude tambien crea symlink .claude/skills
captal shared push                              # Deploy de recursos shared via ECS task
captal locations add <slug>                     # Crea locations/<slug>/ desde template
captal locations push <slug>                    # Deploy de location (S3 + ECS + ALB rule)
captal locations push all                       # Itera push sobre cada locations/<slug>/
                                                # ("all" es slug sentinel — no es subcomando)
captal locations deprovision <slug> [-y|--yes]  # Tear-down de la platform AWS de la location
                                                # (ALB rule, ECS service, task defs, target group,
                                                # log group, ECR image tags, S3 assets, CF cache).
                                                # locations/<slug>/ local NO se toca.
captal migrate [--dry-run] [-y|--yes]           # Aplica schema migrations a los YAMLs del
                                                # proyecto (DSL declarativo, idempotente).
                                                # Ver "Sistema de migrations de schema" abajo.
captal video add <slug> <advertiser> <file>     # Sube video a S3 + crea video.yaml
captal video add-promo <slug> <file>            # Sube promo a S3 + crea promo.yaml
captal update [--url <url>]                     # Self-update del jar desde S3 releases
```

### Sistema de migrations de schema (desde CLI v2.1.0)

Cada breaking change del modelo de provision se registra como una entry en `cli/src/.../migrations/Migration.scala::Migrations.all` con primitivas declarativas:

- **`Add(path, value)`**: setea field si falta (no clobbers overrides)
- **`Delete(path)`**: borra field si existe
- **`Rename(from, to)`**: mueve value entre paths (no transforma)

Las paths usan **`YamlPath`** (opaque type con validación compile-time via `compiletime.ops.string.Matches`): `YamlPath("unifi.siteId")` compila, `YamlPath("a..b")` falla compilación con `error("Invalid YamlPath...")`. Para inputs dinámicos: `YamlPath.parse("...")` retorna Either.

Tras cada invocación de cualquier `captal`, un hook en `Main.scala::run` compara la CLI version corriente con la `lastSeenCliVersion` del `.captal/state.json`. Migrations cuya `version` esté en `(lastSeen, current]` se reportan como pending (sólo cuentan `Delete` y `Rename` — `Add` es silencioso porque típicamente es opcional). El warning sugiere correr `captal migrate`.

**Para agregar una migration nueva**: editar `Migrations.all` y agregar al final una `Migration` con la `version: SemVer` del próximo release que rompe el schema. Skill: `bump-cli-version` (ver sección abajo).

### Configuracion

`shared/captal.yaml` define AWS region, ECR images (4 repos), bucket S3 + bundlePrefix, cluster ECS, subnets/SGs, ALB listener, CloudFront distributionId, database URL y opcionalmente `unifi.proxyUrl` (HTTP proxy para que el handler UniFi llegue al UCG on-prem en deploys cloud — vacío = conexión directa). La skill `configure-aws` guia su llenado con comandos `aws` CLI. Si no se incluyen credenciales explicitas, el SDK usa la cadena default.

### Flujo de despliegue (que hace cada comando bajo el capot)

**`captal shared push`** (task efimera):
1. Build imagen `captal-shared:<ts>` FROM `images.provision` + COPY `shared/` → `/etc/captal/shared/`
2. Push a ECR
3. Register task definition `captal-shared-provision` con esa imagen
4. RunTask Fargate (one-shot)
5. Container ejecuta `java -cp platform.jar SharedProvision` que aplica YAMLs de surveys + advertisers a la DB
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

Los pasos 1 y 2 los cubre hoy `./mill client.publishS3` más las tools de assets
(`set_location_styles`, `set_location_icon`, que sí invalidan). El 3 y el 4 desaparecen: no hay
imagen derivada por location, el contenido ya no viaja en el artefacto. **Del 5 al 10 no hay nada.**

</details>

### Caveats operativos descubiertos en deploy

- **rqlite + storage efimero Fargate**: cada redeploy de un nodo rqlite pierde su data local; el cluster recupera via Raft solo si manten quorum. Si 2/3 nodos reinician simultaneamente, datos pueden perderse. Mitigacion temporal: re-correr `shared push` + `locations push` para reinsertar. Solucion permanente pendiente: EFS mount para `/rqlite/file`.
- **JDBC connection stale tras rqlite redeploy**: el driver rqlite-jdbc no hace fail-over; si el nodo al que apunta desaparece, las queries fallan con `SQLException: No result set returned`. Mitigacion: `aws ecs update-service --force-new-deployment` para conexiones frescas.
- **Mill BuildInfo cache**: `client.generatedSources` no se invalida por cambio en env var `ENVIRONMENT`. Hay que `./mill clean client.generatedSources` antes de un bundle con env distinto. Sin esto, los flags de dev (`isDevMode`, boton reset) quedan stale en el bundle.
- **Bundle metadata**: subir `.gz` files a S3 requiere `--content-encoding gzip` y `--content-type` correcto. `aws s3 sync` por si solo no lo setea; usar `aws s3 cp` por archivo (ver "Release flow" arriba).

---

## Skills (Cross-agent)

Hay dos conjuntos y viven en lugares distintos:

**Operacionales** — `mcp/resources/skills/<name>/SKILL.md`. Las sirve el servidor MCP al modelo con
la tool `read_skill`, y `mcp.resources` las pone en el classpath en `/skills/<slug>/SKILL.md` más un
`index.txt` generado. No se distribuyen a ningún workspace: el operador no tiene archivos.

**De desarrollo** — `.agents/skills/<name>/SKILL.md`, reconocidas por Claude Code y OpenCode. Sólo
viven en este repo.

Las doce operacionales, todas reescritas alrededor de tools:

| Skill | Proposito |
|-------|-----------|
| `add-location` | Onboarding de una location, con `get_location_readiness` como ciclo |
| `add-advertiser` | `create_advertiser` / `update_advertiser` / `set_advertiser_active` |
| `add-video` | `create_advertiser_video` y la mitad de subida: página, PUT firmado, `confirm_video_upload` |
| `add-promo` | `create_promo_video`. Un promo **puede** tener encuesta; lo que cambia es la selección |
| `add-survey` | `create_survey` y los pares de questions, separados por scope |
| `edit-i18n` | `get_i18n_status` → `set_translations` → `remove_translation` |
| `i18n-reference` | Las claves que el portal exige, derivadas del tipo `I18n` por `get_i18n_schema` |
| `style-reference` | Variables CSS y estructura de clases. La referencia viva es `get_css_schema` |
| `configure-aws` | El repo de platform, las env vars que el server lee, y los tokens de operador |
| `deploy-location` | Las tres cosas que "deploy" puede significar. La tercera es `deploy_location` |
| `troubleshoot-deployment` | Síntoma → causa → arreglo |
| `recover-data` | Recuperación tras pérdida de datos en rqlite. **Ya no hay replay desde archivos** |

`yaml-reference` y `deploy-shared` se borraron: documentaban el árbol de YAML y `captal shared push`.
Una skill que describe algo borrado es peor que una ausente, porque el modelo la obedece —
`CaptalMcpSpec` pinea el conteo en 12 para que agregar o quitar una sea una decisión y no un
descuido.

**Cambiar una tool incluye su skill.** No hay test que lo cubra y no debería haberlo: un spec puede
verificar que un string esté presente, nunca que el párrafo alrededor sea cierto, así que una prueba
de contenido da apariencia de cobertura sobre lo único que importa. Lo que sí está cubierto es
mecanismo — empaquetado, parseo del frontmatter, las URIs, y que ninguna quede afuera de
`skills/list` por el contrato del SDK.

El caso que lo demuestra: `deploy-location` abrió con **"There is no `deploy_location` tool"** desde
que se borró la CLI hasta que la tool existió, y sobrevivió a una reescritura completa de las doce
skills. Ningún invariante mecánico lo habría encontrado — se encontró leyendo el archivo para
agregarle otra cosa. Las tools que una skill nombra son su parte más perecedera: si tocás una tool,
abrí su skill en el mismo cambio.

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

`build.mill` arranca un contenedor Docker de `rqlite/rqlite:10.6.1` antes de los tests (`ensureRqlite`). El driver JDBC apunta a `jdbc:rqlite:http://localhost:<port>`.

La versión es la del driver a propósito: `rqlite-jdbc` 10.5.2.1 contra servidor 10.6.x. `ensureRqlite` compara la imagen del contenedor corriendo contra la pedida y lo recrea si difieren, porque antes decía 8.36.2 acá, 9.1.2 en el build y 10.6.1 en producción — tres combinaciones y ninguna la que el driver apunta.

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

**Y durante mucho tiempo no fue cierto.** `RqliteDataSource` envolvía cada conexión en un proxy que interceptaba `setAutoCommit`, `commit` y `rollback` y devolvía `null` en los tres, porque el driver JDBC de rqlite 9.1.2.1 descartaba escrituras en silencio con `autoCommit = false`. Quill's `.transaction` hace exactamente esas tres llamadas, así que **la única transacción del codebase no existía**: un lote de eventos que fallaba a mitad dejaba una respuesta persistida sin la fase que le sigue, y `TransactionalEventHandler` logueaba *"transaction rolled back"* sobre un rollback que nunca ocurrió.

Arreglado con el bump a `rqlite-jdbc` 10.5.2.1 quitando el proxy, y verificado en vez de supuesto: contra un servidor 10.6.1, `setAutoCommit(false)` se sostiene y una fila insertada y revertida no está después.

La lección es la de la entrada 9 otra vez: **una garantía que nadie ejercita no es una garantía.** Esta estaba escrita acá, implementada en el código, y desactivada dos capas más abajo por un workaround contra un bug de una dependencia — sin una sola línea que las conectara.

**Había un segundo proxy, y se fue con el mismo bump.** `Migrate.wrapForMigrations` eran sesenta líneas de reflection que forzaban el `Statement.execute()` de Flyway a pasar por `executeUpdate()` para cualquier cosa que pareciera una escritura — porque el driver 9.1.2.1 ruteaba un `INSERT` cuyo literal de string contenía la palabra "select" al endpoint read-only `/db/query`, donde no hacía nada mientras Flyway la registraba como aplicada.

Quitarlo no fue limpieza: **se había vuelto activamente nocivo.** El handler del proxy nunca desenvolvía el `InvocationTargetException` que devuelve `Method.invoke`, lo cual no importaba mientras el driver se tragaba los errores — y en el momento en que el driver nuevo empezó a lanzar, cada `SQLException` real llegaba a Flyway como un `UndeclaredThrowableException` sin causa adjunta. La suite end-to-end falló exactamente así, y el mensaje no nombraba nada.

Verificado antes de borrarlo: en 10.5.2.1 un `INSERT` que carga "select" en un literal, emitido por `execute()`, aterriza.

### 3. MappedEncoding para Tipos de Dominio

Los Row types usan tipos de dominio directamente (`user.Id`, `survey.Id`, `Phase`, etc.) gracias a `MappedEncoding`. Esto elimina conversiones manuales `.asString` / `.fromString` en las queries.

### 4. UserPersistenceHandler con Deteccion de Usuario Existente

Cuando un usuario responde con un email que ya existe, el handler vincula la sesion al usuario existente en lugar de crear un duplicado.

### 5. Typed States

Las entidades (`Survey`, `User`) estan tipadas por su estado, lo que hace imposible invocar operaciones invalidas en tiempo de compilacion.

### 6. Un solo linker run para todo el JS

`client` emite cuatro módulos públicos: el portal, las dos MCP Apps y la página de pausa. Antes eran tres módulos Mill con tres linker runs.

**El costo es conocido y está medido**, porque la alternativa se midió primero. Un linker run produce un runtime dimensionado por el programa entero, y todo módulo público lo importa estáticamente — no es diferible, el entry arranca instanciando algo que deriva de `Object`. En runs separados el runtime de las MCP Apps pesaba 16.810 gzip; dentro de `client` pesa 91.045, de los que Rollup y terser ya sacudieron todo lo que se puede sacudir. La primera descarga de `upload` pasó de 10.839 a 94.899 gzip.

Traducido a lo que se siente: con los hints puestos en las dos estructuras, la fusión cuesta **+70 ms a 10 Mbps y +408 ms a 1,6**. El segundo número es el peso puro (84 KB a 1,6 Mbps son 420 ms) y es un piso, porque 10,8 KB entran en la primera ventana de congestión y 94,9 KB necesitan tres.

Lo que se gana a cambio es un codebase: `maintenance` usa el `Layout` del portal sin copiarlo, y hay una sola configuración de Vite en vez de tres.

**Si esto se revisa**, el eje a medir es ese, no los chunks: el reparto entre módulos no cambia nada porque el módulo que todos comparten es una sola clase, `java.lang.Object`, y ninguna granularidad de paquete entra dentro de una clase.

### 7. Los `modulepreload` se derivan del artefacto, nunca de una lista

Los nombres de chunk llevan hash de contenido, así que una lista escrita a mano o compilada en el servidor queda vieja en el siguiente publish — y **un preload con un hash que ya nadie importa es peor que ninguno**, porque el browser se baja un chunk que no va a usar.

De ahí dos implementaciones y no una, según quién renderiza el HTML:

- El portal y la página de pausa los renderiza el build, así que `preloadLinks` lee los imports reales del entry, transitivamente.
- Las MCP Apps las renderiza el servidor por request, así que `AssetStore.scriptPreloads` lee el entry publicado de S3 y parsea sus imports. Si no puede leerlo responde vacío: la página sin hints es correcta, solo más lenta.

Ambas excluyen `import()` dinámico — sin eso el portal precargaría todas sus vistas en el primer pintado, que es lo contrario de por qué existe el split de rutas.

Medido: quita un round trip del camino crítico, la app monta 143 ms antes a 100 ms de RTT.

### 8. La CDN siempre existe

No hay flag para correr sin CloudFront. `AssetStore` invalida siempre, en todos los entornos, y la distribución es un requisito de arranque como el bucket.

Hubo un `cdnEnabled`, derivado de si `AWS_ENDPOINT_URL` estaba seteado, que convertía toda invalidación local en el string `"skipped-no-cdn"`. Nació de una capacidad ausente —LocalStack Community no tenía CloudFront— y se quedó como si fuera un caso de uso. No lo es: **no hay ningún escenario nuestro sin CDN.** Prepararse para uno costaba tres piezas móviles (un id de distribución inventado en dos entornos, un flag que lo neutralizaba, y dos tests que fijaban el skip en ambas direcciones) y, entre todas, garantizaban que el camino de CloudFront no se ejecutara nunca hasta llegar a AWS.

Verificado contra LocalStack Pro antes de borrarlo — crea distribuciones, sirve por ellas, las invalidaciones llegan a `Completed` en el primer poll, y **ejecuta las CloudFront Functions en viewer-request**. O sea el borde es observable en local, que es lo que vuelve testeables tanto la invalidación como las reglas del edge.

Lo que queda en su lugar: `ensureDistribution` crea una distribución por entorno local (una para los tests, otra para `mcp.dev`) y exporta su id real. Y `cdnClientFor(endpoint)` es explícito como los otros cuatro clientes, aunque el SDK lea `AWS_ENDPOINT_URL` por su cuenta desde 2.21 — cuatro explícitos y uno implícito se lee como un olvido.

**La lección general, que es por qué esta entrada es larga:** un flag justificado por una capacidad ausente hay que volver a mirarlo cuando la capacidad aparece. Este sobrevivió al bump de Community a Pro sin que nadie lo notara, y de paso se llevó la cobertura de todo lo que protegía.

### 9. Medido y descartado

Para que no se reintente. Cada uno vino con una hipótesis razonable y un número que la cerró.

| Idea | Resultado |
|---|---|
| `mangle.properties` en terser | **0,71%** (89 bytes sobre 12.464). Scala.js ya minifica los nombres de miembro a 2–3 caracteres y entrecomilla todo lo que cruza a JS; `keep_quoted` respeta esa marca. No queda nada que acortar, y el riesgo se concentra en los nombres que no se pueden tocar. |
| `ModuleSplitStyle.SmallestModules` | 755 módulos, cero `internal-*`, y al portal le cuesta menos del 1%. No arregla nada porque `java.lang.-Object` sigue siendo un módulo de 159.172 gzip. |
| Tree-shake post-build del runtime | Ya lo hace Rollup, y llega a 91 KB desde 159. Lo que falta para llegar a 17 no es código borrable: el runtime de un programa chico tiene 232 funciones y el del portal 1.393, y **no es un subconjunto**. Un pase post-build solo puede borrar, nunca regenerar. |
| Primer pintado por debajo de ~2 Mbps | Lo gobierna `brand-icon.svg` — 730 bytes con round trip propio, ya eran 756 ms antes de los preloads. El arreglo está escrito en `build.mill` (icono y reglas del loader inline, `styles.css` fuera del render-blocking) y no se hizo porque las conexiones que sirve este portal no son de 1,6 Mbps. |

Sigue sin explorar un lever real: `scala-java-time` aporta 222 KB crudos y `client` no nombra `java.time` en ninguna línea — entra por los 25 archivos de `core` que timestampean con `Instant`.

### 10. El lock de migraciones vive en rqlite, no en S3

`Migrate.Lock` es un mutex hand-rolled: un `INSERT` de una primary key fija en `migration_lock`, y el ganador se decide leyendo de vuelta quién quedó. Dos preguntas razonables lo cuestionan, y vale tenerlas contestadas.

**"Flyway envuelve cada migración en una transacción — ¿el lock no es redundante?"** No, y la medición importa porque su primera mitad parece decir que sí.

Las transacciones son reales desde que se quitó el proxy de `autoCommit` (entrada 2), y hacen trabajo: con `group = false` cada migración commitea sola, así que el perdedor se rechaza completo, DDL y fila del historial juntos. Seis migradores contra una base vacía, diez intentos: **18 de 60 murieron en el commit** con `SQL State 25000 — Transaction commit failed: table users already exists`, y **cero versiones duplicadas** sobrevivieron en ningún intento.

Pero eso no es exclusión, y ahí está la trampa: lo que falló esos commits fue el SQL de la migración chocando consigo mismo — `CREATE TABLE users` contra una tabla que ya existe. Cuando el SQL no choca, nada excluye a nadie. Seis migradores con una sola migración pendiente, cuyo cuerpo es `DROP TABLE IF EXISTS`, escribieron **tres filas de historial para la misma versión** en ranks 23, 24 y 25, dentro de 53 ms, **diez intentos de diez**. `installed_rank` es la primary key y `version` no es única, así que migradores que leen el mismo máximo y lo incrementan caen en ranks distintos y nunca colisionan.

El segundo caso es el desplegado. Una base vacía ocurre una vez; un redespliegue con una migración nueva es cada release. Y lo que lo hace caro en vez de desordenado es V22: reconstruye `advertiser_videos` con `INSERT INTO advertiser_videos_new` sin `ON CONFLICT`, así que aplicarla dos veces duplica cada video que copia.

Flyway no ayuda, y esto no hacía falta medirlo: la frase `SQLite does not support locking. No concurrent migration supported.` sale de `org.flywaydb.core.internal.database.sqlite.SQLiteTable`, el `doLock()` de Flyway para SQLite, que no hace nada y loguea. En PostgreSQL tomaría un advisory lock, en MySQL `GET_LOCK`; acá el método está vacío. **Ese es el contrato, declarado por la implementación**, y contesta la pregunta sin armar un experimento. Lo que las corridas agregaron es la forma del fallo — qué commits mueren y por qué los ranks no colisionan — que no está documentada en ningún lado.

Queda fijado por `MigrationLockSuite`, que es lo que faltaba.

**"¿Hay un servicio de AWS que dé un lock real? ¿Un lockfile en S3, o en EFS?"** Sí, S3 lo da, y es un primitivo legítimo: desde agosto de 2024 `PutObject` acepta `If-None-Match: *`, que es un compare-and-swap de crear-si-no-existe. El precedente está al lado — es exactamente lo que usa el backend S3 de OpenTofu con `use_lockfile = true`, el que reemplazó a la tabla de DynamoDB. DynamoDB con `PutItem` condicional es el clásico y sirve igual.

No se eligió porque **el primitivo no es la parte débil.** El `INSERT` contra rqlite pasa por Raft, así que de N inserciones concurrentes de una primary key exactamente una tiene efecto y todos los nodos coinciden en cuál: las mismas semánticas de CAS que daría S3 o DynamoDB. Cambiarlo no compra corrección, y cuesta una dependencia nueva con su IAM.

Y sobre todo: **no arreglaría la única debilidad real**, que es el lock abandonado. Lo que S3 o DynamoDB agregan es poder ponerle un TTL, y el TTL resuelve el problema equivocado — un migrador que muere a mitad deja un esquema a medio aplicar *y* una fila fallida en el historial que Flyway se niega a pasar hasta que alguien la repare. Entregarle el lock al siguiente proceso lo deja igual de bloqueado, solo más tarde y con menos pistas.

EFS es el caso más interesante, y se descarta igual. Sus locks NFSv4 sí se liberan solos cuando el cliente muere, por expiración del lease — lo único que el diseño actual no hace. Pero EFS solo existe en la topología de `dev` (rqlite single-node); en prod el cluster Raft corre sobre almacenamiento efímero. Montarlo en la task de cada location para un lockfile es pesado, `FileChannel.tryLock` sobre NFS no es confiable en la JVM, y la liberación automática vuelve a resolver el problema equivocado.

**El costo que esto acepta, dicho sin adornos.** Un nodo que toma el lock y muere sin liberarlo bloquea a todos los demás migradores hasta que alguien borre la fila. El lock loguea cada 2 s a quién espera y falla a los 10 minutos nombrando la tabla y qué hacer, así que no es invisible en el log — pero el contenedor sale, ECS lo reinicia, vuelve a esperar 10 minutos, y nada escala. En la práctica el despliegue nunca termina y hay que mirar para enterarse.

Eso no se arregla con el almacén. Detectar que un nodo se desconectó requiere una **sesión del lado del servidor** — el advisory lock de Postgres que se libera cuando cae el TCP, un lease de etcd con su keepalive, una sesión de Consul, un znode efímero de ZooKeeper. rqlite no tiene sesiones y S3 tampoco, así que ninguno de los dos puede. Lo máximo sin sesiones es un lease con TTL, que no detecta la desconexión: expira si nadie renueva.

**Decidido: se queda así por ahora. Si muerde, entra etcd** — una instancia sola alcanza, no hace falta cluster, y es el único de la lista que da la semántica de verdad con el keepalive manejado por la librería (`jetcd`) en vez de escrito a mano. Lo que cuesta es una task siempre encendida, su módulo de TF, y `jetcd` arrastrando grpc-netty dentro de `platform/`; y hay que saber que se vuelve un SPOF que bloquea despliegues, y que tampoco cierra el fencing (un holder congelado por GC puede despertar y seguir migrando tras perder el lease, porque Flyway no acepta tokens).

La alternativa que no necesita ningún lock quedó sobre la mesa y sin hacer: sacar las migraciones del contenedor que sirve y correrlas como una task one-shot por despliegue, con la API solo verificando la versión. Un migrador por construcción, y ECS ya detecta la task muerta — es exactamente la capacidad que se le compraría a etcd. Es el patrón que `mill dev` ya usa localmente (paso 2/4); el camino desplegado es el que divergió.

Cuándo revisar lo de S3: si alguna vez hace falta tomar el lock **antes** de que la base exista, o cuando está inalcanzable. Hoy es discutible — si no se puede llegar a la base, tampoco se puede migrar.

### 11. La política IAM se evalúa en soft mode, y el log se lee

LocalStack arranca con `ENFORCE_IAM=1` y `IAM_SOFT_MODE=1`, y el suite corre como el usuario IAM que `modules/mcp` crea — el que carga la misma política que el task role desplegado.

**Lo que esto arregla.** Por defecto LocalStack no autentica nada: *"all APIs can be accessed without authentication"*. El suite pasaba `test`/`test`, así que cada llamada tenía éxito sin importar qué concediera la política — el e2e de deploy **pasaba igual con una política vacía**. La entrada 2 otra vez: una garantía que nadie ejercita no es una garantía.

**Por qué soft y no duro.** Hard mode es inalcanzable acá, y está **documentado**, no es una inferencia mía. La página de *IAM coverage* de LocalStack enumera las claves de condición que soporta — las globales, las de tags, y específicas de servicio **solo para EC2 y RAM** — y lista los servicios con cobertura de enforcement, donde **ECS no figura**. Esta política condiciona las llamadas de servicio de ECS a `ecs:cluster` y `iam:PassRole` a `iam:PassedToService`, ninguna de las cuales está en esa lista. Su propio `simulate-principal-policy` muestra la consecuencia:

| simulación | decisión |
|---|---|
| `ecs:DescribeServices` sin `ecs:cluster` | `implicitDeny` |
| `ecs:DescribeServices` con la clave | `allowed` |
| `iam:PassRole` sin `iam:PassedToService` | `implicitDeny` |
| `iam:PassRole` con la clave | `allowed` |
| `ecs:RegisterTaskDefinition` (sin condición, control) | `allowed` |

Verificado además que el lado nuestro está bien: `Deploy` pasa `.cluster(targets.cluster)` en cada llamada, `cluster_id` es el ARN completo y no el nombre, y `location_task_role_arn` es el mismo rol que el deploy pasa. Así que hard mode fallaría el e2e por el emulador, y la alternativa — quitar las condiciones — es debilitar una política de producción para complacer una herramienta local. `CAPTAL_IAM_SOFT=0` lo intenta, para cuando LocalStack las provea.

**Y soft mode sin leer el log no sirve de nada**, porque evalúa para nadie. Así que `DeployE2ESpec` termina leyendo las denegaciones del log del contenedor y falla con cualquiera que no esté en `KnownEmulationGaps`. Una acción genuinamente ausente de la política rompe el build; los dos huecos conocidos están nombrados y son lo que se borra el día que el emulador los cubra.

**El único bug real que encontró** fue de otra clase: desajuste de recurso. Las cuatro acciones de S3 se denegaban porque el suite escribía en un bucket propio y la política concede sobre `${assets_bucket_arn}/*` — `captal-local-assets` allowed, `captal-assets-mcp-test` implicitDeny. El arreglo fue que el suite use el bucket que crea la configuración, **no** ampliar la política: eso sería conceder acceso de producción a algo que solo existe en una prueba. Esa distinción es el motivo de usar el simulador antes de tocar un permiso.

Lo que falta para que esto cubra todo: el e2e ejercita las llamadas del deploy, no las de `AssetStore` ni `Assets` por separado, así que un permiso que solo haga falta en un camino que el e2e no recorre sigue sin verificarse.

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

### 5. Comentarios técnicos, documentación de decisiones

Este repo comenta mucho, y la razón es buena: casi todo lo que puede romperse acá rompe en silencio. Pero las dos cosas se habían mezclado, y un comentario que argumenta una decisión con mediciones crece hasta tapar el código que explica.

El reparto:

- **Un comentario responde "qué hace esto y qué se rompe si lo tocás".** El mecanismo, la restricción, la trampa. Es para alguien que está leyendo esa línea. `crossorigin` en un `modulepreload` es load-bearing porque el módulo se busca en modo CORS — eso es un comentario.
- **La documentación responde "por qué así y no de la otra forma".** La alternativa que se consideró, el número que la descartó, y cuándo valdría revisarlo. Es para alguien que está decidiendo. Que `mangle.properties` rinde 0,71% y por eso no está — eso es documentación, y vive en *Decisiones de Diseño*.

La prueba para separarlos: **si el párrafo sigue siendo verdad cuando alguien cambia la decisión, es un comentario; si deja de tener sentido, era documentación.**

Esto es una dirección de refactor, no un estado alcanzado. Al tocar un archivo con un comentario que argumenta en vez de explicar, el movimiento es mover el argumento a *Decisiones de Diseño* y dejar en el código la mecánica.

---

## Comandos Utiles

```bash
# Compilar todo
./mill __.compile

# Tests (arrancan container de rqlite automaticamente)
./mill __.test
./mill core.test
./mill api.test                 # Incluye E2E + ProvisioningSuite
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

# Backoffice MCP en local — rqlite + migraciones + LocalStack + servidor, en ese orden
./mill mcp.dev                       # :8081, token "dev-token"
./mill mcp.dev --port 9000 --token x
./mill mcp.stopLocalStack            # Borra el contenedor y con el todos los objetos
```

`mcp.dev` comparte el contenedor `rqlite-dev` con `api.dev` a proposito: ninguna tool del MCP crea
locations —eso sigue viniendo del provisioning— asi que la base necesita que `./mill api.dev` haya
corrido al menos una vez para tener `cafe-centro`. Si no hay ninguna, el comando lo dice al arrancar.

LocalStack Community no implementa CloudFront. Con `AWS_ENDPOINT_URL` apuntando a un endpoint local
el servidor omite la invalidacion en vez de reportar un 501 en cada escritura de asset; contra AWS
real la invalidacion vuelve a ser obligatoria y un fallo es un error de la tool.

```bash
# Docker - imagenes base
./mill api.dockerBuild   <ecr-uri>/captal-api v1.0.0
./mill platform.dockerBuild <ecr-uri>/captal-provision v1.0.0

# CLI
./mill cli.run -- init --claude
./mill cli.run -- locations add cafe-centro
./mill cli.run -- video add cafe-centro chromecast ./blazes.mp4
```

---

## TODOs Pendientes

### Pruebas E2E en UI usando Playwright
Implementar tests E2E del cliente web (Welcome → IdentificationQuestion → Video → AdvertiserSurvey → Ready → Authorized) usando Playwright MCP.

### Unit test de `UnifiClient`
Viable desde que la lógica HTTP se extrajo del handler: levantar un server zio-http en puerto 0 y usar `UnifiClient.live(client, scheme = "http")`. Cubrir authorize (path, encoding del `filter`, `X-API-KEY`, body con `timeLimitMinutes`), revoke (`action` correcto y **sin** `timeLimitMinutes`), `{"data":[]}` → "no client found", no-2xx en GET y en POST, más asserts puros sobre el bracketing IPv6 de `baseUrl` y `normalizeMac`.

Ojo: **no existe módulo `platform.test`** — `build.mill` sólo define `object test` bajo `core`, `api` y `cli`. O va en `api/test/` como `ZIOSpecDefault` standalone (fuera de `E2ETests.spec`, que hace `provideShared` y necesita rqlite), o hay que agregar el submódulo al build.

---

## Docker e imagenes base

El proyecto produce **dos imagenes base** que se pushean a ECR:

- `captal-api` (`Dockerfile.api`): JVM + `api.jar` assembly. Base del ECS service per-location. Incluye clases de `platform` (Migrate) por el assembly.
- `captal-provision` (`Dockerfile.provision`): JVM + `platform.jar` assembly. Base de la task efimera de `shared push`. Mas pequena (no incluye `api/`).

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
./mill platform.dockerBuild <account>.dkr.ecr.us-east-1.amazonaws.com/captal-provision v1.0.0
./mill platform.dockerPush  <account>.dkr.ecr.us-east-1.amazonaws.com/captal-provision v1.0.0

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
- `platform/provision/`: `ProvisionService`, `ProvisionPlan`, `EntityWriter`, `IdGenerator`
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
./mill platform.dockerBuild --repoUri <ecr>/captal-provision-dev --tag vX.Y.Z
./mill platform.dockerPush  --repoUri <ecr>/captal-provision-dev --tag vX.Y.Z

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

**TODO**: el bucket `captal-cli-releases` es nuevo; agregar a Terraform platform (versioning + lifecycle policy para cleanup de releases viejos).

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

> **Tapir ya no existe en el repo, y con él tres detalles de esta sección.** Las once rutas se
> describen hoy con el `Endpoint` de zio-http, que es la librería sobre la que el servidor ya corría
> por debajo. En consecuencia: `SessionCookieConfig` ya no tiene `tapirInput`/`tapirOutput` ni
> `asMeta` — tiene `valueFrom` (saca esta cookie del header `Cookie` completo) y `asSetCookie`;
> `SessionEndpoint` volvió a ser `object`, porque la clase existía solo para construir el endpoint
> parcial de tapir; y el módulo `endpoints/` se borró entero. Lo que esta sección describe del
> *comportamiento* de la cookie — nombre y path por slug, una entrada por location en el browser —
> sigue siendo exacto, y ahora hay un test que compara los atributos que escribe cada ruta.

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

- **API**: `captal-api-dev:v1.0.0` → `v1.1.0` (cookie isolation + soft-validate + cross-location user) → `v1.2.0` (= v1.1.0 + soft-delete wiring) → `v1.3.0` (= v1.2.0 + click_id pendiente push como v1.4.0) → `v1.6.0` (UnifiAccessHandler stack: handler post-commit + /api/finish StatusResponse + proxy config) → `v2.0.0` (UniFi Integration v1 API: siteId UUID, two-step lookup+authorize, drop unifiOs) → `v2.1.0` (ap_mac moved into unifi block + redirectUrl override).
- **Provision**: `captal-provision-dev:v1.0.0` → `v1.3.0` (con el soft-delete wiring). Co-pushed con API en v2.0.0+ via `images.api` referenciado en `shared/captal.yaml`.
- **CLI**: `1.0.0` → `1.6.4` (varias rondas: ALB rule fix, target group settings, video CloudFront URL fix, self-update, skills, click_id) → `1.7.0` (deprovision command + push refactor) → `1.7.1` (push-all merged into push 'all' sentinel) → `2.0.1` (UniFi Integration v1 schema break: site→siteId, drop unifiOs) → `2.1.0` (ap_mac→unifi.apMac + redirectUrl + **sistema de migrations declarativo** con YamlPath compile-time validated + auto-warning hook + `captal migrate` command).
- **rqlite custom image**: `captal-rqlite:1.0.0` → `1.0.4` (peers.json single-node recovery + DNS-based identity + continue_on_failure auto-restore + entrypoint v4 nuclear-clean migration). El módulo TF de rqlite usa `var.rqlite_image_tag` (no más `:latest`) — bump explícito por cada cambio al entrypoint o Dockerfile.

### Infrastructure changes (whitelabel-captal-infrastructure repo)

- **Tailscale proxy module** (`modules/tailscale-proxy/`): ECS Fargate userspace Tailscale daemon exposing HTTP CONNECT proxy on port 1055. Routes API → tailnet → on-prem UCG. Auth key minted automáticamente vía `tailscale/tailscale` TF provider; creds via env vars `TAILSCALE_API_KEY` + `TAILSCALE_TAILNET`.
- **Global Accelerator + Portal Dispatcher** (`modules/global-accelerator/` + `modules/portal-dispatcher/`): 2 IPv4 anycast estáticas en frente del ALB (UniFi External Portal sólo acepta IPv4). El Lambda dispatcher (Python en VPC) recibe los hits del UCG en `/guest/s/*`, lookup del slug via `SELECT slug, unifi_redirect_url FROM locations WHERE unifi_ap_mac = ?` contra rqlite, y 302 al CloudFront destino. Gatable con `enable_global_accelerator` (default true).
- **rqlite single-node hardening**: V18 migration de DB (siteId rename + drop unifiOs), V19 (ap_mac → unifi_ap_mac + add unifi_redirect_url). Entrypoint v4 nuclear-clean (wipea raft.db, rsnapshots/, db.sqlite3* en primer boot via sentinel). peers.json escrito on-boot para forzar membership = DNS identity. `continue_on_failure: true` en auto-restore para que un backup S3 faltante no crashee el cluster.
- **Multi-node deployment percentages** (`modules/rqlite/main.tf`): cálculo automático de `deployment_minimum_healthy_percent` y `deployment_maximum_percent` a partir de `var.desired_count` (Raft quorum-aware: para N=5, min=60%, max=120% — siempre mantiene quorum durante rolling deploys). Single-node mantiene 0/100 stop-then-start.

---

## Documentacion Adicional

- **docs/EVENT_SOURCING_SUMMARY.md**: Detalle completo del modelo de Event Sourcing
- **client/STYLING.md**: Variables CSS y theming del cliente
- **Imagenes del Board de Miro** (*.jpg): Diagramas visuales originales del diseno
