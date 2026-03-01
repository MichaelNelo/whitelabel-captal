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
- **Base de datos**: SQLite con Quill, migraciones con Flyway
- **Cliente web**: Laminar + Scala.js con routing (Waypoint)
- **i18n**: Sistema de traducciones (ES/EN) cargadas desde BD
- **Cross-compilation**: Core y endpoints compartidos entre JVM y JS
- **Validacion client-side**: Usa logica del core via `Op.run`
- **Tests E2E API**: 34 tests pasando (session, surveys, validation, phase validation)
- **Phase validation**: Endpoints protegidos por fase con Tapir partial server endpoints
- **Docker**: Multi-stage Dockerfile para desarrollo (`dev.Dockerfile`)
- **BuildInfo**: Variables de entorno en build time para cliente (ENVIRONMENT)

### En Progreso 🚧
- **IdentificationQuestionView**: Diseño visual necesita iteracion (ver TODOs)

### Pendiente 📋
- Fase de video publicitario (AdvertiserVideo)
- Fase de encuesta de anunciante (AdvertiserQuestion)
- Fase Ready (acceso WiFi)
- Integracion con controlador de hotspot

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
| Tapir | Definicion de endpoints HTTP |
| Cats | Typeclasses (Monad, Parallel) |

### Estructura de Modulos

