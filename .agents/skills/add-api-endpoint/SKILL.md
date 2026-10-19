---
name: add-api-endpoint
description: Use when adding a new HTTP endpoint to the captal guest API. Covers the zio-http Endpoint definition, ApiBase prefixing, secured vs public, messages/ DTOs, wire.scala, layer wiring, and tests. Triggers on "add endpoint", "new api route", "zio-http endpoint", "agregar endpoint".
version: 2.0.0
---

# Add an API endpoint

Read `docs/api.adoc` first for the full picture — boot order, layer composition, phase validation,
the error model. This skill is the procedure for adding one more route to that picture.

## Where things live

There is no `endpoints/` module and no tapir. A route is a zio-http `Endpoint` value, built and
implemented in one place:

- `messages/src/whitelabel/captal/messages/<Name>.scala` — the request/response DTOs. Cross-compiled
  (`messages` for the JVM, `messages-js` for the browser), with their circe `Encoder`/`Decoder`
  **derived**, not hand-written.
- `api/src/whitelabel/captal/api/wire.scala` — the server-side zio-schema `Schema` for every type a
  route sends or receives. A zio-schema `Schema` *is* the codec `Endpoint` serialises with; forgetting
  an entry here is a compile error at the route, not a runtime surprise.
- `api/src/whitelabel/captal/api/routes/<Group>Routes.scala` — the `Endpoint` definition and its
  implementation together. `ApiBase` applies the `/<slug>` prefix while the `Endpoint` is being
  built.

The survey question tree (`QuestionType`, `AnswerValue`, the rule enums, …) is the one exception:
its zio-schema lives in `platform/src/whitelabel/captal/platform/surveySchemas.scala`, shared with
the MCP backoffice that writes the same bytes to `question_rules.rule_config` and
`answers.answer_value`. Don't add another schema for those types in `wire.scala`.

## Steps

### 1. Define the DTOs

In `messages/src/whitelabel/captal/messages/<Name>.scala` (a new file, or add to an existing
`*Request.scala`/`*Response.scala`):

```scala
package whitelabel.captal.messages

import io.circe.{Decoder, Encoder}

final case class FooRequest(value: String, count: Int)

object FooRequest:
  given Encoder[FooRequest] = Encoder.AsObject.derived
  given Decoder[FooRequest] = Decoder.derived
```

An enum response (the "either the resource or a fallback step" shape most routes return) follows
`SurveyResponse`/`AdvertiserSurveyResponse`'s pattern — nest the payload under the case name and
provide a `from` to lift the handler's union-typed result:

```scala
enum FooResponse:
  case Found(data: Foo)
  case Step(data: NextStep)

object FooResponse:
  def from(value: Foo | NextStep): FooResponse = value match
    case f: Foo      => Found(f)
    case n: NextStep => Step(n)

  given Encoder[FooResponse] = Encoder.AsObject.derived
  given Decoder[FooResponse] = Decoder.derived
```

DTOs live in `messages/`, never in `api/`, because the browser needs the same case classes and
`messages-js` cross-compiles this source tree verbatim.

### 2. Add the zio-schema instance

In `api/src/whitelabel/captal/api/wire.scala`:

```scala
given Schema[FooRequest] = DeriveSchema.gen[FooRequest]
given Schema[FooResponse] = DeriveSchema.gen[FooResponse]
```

If `FooResponse` carries a `Phase` or another hand-written type, derive it *after* that type's own
`given` — `wire.scala` is read top to bottom and a derived schema needs its dependencies already in
scope.

### 3. Define the endpoint and its handler

Pick a route class: add to an existing one (`SurveyRoutes`, `VideoRoutes`, …) when the endpoint
belongs to that feature area, or create `api/src/whitelabel/captal/api/routes/FooRoutes.scala` for a
new one. `HealthRoutes` is the smallest real example (no session, no body); `SurveyRoutes` is the
secured one.

