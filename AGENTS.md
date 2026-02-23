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
| Quill | Acceso a base de datos |
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
│   │       ├── application/      # Handlers (casos de uso)
│   │       │   ├── commands/     # Comandos y handlers
│   │       │   ├── Error.scala   # Errores de aplicacion
│   │       │   └── Event.scala   # Eventos de aplicacion
│   │       ├── infrastructure/   # Traits de repositorios y Session
│   │       ├── survey/           # Agregado Survey
│   │       └── user/             # Agregado User
│   └── test/
├── infra/                        # Implementaciones con Quill
│   └── src/
│       └── whitelabel/captal/infra/
│           ├── rows.scala        # Row types para BD
│           ├── SessionRepositoryQuill.scala
│           ├── SurveyRepositoryQuill.scala
│           └── UserRepositoryQuill.scala
├── api/                          # Capa HTTP con Tapir
│   └── src/
│       └── whitelabel/captal/api/
│           ├── SessionEndpoint.scala    # Seguridad por cookie
│           ├── SessionServiceLive.scala # Validacion de sesion
│           └── SurveyEndpoints.scala    # Endpoints de encuestas
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
  def handle(command: C): F[Op[Result]]

object Handler:
  type Aux[F[_], C, R] = Handler[F, C] { type Result = R }

// Ejemplo de implementacion
object AnswerProfilingHandler:
  def apply[F[_]](
      session: Session[F],
      surveyRepo: SurveyRepository[F],
      userRepo: UserRepository[F]
  )(using F: Monad[F], P: Parallel[F]): Handler.Aux[F, AnswerProfilingCommand, QuestionAnswer] =
    new Handler[F, AnswerProfilingCommand]:
      type Result = QuestionAnswer
      def handle(cmd: AnswerProfilingCommand) = ...
```

### 3. Session Management

La sesion se maneja en capas:

```
HTTP Request (cookie)
       |
SessionService.validate (valida cookie)
       |
SessionRepository.findById (consulta BD)
       |
SessionData (datos crudos)
       |
Session[Task] (capability para handlers)
```

```scala
// core/infrastructure/Session.scala
final case class SessionData(
    sessionId: user.SessionId,
    userId: user.Id,
    locale: String,
    currentSurveyId: Option[survey.Id],
    currentQuestionId: Option[survey.question.Id])

trait Session[F[_]]:
  def userId: F[user.Id]
  def locale: F[String]
  def currentSurveyId: F[Option[survey.Id]]
  def currentQuestionId: F[Option[survey.question.Id]]
  def setCurrentSurvey(surveyId: survey.Id, questionId: survey.question.Id): F[Unit]

trait SessionRepository[F[_]]:
  def findById(sessionId: user.SessionId): F[Option[SessionData]]
  def setCurrentSurvey(sessionId: user.SessionId, surveyId: survey.Id, questionId: survey.question.Id): F[Unit]
```

### 4. Repository Pattern

Los repositorios siguen el patron:
- Trait abstracto en `core/infrastructure/`
- Implementacion Quill en `infra/`

```scala
// core/infrastructure/SurveyRepository.scala
trait SurveyRepository[F[_]]:
  def findById(id: survey.Id): F[Option[Survey[State]]]
  def findWithEmailQuestion(surveyId: survey.Id, questionId: survey.question.Id): F[Option[Survey[State.WithEmailQuestion]]]
  def findWithProfilingQuestion(surveyId: survey.Id, questionId: survey.question.Id): F[Option[Survey[State.WithProfilingQuestion]]]
  def findWithLocationQuestion(surveyId: survey.Id, questionId: survey.question.Id): F[Option[Survey[State.WithLocationQuestion]]]
  def findWithAdvertiserQuestion(surveyId: survey.Id, questionId: survey.question.Id): F[Option[Survey[State.WithAdvertiserQuestion]]]
  def findNextIdentificationSurvey(userId: user.Id): F[Option[NextIdentificationSurvey]]

// infra/SurveyRepositoryQuill.scala
object SurveyRepositoryQuill:
  def apply[D <: SqlIdiom, N <: NamingStrategy](quill: Quill[D, N]): SurveyRepository[Task] = ...
  def layer[D <: SqlIdiom: Tag, N <: NamingStrategy: Tag]: ZLayer[Quill[D, N], Nothing, SurveyRepository[Task]] = ...