```
whitelabel-captal/
├── build.mill                    # Configuracion Mill (genera BuildInfo para cliente)
├── .mill-version                 # Version de Mill (1.1.2)
├── mill                          # Mill launcher (tracked en repo)
├── dev.Dockerfile                # Multi-stage build para desarrollo
├── .dockerignore                 # Exclusiones para Docker build
├── AGENTS.md                     # Este archivo
├── core/                         # Dominio y logica de negocio
│   ├── src/
│   │   └── whitelabel/captal/core/
│   │       ├── Op.scala          # Tipo Op[E, Er, A] para operaciones
│   │       ├── application/      # Handlers, eventos, fases
│   │       │   ├── commands/     # Comandos y handlers
│   │       │   ├── Error.scala   # Errores de aplicacion
│   │       │   ├── Event.scala   # Eventos de aplicacion
│   │       │   ├── EventHandler.scala  # Trait para event handlers
│   │       │   ├── Flow.scala    # Ejecutor de comandos
│   │       │   └── phase.scala   # Enum de fases del usuario
│   │       ├── infrastructure/   # Traits de repositorios y SessionData
│   │       ├── survey/           # Agregado Survey
│   │       └── user/             # Agregado User
│   └── test/
├── infra/                        # Implementaciones con Quill
│   ├── src/
│   │   └── whitelabel/captal/infra/
│   │       ├── schema.scala             # MappedEncoding para tipos de dominio
│   │       ├── rows.scala               # Row types (usan tipos de dominio)
│   │       ├── DbEventHandler.scala     # Trait para handlers transaccionales
│   │       ├── TransactionalEventHandler.scala  # Wrapper transaccional
│   │       ├── SessionContext.scala     # FiberRef para sesion
│   │       ├── SessionService.scala     # Servicio de sesiones
│   │       ├── SurveyService.scala      # Queries de progreso de surveys
│   │       ├── SurveyRepositoryQuill.scala
│   │       ├── UserRepositoryQuill.scala
│   │       └── eventhandlers/           # Event handlers
│   │           ├── AnswerPersistenceHandler.scala
│   │           ├── UserPersistenceHandler.scala
│   │           ├── SessionPhaseHandler.scala
│   │           ├── SessionSurveyHandler.scala
│   │           └── SurveyProgressHandler.scala
│   └── test/
│       └── whitelabel/captal/infra/
│           ├── E2ETests.scala           # Tests E2E con Tapir stub
│           ├── TestFixtures.scala       # Fixtures de BD
│           └── TestLayers.scala         # Layers para tests
├── api/                          # Capa HTTP con Tapir
│   ├── src/
│   │   └── whitelabel/captal/api/
│   │       ├── Main.scala              # Entry point, composicion de layers
│   │       ├── SessionEndpoint.scala   # Partial server endpoints (session + phase validation)
│   │       ├── SurveyRoutes.scala      # Implementacion de rutas survey
│   │       └── LocaleRoutes.scala      # Rutas de i18n/locale + dev routes
│   └── test/
│       └── whitelabel/captal/api/
│           └── suites/                 # Test suites E2E
│               ├── SessionManagementSuite.scala
│               ├── EmailSurveySuite.scala
│               ├── SurveyProgressionSuite.scala
│               ├── MultiQuestionSurveySuite.scala
│               ├── ValidationSuite.scala
│               └── PhaseValidationSuite.scala
├── endpoints/                    # Definiciones de endpoints (cross-compiled)
│   └── src/
│       └── whitelabel/captal/endpoints/
│           ├── SurveyEndpoints.scala   # Endpoints de surveys
│           ├── LocaleEndpoints.scala   # Endpoints de i18n
│           └── schemas.scala           # Schemas compartidos (JSON codecs)
├── client/                       # Cliente Laminar (Scala.js)
│   ├── index.html                # HTML con CSS variables para theming
│   ├── assets/
│   │   └── styles.css            # CSS extraido (variables, animaciones)
│   ├── STYLING.md                # Documentacion de variables CSS
│   └── src/
│       └── whitelabel/captal/client/
│           ├── Main.scala              # Entry point, monta app en DOM
│           ├── Router.scala            # Routing con Waypoint
│           ├── AppState.scala          # Estado global (Var)
│           ├── ApiClient.scala         # Llamadas HTTP con sttp
│           ├── Runtime.scala           # Runtime ZIO para Scala.js
│           ├── BuildInfo.scala         # [GENERADO] Variables de build (ENVIRONMENT)
│           ├── i18n/
│           │   └── I18nClient.scala    # Servicio de i18n client-side
│           └── views/
│               ├── Layout.scala                # Layout compartido con loading state
│               ├── WelcomeView.scala           # Pantalla de bienvenida
│               ├── IdentificationQuestionView.scala  # Preguntas de identificacion
│               └── ReadyView.scala             # Pantalla final (con reset en dev)
└── docs/
    └── EVENT_SOURCING_SUMMARY.md # Detalle del modelo ES
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
  case IdentificationQuestion  // Respondiendo preguntas de identificacion
  case AdvertiserVideo         // Viendo video publicitario
  case AdvertiserQuestion      // Respondiendo encuesta del anunciante
  case Ready                   // Listo para acceder a WiFi

object Phase:
  def toDbString(phase: Phase): String = phase match
    case IdentificationQuestion => "identification_question"
    case AdvertiserVideo => "advertiser_video"
    case AdvertiserQuestion => "advertiser_question"
    case Ready => "ready"

  def fromDbString(s: String): Phase = s match
    case "identification_question" => IdentificationQuestion
    case "advertiser_video" => AdvertiserVideo
    case "advertiser_question" => AdvertiserQuestion
    case "ready" => Ready
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

### Survey Endpoints

| Endpoint | Metodo | Path | Request | Response |
|----------|--------|------|---------|----------|
| Status | GET | `/api/status` | - | `StatusResponse` (phase, locale) |
| Next Survey | GET | `/api/survey/next` | - | `Option[NextIdentificationSurvey]` |
| Answer Email | POST | `/api/survey/email` | `AnswerValue` | `QuestionAnswer` |
| Answer Profiling | POST | `/api/survey/profiling` | `AnswerValue` | `QuestionAnswer` |
| Answer Location | POST | `/api/survey/location` | `AnswerValue` | `QuestionAnswer` |

### Locale/i18n Endpoints

| Endpoint | Metodo | Path | Request | Response |
|----------|--------|------|---------|----------|
| Get Locales | GET | `/api/locales` | - | `List[String]` |
| Get I18n | GET | `/api/i18n/{locale}` | - | `I18n` |
| Set Locale | PUT | `/api/session/locale` | `SetLocaleRequest` | `StatusResponse` + Set-Cookie |

### Dev-Only Endpoints (solo cuando `server.dev-mode=true`)

| Endpoint | Metodo | Path | Request | Response |
|----------|--------|------|---------|----------|
| Reset Phase | POST | `/api/dev/reset-phase` | - | `StatusResponse` |

Todos los endpoints usan autenticacion por cookie (`session_id`). La cookie se crea automaticamente en la primera request.

### Phase Validation

Los endpoints de survey validan la fase del usuario:

| Endpoint | Fases Permitidas |
|----------|------------------|
| `GET /api/status` | Cualquiera |
| `GET /api/survey/next` | Welcome, IdentificationQuestion |
| `POST /api/survey/email` | IdentificationQuestion |
| `POST /api/survey/profiling` | IdentificationQuestion |
| `POST /api/survey/location` | IdentificationQuestion |

Si la fase no coincide, retorna `ApiError.WrongPhase(current, expected)`.

---

## Testing

### E2E Tests con Tapir Stub

```scala
// infra/test/E2ETests.scala
object E2ETests extends ZIOSpecDefault:
  def spec: Spec[Any, Throwable] =
    (suite("Identification Survey Flow")(
      sessionManagementSuite,
      emailSurveySuite,
      surveyProgressionSuite,
      multiQuestionSurveySuite,
      validationSuite
    ) @@ TestAspect.sequential @@ TestAspect.after(TestFixtures.clearAllData.orDie))
      .provideShared(TestLayers.testEnv, ZLayer.fromZIO(TestFixtures.migrate.unit))