```scala
package whitelabel.captal.api.routes

import whitelabel.captal.api.session.SessionCookieConfig
import whitelabel.captal.api.wire.given
import whitelabel.captal.api.{ApiBase, ApiErrors, SessionEndpoint}
import whitelabel.captal.core.application.{Flow, Phase}
import whitelabel.captal.messages.{ApiError, FooRequest, FooResponse}
import zio.*
import zio.http.codec.HeaderCodec
import zio.http.endpoint.Endpoint
import zio.http.{Header, Method, Routes, Status}

object FooRoutes:
  type CreateFooFlowType = Flow.Aux[Task, CreateFooCommand, NextStep]
  type FullEnv = SessionContext & SessionService & CreateFooFlowType

  val layer: ZLayer[SessionCookieConfig & ApiBase, Nothing, FooRoutes] =
    ZLayer.fromFunction(FooRoutes(_, _))

final class FooRoutes(cookieConfig: SessionCookieConfig, base: ApiBase):
  import FooRoutes.*

  private def asApiError[A](result: Task[A]): IO[ApiError, A] = result.catchAllCause: cause =>
    ApiErrors.failWith(
      cause.failureOrCause match
        case Left(error)  => error
        case Right(defect) => new Exception(s"Defect: ${defect.prettyPrint}"))

  private val createFoo = Endpoint(base(Method.POST / "api" / "foo"))
    .header(HeaderCodec.cookie.optional)
    .in[FooRequest]
    .out[FooResponse]
    .outError[ApiError](Status.BadRequest)

  val routes: Routes[FullEnv, Nothing] = Routes(
    createFoo.implement: (cookie, request) =>
      for
        session <- SessionEndpoint.authorise(
          cookieConfig.valueFrom(cookie),
          SessionEndpoint.OnMissing.Fail,
          Seq(Phase.IdentificationQuestion))
        flow    <- ZIO.service[CreateFooFlowType]
        now     <- Clock.instant
        result  <- asApiError(flow.execute(CreateFooCommand(request.value, now)).map(FooResponse.from))
      yield result
  )
end FooRoutes
```

Notes on what's in this skeleton:

- **`base(Method.POST / "api" / "foo")`** — `ApiBase.apply` nests the `/<slug>` prefix into the
  `RoutePattern` while the `Endpoint` is still being built. There is no step where the prefix is
  applied afterward; it has to be here, in the literal path.
- **`.header(HeaderCodec.cookie.optional)`** — the whole `Cookie` header, not a single named cookie.
  `cookieConfig.valueFrom(cookie)` is what picks this deployment's session cookie out of it by name.
- **`.outError[ApiError](Status.BadRequest)`** — every route in this module answers errors as a 400
  carrying an `ApiError` variant. There is no other status code to reach for.
- **`asApiError`** is duplicated per route class today (`SurveyRoutes`, `VideoRoutes`,
  `AdvertiserSurveyRoutes`), not shared — follow that, don't try to deduplicate it as part of adding
  one endpoint.

### 4. Decide: secured, public, or session-creating

- **Secured** (needs an existing session): `SessionEndpoint.authorise(cookie, OnMissing.Fail, Seq(Phase.X, ...))`. An empty `Seq` means any phase.
- **Public** (no session at all): skip `SessionEndpoint` entirely — see `HealthRoutes`, which has no
  session and no error output (`Routes[Any, Nothing]`).
- **Session-creating** (`/api/status` is the only one today): `SessionEndpoint.resolveSession(cookie, OnMissing.Create(...))`, then `SessionContext.set(session)` by hand, because this route also has to decide the `Set-Cookie` response header.

### 5. Wire into `AppLayers.scala` and `HttpApp.scala`

Layer wiring and route mounting live apart from where the command handler and flow are defined —
see skill `add-command` for that half. For the route class itself:

- Add `FooRoutes.layer` to the `ZLayer.makeSome[...]` call in
  `api/src/whitelabel/captal/api/AppLayers.scala`, and add `FooRoutes` plus its `Flow.Aux`
  refinement to the `FullEnv` type alias in that same file.
