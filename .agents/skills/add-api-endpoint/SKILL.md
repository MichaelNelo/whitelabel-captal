---
name: add-api-endpoint
description: Use when adding a new HTTP endpoint to the captal API. Covers Tapir definition, secured vs public, server logic, layer wiring, and tests. Triggers on "add endpoint", "new api route", "tapir endpoint", "agregar endpoint".
version: 1.0.0
---

# Add an API endpoint

## Two-file structure

The codebase splits endpoint **contracts** (cross-compiled, used by API server + future clients) from **server implementations**:

- `endpoints/src/whitelabel/captal/endpoints/<Group>Endpoints.scala` — Tapir `PublicEndpoint[...]` values, JSON codecs via `circe`.
- `api/src/whitelabel/captal/api/<Group>Routes.scala` — final class taking `SessionEndpoint` (and possibly `SessionCookieConfig`, `UserCookieConfig`, etc.) that produces `ZServerEndpoint`s by attaching `serverLogic`.

## Steps

### 1. Define request/response DTOs

In `endpoints/src/.../<Group>Endpoints.scala` (or a sibling `<Name>Request.scala` / `<Name>Response.scala`):

```scala
final case class FooRequest(value: String, count: Int)
object FooRequest:
  given Encoder[FooRequest] = deriveEncoder
  given Decoder[FooRequest] = deriveDecoder
  given Schema[FooRequest] = Schema.derived
```

DTOs are cross-compiled (JVM + JS) so they live in `endpoints/`, not `api/`.

### 2. Define the Tapir endpoint

Same file:

```scala
val createFoo: PublicEndpoint[FooRequest, ApiError, FooResponse, Any] = endpoint
  .post
  .in("api" / "foo")
  .in(jsonBody[FooRequest])
  .out(jsonBody[FooResponse])
  .errorOut(jsonBody[ApiError])
  .description("Create a foo")
```

**Note**: these `val`s are not actually consumed by the runtime today (the routes re-declare via `sessionEndpoint.secured(...).post.in(...)`). They exist for OpenAPI/Swagger generation. If you only need the runtime, you can skip this `val` and inline at the route — but conventionally we keep it for docs.

### 3. Pick a route class

If your endpoint needs a session: add to `api/<Existing>Routes.scala` (e.g. `SurveyRoutes`, `VideoRoutes`).

If it's a new feature area: create `api/<Group>Routes.scala`:

```scala
final class FooRoutes(sessionEndpoint: SessionEndpoint):
  import FooRoutes.*

  val createFooRoute: ZServerEndpoint[SessionContext & SessionService & FooService, Any] =
    sessionEndpoint
      .secured(
        onMissingSession = SessionEndpoint.OnMissing.Fail,
        allowedPhases = Seq(Phase.IdentificationQuestion))
      .post
      .in("api" / "foo")
      .in(jsonBody[FooRequest])
      .out(jsonBody[FooResponse])
      .serverLogic: session =>
        request =>
          ZIO
            .serviceWithZIO[FooService](_.create(session.sessionId, request))
            .map(FooResponse.from)
            .mapError(ApiError.fromThrowable)

  type FullEnv = SessionContext & SessionService & FooService
  def routes: List[ZServerEndpoint[FullEnv, Any]] = List(createFooRoute.widen[FullEnv])

object FooRoutes:
  val layer: ZLayer[SessionEndpoint, Nothing, FooRoutes] = ZLayer.fromFunction(FooRoutes(_))
```

### 4. Decide: secured vs public

- **Secured** (needs a session): use `sessionEndpoint.secured(onMissingSession = Fail | Create, allowedPhases = Seq(...))`. Phase enforcement happens automatically.
- **Public** (no session): use raw `endpoint.in(...)...zServerLogic(...)`. Examples: `HealthRoutes`, `LocaleEndpoints.listLocales`.
- **Status-like** (creates session if missing): see `SurveyRoutes.statusRoute` — raw endpoint + `SessionEndpoint.OnMissing.Create`.

### 5. Wire in `Main.scala`

- Add `FooService.layer` (and `FooRoutes.layer`) to `appLayers` in `api/Main.scala`.
- Add `FooRoutes` to `FullEnv` type.
- Add to the `endpoints(...)` builder so the routes get mounted:
  ```scala
  fooRoutes.routes.map(_.widen[FullEnv])
  ```

### 6. Test

In `api/test/src/whitelabel/captal/api/suites/<Group>Suite.scala`:

```scala
test("create foo persists and returns the new id") {
  for
    backend <- TestHelpers.testBackend
    session <- TestHelpers.createSession(backend)
    res     <- basicRequest
      .post(uri"http://test/api/foo")
      .cookie("captal_session", session)
      .body("""{"value":"x","count":1}""")
      .response(asStringAlways)
      .send(backend)
  yield assertTrue(res.code.code == 200)
}
```

Add the suite to `MainSpec` if necessary. The `TestHelpers.testBackend` uses `TapirStubInterpreter` — same `Routes.layer` wiring as production.

### 7. Document

If the endpoint is operator-facing or important enough, add a brief note in `AGENTS.md` under the relevant existing section. If it changes a flow covered by a skill, update the skill.

## Common patterns

| Want                                  | Use                                                              |
|---------------------------------------|------------------------------------------------------------------|
| Read JSON body                        | `.in(jsonBody[MyRequest])`                                       |
| Path param                            | `.in("api" / "foo" / path[String]("id"))`                        |
| Query param                           | `.in(query[Option[String]]("name"))`                             |
| Header                                | `.in(header[Option[String]]("X-Foo"))`                           |
| Set cookie on response                | `.out(cookieConfig.tapirOutput.and(jsonBody[T]))`                |
| Return error                          | `.errorOut(jsonBody[ApiError])` + `ZIO.fail(ApiError.X)`         |
| Validate phase                        | `sessionEndpoint.secured(allowedPhases = Seq(Phase.X, Phase.Y))` |
| Require session OR create on missing  | `OnMissing.Create(userAgent, locale, Some(portalParams))`        |

## Anti-patterns

- ❌ Defining DTOs inside `api/` — they won't cross-compile to the JS client.
- ❌ Calling `Quill.run(...)` directly from a route — go through a service / repository.
- ❌ Forgetting to add the route to `FullEnv` — compilation fails with cryptic widen error.
- ❌ `endpoint.in(query[Option[String]]("foo"))` when you actually want a header — pick based on whether the SPA needs the value in the URL bar.