```

### Escenarios Validados

1. **Session Management**:
   - Nuevo visitante recibe sesion en fase de identificacion
   - Visitante recurrente mantiene su fase
   - Sesion perdida con usuario existente vincula al usuario existente (no crea duplicado)

2. **Email Survey**:
   - Usuario anonimo recibe encuesta de email como primer paso
   - Email valido crea usuario y transiciona a fase de video
   - Email invalido es rechazado con error de validacion

3. **Survey Progression**:
   - Usuario con email completado recibe encuesta de profiling
   - Usuario con email y profiling completados recibe encuesta de location
   - Usuario con todas las encuestas completadas no recibe mas encuestas

4. **Multi-Question Survey**:
   - Despues de responder primera pregunta, se ofrece siguiente pregunta del mismo survey

5. **Validation Rules**:
   - Email vacio es rechazado como campo requerido

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

# Ejecutar tests
./mill __.test

# Solo core
./mill core.test

# Solo API (incluye E2E)
./mill api.test

# Formatear codigo
./mill mill.scalalib.scalafmt.ScalafmtModule/reformatAll __.sources

# Build assembly JARs
./mill api.assembly      # -> out/api/assembly.dest/out.jar
./mill infra.assembly    # -> out/infra/assembly.dest/out.jar

# Compilar cliente
./mill client.fastLinkJS                    # Production
ENVIRONMENT=dev ./mill client.fastLinkJS    # Dev mode

# Docker
docker build -f dev.Dockerfile -t captal-dev .
docker run -p 8080:8080 -v $(pwd)/captal-dev.db:/app/captal-dev.db captal-dev

# Migraciones y seed
./mill infra.migrate
./mill infra.seed
```

---

## TODOs

### 1. ~~[BUG] Botones no visibles en Welcome e Identification~~ ✅ CORREGIDO

**Problema:** Los selectores CSS usaban `.welcome-text-content.visible` y `.question-text-content.visible` pero el Layout unificado usa `.view-content.visible`.

**Solucion aplicada:** Actualizado `styles.css`:
- `.app-layout:has(.welcome-text-content.visible)` → `.app-layout:has(.view-content.visible)`
- `.app-layout:has(.question-text-content.visible)` → `.app-layout:has(.view-content.visible)`

### 2. ~~Diseño de IdentificationQuestionView~~ ✅ COMPLETADO

Vista de preguntas de identificación implementada:
- Estilo visual consistente con WelcomeView (texto sobre gradiente, elementos glass)
- Stack vertical de preguntas
- Validación client-side usando `core.Op.run(questionOps.validate(...))`
- Mensajes de error i18n completos
- Layout unificado con loading state

### 3. ~~Investigar QueryMeta para Eliminar Row Types~~ ❌ NO VIABLE

Se investigó `QueryMeta` de Quill pero no fue posible eliminar los Row types:
- ProtoQuill requiere tipos concretos en compile-time
- Los estados tipados (`Survey[State]`, `User[State]`) no son compatibles con el modelo de Quill
- Los Row types intermedios son necesarios para el mapeo DB ↔ dominio

### 4. Pruebas E2E en UI usando Playwright

**Descripcion:**
Implementar tests E2E para validar el flujo de usuario en el cliente web usando Playwright MCP.

**Tareas:**
- Configurar Playwright para el proyecto
- Escribir tests para el flujo Welcome -> Question -> Video -> Ready
- Validar estados visuales (loading, loaded, error)
- Integrar con CI