```

---

## Modelo de Dominio

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
  case PendingEmail(sessionId: SessionId, deviceId: DeviceId, locale: String)
  case WithEmail(email: Email, sessionId: SessionId, locale: String)
  case AnsweringQuestion(sessionId: SessionId, locale: String, questionId: survey.question.Id)

// Entidad User tipada por estado
final case class User[S <: State](id: user.Id, state: S)
```

### Eventos y Errores

```scala
// Eventos de Survey
enum Event:
  case QuestionAnswered(userId: user.Id, questionId: survey.question.Id, answer: AnswerValue, occurredAt: Instant)

// Eventos de User
enum Event:
  case UserCreatedWithEmail(id: user.Id, sessionId: SessionId, deviceId: DeviceId, email: Email, locale: String, occurredAt: Instant)

// Errores de aplicacion
enum Error:
  case NoSurveyAssigned
  case UserNotFound(userId: user.Id)
  case SurveyNotFound(surveyId: survey.Id)
  case InvalidEmailFormat(value: String)
```

---

## Handlers Disponibles

| Handler | Comando | Resultado | Dependencias |
|---------|---------|-----------|--------------|
| `AnswerEmailHandler` | `AnswerEmailCommand` | `QuestionAnswer` | `Session` |
| `AnswerProfilingHandler` | `AnswerProfilingCommand` | `QuestionAnswer` | `Session`, `SurveyRepository`, `UserRepository` |
| `AnswerLocationHandler` | `AnswerLocationCommand` | `QuestionAnswer` | `Session`, `SurveyRepository`, `UserRepository` |
| `AnswerAdvertiserHandler` | `AnswerAdvertiserCommand` | `QuestionAnswer` | `Session`, `SurveyRepository`, `UserRepository` |
| `ProvideNextIdentificationSurveyHandler` | `ProvideNextIdentificationSurveyCommand` | `Option[NextIdentificationSurvey]` | `Session`, `SurveyRepository` |

---

## Validacion de Respuestas

El sistema valida respuestas segun el tipo de pregunta:

```scala
// Reglas de seleccion (Radio, Select, Checkbox)
enum SelectionRule:
  case MinSelections(min: Int)
  case MaxSelections(max: Int)

// Reglas de texto (Input)
enum TextRule:
  case MinLength(min: Int)
  case MaxLength(max: Int)
  case Email
  case Url
  case Pattern(regex: String)

// Reglas de rango (Rating, Numeric, Date)
enum RangeRule:
  case Min(value: BigDecimal)
  case Max(value: BigDecimal)
  case DateMin(value: LocalDate)
  case DateMax(value: LocalDate)

// Reglas comunes
enum CommonRule:
  case Required
```

---

## Tipos de Identificacion

El sistema usa newtypes para IDs:

```scala
// IDs opacos con validacion UUID
opaque type Id = UUID
object Id:
  def generate: Id = UUID.randomUUID()
  def fromString(s: String): Option[Id] = Try(UUID.fromString(s)).toOption
  extension (id: Id) def asString: String = id.toString

// Ejemplos
survey.Id           // ID de encuesta
survey.question.Id  // ID de pregunta
user.Id             // ID de usuario
user.SessionId      // ID de sesion
user.DeviceId       // ID de dispositivo
OptionId            // ID de opcion de pregunta
AdvertiserId        // ID de anunciante
```

---

## Base de Datos

### Tablas (rows.scala)

```scala
final case class UserRow(id, email, locale, createdAt, updatedAt)
final case class SessionRow(id, userId, deviceId, locale, currentSurveyId, currentQuestionId, createdAt)
final case class SurveyRow(id, category, advertiserId, isActive, createdAt)
final case class QuestionRow(id, surveyId, questionType, textContent, textLocale, ...)
final case class QuestionOptionRow(id, questionId, textContent, textLocale, displayOrder, parentOptionId)
final case class QuestionRuleRow(id, questionId, ruleType, ruleConfig)
final case class AnswerRow(id, userId, sessionId, questionId, answerValue, answeredAt, createdAt)
final case class UserSurveyProgressRow(id, userId, surveyId, currentQuestionId, completedAt, ...)
```

---

## Testing

Los tests usan `cats.Id` como monad sincrono para evitar ZIO runtime:

