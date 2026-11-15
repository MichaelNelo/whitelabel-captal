package whitelabel.captal.mcp.apps

import whitelabel.captal.platform.aws.AssetStore
import zio.*
import zio.http.{Client, Request, URL}

/** Whether the MCP App pages' module reaches a browser. Here rather than on [[AssetStore]], which
  * `platform` compiles against its own zio-http: this runs on the backoffice's.
  */
object ScriptDelivery:
  /** Fetches `ui/upload.js` at startup and returns a warning when it would not load as a module.
    *
    * A module script is fetched in CORS mode, so the CDN has to answer
    * `Access-Control-Allow-Origin` or the browser blocks it and the page renders inert, with
    * nothing in the console, the tool call or this server's logs.
    *
    * A warning and not a failure: the first deploy of a new environment reaches this before
    * `client.publish` has ever run. Nothing here configures CORS; see docs/mcp-iam.adoc.
    */
  def verify(store: AssetStore): ZIO[Client, Nothing, Option[String]] =
    val target = store.scriptUrl("upload.js")
    val probe =
      for
        url <- ZIO.fromEither(URL.decode(target)).mapError(e => new RuntimeException(e.getMessage))
        // An `Origin` header is required: without one the response carries no CORS headers whether
        // or not any are configured. Any value distinguishes a configured `*` from silence.
        request = Request.get(url).addHeader("Origin", "https://probe.invalid")
        response <- ZIO.serviceWithZIO[Client](_.batched(request))
        // Drained rather than ignored, so the client does not hold the connection.
        _ <- response.body.asString
      yield (response.status.code, response.rawHeader("access-control-allow-origin"))
    probe
      // Bounded: this is in the startup path, so a CDN that hangs must not stop the server from
      // serving.
      .timeoutFail(new RuntimeException("timed out after 5s"))(5.seconds)
      .fold(
        e => Some(s"could not be reached ($target): ${e.getMessage}"),
        (status, allowOrigin) =>
          if status == 404 then
            Some(s"is not published yet ($target). Run `mill client.publish`.")
          else if status != 200 then
            Some(s"answered HTTP $status ($target).")
          else if allowOrigin.isEmpty then
            Some(
              s"is served but without Access-Control-Allow-Origin ($target), so a browser will block it as a module and the page will render inert. See docs/mcp-iam.adoc.")
          else
            None
      )
  end verify
end ScriptDelivery