### 5. ~~Redirigir /* al cliente + endpoints estaticos solo en dev~~ ✅ COMPLETADO

**Implementacion:**
- `server.dev-mode` en `application.conf` (default: true, override con `SERVER_DEV_MODE=false`)
- En dev mode: sirve assets (css, js, svg) y catch-all SPA (`/*` -> `index.html`)
- En prod mode: solo API endpoints (`/api/*`)
- Cliente (`Main.scala`): `syncPhaseOnLoad()` chequea fase y redirige al montar la app

### 6. ~~Validaciones agresivas de fase en cada endpoint~~ ✅ COMPLETADO

**Implementacion:**
- `SessionEndpoint.secured`: Partial server endpoint que resuelve sesion
- `SessionEndpoint.withPhase(phases*)`: Partial server endpoint que valida fase
- Todos los endpoints de survey usan el patron de partial server endpoints
- `ApiError.WrongPhase(current, expected)`: Error cuando fase no coincide
- 15 tests E2E en `PhaseValidationSuite` validan todas las combinaciones

**Endpoints protegidos:**
- `POST /api/survey/email` - solo `IdentificationQuestion`
- `POST /api/survey/profiling` - solo `IdentificationQuestion`
- `POST /api/survey/location` - solo `IdentificationQuestion`
- `GET /api/survey/next` - `Welcome` o `IdentificationQuestion`
- `GET /api/status` - cualquier fase (usa `secured` sin validacion de fase)

### 7. ~~Cross-Compilar Core para Scala.js~~ ✅ COMPLETADO

Core y endpoints estan cross-compilados. Ver `build.mill`.

### 8. ~~Cliente Laminar~~ ✅ COMPLETADO

Cliente implementado con:
- Laminar para UI reactiva
- Waypoint para routing
- sttp con Fetch backend para HTTP
- I18n client-side cargando traducciones del servidor

### 9. ~~Compartir Definiciones de Endpoints~~ ✅ COMPLETADO

Modulo `endpoints` es cross-compiled y usado por:
- `api` (JVM): Genera rutas del servidor
- `client` (JS): Genera llamadas HTTP tipadas via sttp

### 10. ~~Compartir Logica del Core con Cliente~~ ✅ COMPLETADO

El cliente usa logica del core para:
- Validaciones (`Op.run(questionOps.validate(...))`)
- Tipos de dominio (`AnswerValue`, `QuestionType`, etc.)
- Errores consistentes entre cliente y servidor

---

## Docker (Desarrollo)

### dev.Dockerfile

Multi-stage build para desarrollo:

```dockerfile
# Stage 1: Build - compila API, infra y cliente
FROM eclipse-temurin:21-jdk AS builder
COPY mill build.mill .mill-version ./
COPY core/ endpoints/ infra/ api/ client/ ./
RUN ENVIRONMENT=dev ./mill api.assembly && \
    ./mill infra.assembly && \
    ENVIRONMENT=dev ./mill client.fastLinkJS

# Stage 2: Runtime - solo JRE + JARs
FROM eclipse-temurin:21-jre-noble
COPY --from=builder /app/out/api/assembly.dest/out.jar api.jar
COPY --from=builder /app/out/infra/assembly.dest/out.jar infra.jar
COPY --from=builder /app/out/client/fastLinkJS.dest/ out/client/fastLinkJS.dest/
CMD java -cp infra.jar whitelabel.captal.infra.Migrate && \
    java -cp infra.jar whitelabel.captal.infra.Seed && \
    java -jar api.jar
```

**Uso:**
```bash
docker build -f dev.Dockerfile -t captal-dev .
docker run -p 8080:8080 -v $(pwd)/captal-dev.db:/app/captal-dev.db captal-dev
```

---

## BuildInfo (Cliente)

El cliente usa `BuildInfo` generado en build time para features condicionales:

```scala
// build.mill - generatedSources en client
def generatedSources: T[Seq[PathRef]] = Task:
  val env = Task.env.getOrElse("ENVIRONMENT", "production")
  val isDevMode = env == "dev"
  val dest = Task.dest / "BuildInfo.scala"
  os.write(dest, s"""
    package whitelabel.captal.client
    object BuildInfo:
      val environment: String = "$env"
      val isDevMode: Boolean = $isDevMode
  """)
  Seq(PathRef(Task.dest))
```