- Add `fooRoutes.routes` to the concatenation in `api/src/whitelabel/captal/api/HttpApp.scala`'s
  `apiRoutes`, and resolve `FooRoutes` as a service in `api/src/whitelabel/captal/api/Main.scala`'s
  `program` so it can be passed through. `Main` is only the startup sequence — resolve settings,
  start the scheduler, log, assemble, serve — it does not itself hold any layer definitions.

### 6. Test

In `api/test/src/whitelabel/captal/api/suites/<Group>Suite.scala`:

```scala
package whitelabel.captal.api.suites

import whitelabel.captal.api.TestHelpers.*
import zio.test.*

object FooSuite:
  val suite: Spec[TestEnv, Throwable] =
    zio.test.suite("Foo")(
      test("create foo persists and returns the new id"):
        for
          backend <- testBackend
          cookie  <- createSession(backend)
          resp    <- basicRequest
            .post(uri"http://test/api/foo")
            .cookie("captal_session", cookie)
            .body("""{"value":"x","count":1}""")
            .contentType("application/json")
            .response(asStringAlways)
            .send(backend)
        yield assertTrue(resp.code.isSuccess)
    )
end FooSuite
```

Add the new routes to `TestHelpers.testBackend`'s concatenation if the suite needs them and they
aren't already mounted there, and add `FooSuite.suite` to the list in
`api/test/src/whitelabel/captal/api/E2ETests.scala`. The test backend is `TestHelpers.testBackend` —
it runs the production `Routes` value through an sttp stub, under the server's own
`HttpApp.codecConfig`, not through any tapir stub interpreter (there is no tapir anywhere in the
test stack either).

### 7. Document

If the endpoint changes the request/response table or the guest journey, update `docs/api.adoc`
(the endpoint table) and `docs/event-sourcing.adoc` (the guest-journey table) rather than restating
it here.

## Common patterns

| Want | Use |
|---|---|
| Read JSON body | `.in[MyRequest]` |
| Path param | `Endpoint(base(Method.GET / "api" / "foo" / PathCodec.string("id")))` — see `LocaleRoutes.getI18n` |
| Header | `.header(HeaderCodec.name[String]("X-Foo").optional)` |
| Set cookie on response | `.outHeader(HeaderCodec.setCookie.optional)`, return `(body, Option[Header.SetCookie])` |
| Return error | `.outError[ApiError](Status.BadRequest)` + `asApiError(...)` or `ZIO.fail(ApiError.X)` |
| Validate phase | `SessionEndpoint.authorise(cookie, OnMissing.Fail, Seq(Phase.X, Phase.Y))` |
| Session required or created on missing | `OnMissing.Create(userAgent, locale, portalParams)` / `OnMissing.CreateForUser(...)` |

## Anti-patterns

- ❌ Defining DTOs inside `api/` — they won't cross-compile to the JS client. They belong in `messages/`.
- ❌ Adding a type to `messages/` and forgetting its `given Schema[...]` in `api/wire.scala` — the
  route fails to compile with a missing-instance error that names the type, not the missing file.
- ❌ Calling `Quill.run(...)` directly from a route — go through a service / repository / command
  handler.
- ❌ Forgetting to add the route to `FullEnv` in `AppLayers.scala` — compilation fails with a cryptic
  widen error at `HttpApp`'s route concatenation.
- ❌ Nesting the assembled `Routes` with the slug instead of applying `ApiBase` to each `Endpoint`
  while it's built — the `RoutePattern` gets prefixed but the endpoint's own codec doesn't, so a
  prefixed request matches the route and then fails to decode.
- ❌ Reaching for a tapir type (`PublicEndpoint`, `jsonBody`, `ZServerEndpoint`) — none of it exists
  in this build any more. The equivalents are `zio.http.endpoint.Endpoint`, `.in[A]`/`.out[A]`, and
  `Routes(endpoint.implement(...))`.
