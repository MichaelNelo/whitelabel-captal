# Contexto del Proyecto - Portal Captivo Whitelabel

## Descripcion del Proyecto

Este repositorio implementa un **portal captivo whitelabel** para redes WiFi. El sistema permite a usuarios conectarse a internet a cambio de:

1. Proporcionar datos de identificacion (email, perfilado)
2. Visualizar publicidad
3. Responder encuestas

El proyecto utiliza arquitectura **Event Sourcing** y esta implementado en **Scala 3** usando **Mill** como build tool y **ZIO** como runtime.

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

### Pendiente 📋
- Fase Ready completa (acceso WiFi tras encuesta)
- Integracion con controlador de hotspot
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
  case Ready                   // Listo para acceder a WiFi
```

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
// Estados del User
enum State:
  case WithEmail(email: Email)
  case AnsweringQuestion(surveyId: survey.Id, questionId: survey.question.Id)

// Entidad User tipada por estado
final case class User[S <: State](id: user.Id, state: S)
```

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
  case SurveyAssigned(userId, surveyId, questionId, occurredAt)
  case NewUserArrived(surveyId, questionId, occurredAt)
```

---

## Event Handlers Disponibles

| Handler | Tipo | Funcion |
|---------|------|---------|
| `AnswerPersistenceHandler` | `DbEventHandler` | Persiste respuestas en `answers` |
| `UserPersistenceHandler` | `DbEventHandler` | Crea usuarios (o vincula existentes), actualiza session |
| `SessionPhaseHandler` | `DbEventHandler` | Actualiza fase de la session |
| `SessionSurveyHandler` | `DbEventHandler` | Actualiza survey/question actual en session |
| `SurveyProgressHandler` | `DbEventHandler` | Actualiza progreso del survey, marca completado |

Todos corren en una sola transaccion via `TransactionalEventHandler`.

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
| Status | GET | `/api/status` | - | `StatusResponse` (phase, locale) |
| Next Survey | GET | `/api/survey/next` | - | `Option[NextIdentificationSurvey]` |
| Answer Email | POST | `/api/survey/email` | `AnswerValue` | `QuestionAnswer` |
| Answer Profiling | POST | `/api/survey/profiling` | `AnswerValue` | `QuestionAnswer` |
| Answer Location | POST | `/api/survey/location` | `AnswerValue` | `QuestionAnswer` |

### Video / Advertiser Endpoints

| Endpoint | Metodo | Path | Request | Response |
|----------|--------|------|---------|----------|
| Get Next Video | GET | `/api/video/next` | - | `VideoMetadata` |
| Mark Watched | POST | `/api/video/watched` | - | `StatusResponse` |
| Get Advertiser Survey | GET | `/api/advertiser-survey/next` | - | `Option[QuestionToAnswer]` |
| Answer Advertiser Survey | POST | `/api/advertiser-survey/answer` | `AnswerValue` | `QuestionAnswer` |

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
| `GET /api/advertiser-survey/next` | AdvertiserVideoSurvey, AdvertiserQuestion |
| `POST /api/advertiser-survey/answer` | AdvertiserVideoSurvey, AdvertiserQuestion |

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

`shared/captal.yaml` define AWS region, ECR images (4 repos), bucket S3 + bundlePrefix, cluster ECS, subnets/SGs, ALB listener, CloudFront distributionId, database URL. La skill `configure-aws` guia su llenado con comandos `aws` CLI. Si no se incluyen credenciales explicitas, el SDK usa la cadena default.

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
7. Register task definition `captal-<slug>` con env vars `LOCATION_SLUG`, `PROVISION_DIR=/etc/captal/provision`, `DB_URL`, `DB_DEV_SEED=false`, etc. + `taskRoleArn` (requerido para ECS Exec)
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
Implementar tests E2E del cliente web (Welcome → IdentificationQuestion → Video → AdvertiserSurvey → Ready) usando Playwright MCP.

### Fase Ready completa
- Integracion con controlador de hotspot para liberar acceso WiFi
- Timer de sesion (max 5 min)
- Reconexion: re-evaluar surveys pendientes

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

## Documentacion Adicional

- **docs/EVENT_SOURCING_SUMMARY.md**: Detalle completo del modelo de Event Sourcing
- **client/STYLING.md**: Variables CSS y theming del cliente
- **Imagenes del Board de Miro** (*.jpg): Diagramas visuales originales del diseno