```scala
// Mock de Session
def mockSession(userId: user.Id, surveyId: Option[survey.Id] = None): Session[Id] =
  new Session[Id]:
    def userId: Id[user.Id] = userId
    def locale: Id[String] = "en"
    def currentSurveyId: Id[Option[survey.Id]] = surveyId
    def currentQuestionId: Id[Option[survey.question.Id]] = None
    def setCurrentSurvey(sId: survey.Id, qId: survey.question.Id): Id[Unit] = ()

// Uso en test
val session = mockSession(user.Id.generate)
val handler = AnswerEmailHandler(session)
val result = Op.run(handler.handle(cmd))
assert(result.isRight)
```

---

## Decisiones de Diseno

### 1. Parametros Explicitos vs Context Bounds

Los handlers usan **parametros explicitos** para dependencias (Session, Repository) en vez de context bounds. Esto es compatible con ZIO layers:

```scala
// Antes (tagless final)
def apply[F[_]: Monad: Session: SurveyRepository]: Handler[F, Cmd]

// Ahora (parametros explicitos)
def apply[F[_]](session: Session[F], surveyRepo: SurveyRepository[F])(using Monad[F]): Handler[F, Cmd]
```

### 2. Session[Task] vs SessionContext

`SessionService.validate` retorna `Session[Task]` directamente, eliminando el DTO intermedio `SessionContext`.

### 3. Repositorios en core/infrastructure

Los traits de repositorios estan en `core/infrastructure/` para que el dominio pueda depender de ellos sin conocer la implementacion.

### 4. Typed States

Las entidades (`Survey`, `User`) estan tipadas por su estado, lo que hace imposible invocar operaciones invalidas en tiempo de compilacion.

### 5. Naming: Service vs ServiceLive

Solo usar sufijo `Live` cuando hay multiples implementaciones. Si hay una sola implementacion:

```scala
// SessionEndpoint.scala
object SessionEndpoint:
  trait Service:
    def validate(cookie: Option[String]): IO[SessionError, Session[Task]]

// SessionService.scala (sin sufijo Live)
object SessionService:
  def apply(sessionRepo: SessionRepository[Task]): SessionEndpoint.Service = ...
```

---

## Convenciones de Estilo

### 1. Sintaxis Scala 3 con indentacion

Usar `:` en vez de `{}` para bloques. Scalafmt lo aplica automaticamente:

```scala
// Correcto
object MyHandler:
  def apply[F[_]](session: Session[F])(using Monad[F]): Handler[F, Cmd] =
    new Handler[F, Cmd]:
      type Result = Response

      def handle(cmd: Cmd) =
        for
          userId <- session.userId
          result <- doSomething(userId)
        yield result

// Incorrecto (braces innecesarias)
object MyHandler {
  def apply[F[_]](session: Session[F])(using Monad[F]): Handler[F, Cmd] = {
    new Handler[F, Cmd] {
      ...
    }
  }
}
```

### 2. Evitar type annotations innecesarias

No tipar variables locales ni retornos de funciones privadas cuando el tipo es obvio:

```scala
// Correcto
val session = mockSession(user.Id.generate)
val handler = AnswerEmailHandler(session)
val result = Op.run(handler.handle(cmd))

// Incorrecto (tipos innecesarios)
val session: Session[Id] = mockSession(user.Id.generate)
val handler: Handler.Aux[Id, AnswerEmailCommand, QuestionAnswer] = AnswerEmailHandler(session)
val result: Either[NonEmptyChain[Error], (Chain[Event], QuestionAnswer)] = Op.run(handler.handle(cmd))
```

**Excepciones** - si tipar cuando:
- Es un metodo publico de una API
- El tipo no es obvio del contexto
- Mejora la legibilidad significativamente

### 3. Inferencia en parametros de funcion

Dejar que el compilador infiera tipos en lambdas y pattern matching:

```scala
// Correcto
users.map(_.email)
results.collect { case Right(value) => value }

// Incorrecto
users.map((u: User) => u.email)
results.collect { case Right(value: QuestionAnswer) => value }
```

### 4. Formateo automatico

Scalafmt y Scalafix aplican estas reglas automaticamente:

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

# Formatear codigo
./mill mill.scalalib.scalafmt.ScalafmtModule/reformatAll __.sources
```

---

## Documentacion Adicional

- **docs/EVENT_SOURCING_SUMMARY.md**: Detalle completo del modelo de Event Sourcing
- **Imagenes del Board de Miro** (*.jpg): Diagramas visuales originales del diseno