**Uso:**
```bash
# Dev mode (incluye boton reset, etc.)
ENVIRONMENT=dev ./mill client.fastLinkJS

# Production (features dev eliminadas por dead code elimination)
./mill client.fastLinkJS
```

---

## Cambios Recientes (Session Actual)

### CSS Extraido a Archivo Externo
- **Antes**: CSS inline en `client/index.html` (~970 lineas)
- **Ahora**: CSS en `client/assets/styles.css`
- `Main.scala` sirve el CSS en `/assets/styles.css`

### Ruta /video renombrada a /final
- `Router.scala`: ruta `/video` -> `/final`
- Serializacion: `"video"` -> `"final"`
- Page title: `"Video"` -> `"Final"`

### Layout Unificado con Loading State
- `Layout.scala` ahora acepta `isLoading: Signal[Boolean]`
- Nuevas clases CSS unificadas:
  - `.layout-view.loading-state` / `.layout-view.loaded-state`
  - `.view-icon` (antes `.welcome-icon`, `.question-icon`)
  - `.view-content.hidden` / `.view-content.visible`
- WelcomeView, IdentificationQuestionView y ReadyView usan el nuevo Layout
- Brand icon centralizado en Layout (ya no se repite en cada view)

### SPA Routing y Dev Mode
- `api/resources/application.conf`: Agregado `server.dev-mode`
- `api/src/.../Main.scala`:
  - `ServerSettings` case class con config y devMode
  - `devStaticRoutes`: assets solo en dev mode
  - `spaCatchAllRoutes`: catch-all `/*` -> `index.html` para SPA routing
  - `routes(devMode)`: compone rutas segun modo
- `client/src/.../Main.scala`:
  - `syncPhaseOnLoad()`: chequea fase del servidor y redirige al montar

### Archivos Modificados (Session Anterior)
- `client/index.html` - Solo estructura, sin CSS inline
- `client/assets/styles.css` - CSS extraido + nuevas clases unificadas
- `client/src/.../views/Layout.scala` - Acepta isLoading, maneja brand icon
- `client/src/.../views/WelcomeView.scala` - Usa Layout con isLoading
- `client/src/.../views/IdentificationQuestionView.scala` - Usa Layout con isLoading
- `client/src/.../views/ReadyView.scala` - Usa Layout
- `client/src/.../Router.scala` - Ruta /final en lugar de /video
- `api/src/.../Main.scala` - Sirve styles.css

### Phase Validation con Tapir Partial Server Endpoints
- `SessionEndpoint.scala`: `secured` y `withPhase(phases*)` para validacion
- `SurveyRoutes.scala`: Todos los endpoints usan partial server endpoints
- `ApiError.scala`: Agregado `WrongPhase(current, expected)`
- `PhaseValidationSuite.scala`: 15 tests E2E para validacion de fases

### Dev Reset Endpoint
- `LocaleEndpoints.resetPhase`: `POST /api/dev/reset-phase` (solo dev mode)
- `LocaleRoutes.devRoutes`: Lista de rutas solo para dev
- `Main.scala`: Monta `devRoutes` solo cuando `server.dev-mode=true`
- `ReadyView.scala`: Boton "Reset (Dev)" que llama al endpoint (solo en dev)

### BuildInfo para Cliente
- `build.mill`: `generatedSources` genera `BuildInfo.scala` con `ENVIRONMENT`
- `ReadyView.scala`: Usa `BuildInfo.isDevMode` en lugar de detectar hostname
- Dead code elimination: En production, codigo dev no se incluye en el JS

### Docker Multi-Stage
- `dev.Dockerfile`: Build con Mill, runtime con JRE + JARs
- `.mill-version`: Version de Mill (1.1.2)
- `.dockerignore`: Exclusiones para build mas rapido
- Usa `api.assembly` e `infra.assembly` para JARs ejecutables

### Core Tests Actualizados
- `HandlerTests.scala`: Handlers ahora requieren `nextStep: NextStep`
- Assertions actualizadas para validar `NextStep` en lugar de `answer`
- 48 tests pasando (8 Handler + 21 ValidationSuccess + 19 ValidationFailure)

---

## Documentacion Adicional

- **docs/EVENT_SOURCING_SUMMARY.md**: Detalle completo del modelo de Event Sourcing
- **Imagenes del Board de Miro** (*.jpg): Diagramas visuales originales del diseno
