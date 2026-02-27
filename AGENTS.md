# Contexto del Proyecto - Portal Captivo Whitelabel

## Descripcion del Proyecto

Este repositorio implementa un **portal captivo whitelabel** para redes WiFi. El sistema permite a usuarios conectarse a internet a cambio de:

1. Proporcionar datos de identificacion (email, perfilado)
2. Visualizar publicidad
3. Responder encuestas

El proyecto utiliza arquitectura **Event Sourcing** y esta implementado en **Scala 3** usando **Mill** como build tool y **ZIO** como runtime.

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
├── build.mill                    # Configuracion Mill
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
│   └── src/
│       └── whitelabel/captal/api/
│           ├── Main.scala              # Entry point, composicion de layers
│           ├── ApiError.scala          # Errores HTTP
│           ├── SessionEndpoint.scala   # Seguridad por cookie
│           ├── SurveyEndpoints.scala   # Definicion de endpoints
│           └── SurveyRoutes.scala      # Implementacion de rutas
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
| `AnswerEmailHandler` | `AnswerEmailCommand` | `QuestionAnswer` | `SurveyRepository` |
| `AnswerProfilingHandler` | `AnswerProfilingCommand` | `QuestionAnswer` | `SurveyRepository`, `UserRepository` |
| `AnswerLocationHandler` | `AnswerLocationCommand` | `QuestionAnswer` | `SurveyRepository`, `UserRepository` |
| `ProvideNextIdentificationSurveyHandler` | `ProvideNextIdentificationSurveyCommand` | `Option[NextIdentificationSurvey]` | `SurveyRepository`, `UserRepository` |

---

## API Endpoints

| Endpoint | Metodo | Path | Request | Response |
|----------|--------|------|---------|----------|
| Status | GET | `/api/status` | - | `Phase` |
| Next Survey | GET | `/api/survey/next` | - | `Option[NextIdentificationSurvey]` |
| Answer Email | POST | `/api/survey/email` | `{ email: String }` | `QuestionAnswer` |
| Answer Profiling | POST | `/api/survey/profiling` | `{ optionId: String }` | `QuestionAnswer` |
| Answer Location | POST | `/api/survey/location` | `{ optionId: String }` | `QuestionAnswer` |

Todos los endpoints usan autenticacion por cookie (`session_id`).

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

# Solo infra (incluye E2E)
./mill infra.test

# Formatear codigo
./mill mill.scalalib.scalafmt.ScalafmtModule/reformatAll __.sources
```

---

## TODOs

### 1. Investigar QueryMeta para Eliminar Row Types

Investigar si `QueryMeta` de Quill permite decodificar directamente a tipos de dominio (`Survey[State]`, `User[State]`) sin necesidad de Row types intermedios.

Referencias:
- [ZIO Quill - Extending Quill](https://zio.dev/zio-quill/extending-quill/)
- [ProtoQuill GitHub](https://github.com/zio/zio-protoquill)

```scala
// Posible uso de QueryMeta
inline given QueryMeta[User[State.WithEmail]] =
  queryMeta(
    quote { query[UserRow].filter(_.email.isDefined) }
  )(row => User(row.id, State.WithEmail(row.email.get)))
```

### 2. Cross-Compilar Core para Scala.js

Configurar Mill para cross-compilar el modulo `core` a JVM y JS:

```scala
// build.mill
object core extends Cross[CoreModule]("jvm", "js")

class CoreModule(platform: String) extends BaseModule with PlatformModule:
  def platformSegment = platform
  // ...
```

### 3. Cliente Laminar

Crear un cliente web con Laminar que:
- Consuma los endpoints del API
- Comparta la logica del core (validaciones, tipos de dominio)
- Use Tapir client para generar llamadas HTTP

### 4. Compartir Definiciones de Endpoints

Refactorizar `SurveyEndpoints` para que sea cross-compilable y pueda usarse tanto en:
- **api**: Para generar rutas del servidor
- **client**: Para generar llamadas HTTP tipadas

```scala
// shared/endpoints/SurveyEndpoints.scala (cross-compiled)
object SurveyEndpoints:
  val answerEmail = endpoint
    .post
    .in("api" / "survey" / "email")
    .in(jsonBody[AnswerEmailRequest])
    .out(jsonBody[QuestionAnswer])

// api (JVM)
val route = answerEmail.zServerLogic(...)

// client (JS)
val call = SttpClientInterpreter().toClient(answerEmail, baseUri, backend)
```

### 5. Compartir Logica del Core con Cliente

El modulo `core` ya es independiente de ZIO y usa `cats.Monad`. Al cross-compilarlo:
- Las validaciones funcionaran en el cliente
- Los tipos de dominio seran compartidos
- Los errores seran consistentes entre cliente y servidor

---

## Documentacion Adicional

- **docs/EVENT_SOURCING_SUMMARY.md**: Detalle completo del modelo de Event Sourcing
- **Imagenes del Board de Miro** (*.jpg): Diagramas visuales originales del diseno
