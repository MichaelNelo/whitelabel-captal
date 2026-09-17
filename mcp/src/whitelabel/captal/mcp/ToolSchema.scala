package whitelabel.captal.mcp

import com.jamesward.ziohttp.mcp.*
import zio.Chunk
import zio.json.ast.Json

/** Decorators over `McpToolHandlerR`.
  *
  * The builder does not expose `_meta`, but the trait is plain and `definition` is override-able,
  * so a decorator reaches it without forking the library — and keeps the typed derivation intact,
  * including the MRTR signal the SDK re-throws from `handleWithContext`.
  *
  * This used to also repair the generated input schema. It no longer needs to: [[McpSchema]]
  * replaces the renderer outright with one that reads the same annotations the decoder does.
  */
object ToolSchema:
  extension [R](handler: McpToolHandlerR[R])
    def withDefinition(f: ToolDefinition => ToolDefinition): McpToolHandlerR[R] =
      new McpToolHandlerR[R]:
        def name: ToolName = handler.name
        def definition: ToolDefinition = f(handler.definition)
        override def requiredScopes: Set[auth.OauthScope] = handler.requiredScopes
        def call(args: Option[Json.Obj]) = handler.call(args)
        override def callWithContext(args: Option[Json.Obj], ctx: McpToolContext) = handler
          .callWithContext(args, ctx)

    /** Sets `_meta` on the tool definition. The builder does not expose it, but `McpToolHandlerR`
      * is a plain trait with an override-able `definition`, so a decorator reaches it without
      * forking the library — and keeps the typed derivation, including the MRTR signal the SDK
      * re-throws from `handleWithContext`.
      */
    def withMeta(meta: Json.Obj): McpToolHandlerR[R] = withDefinition(_.copy(meta = Some(meta)))

    /** Points the tool at an MCP App. The host reads the `ui://` resource and renders it. */
    def withUi(resourceUri: String): McpToolHandlerR[R] = withMeta(
      Json.Obj(Chunk("ui" -> Json.Obj(Chunk("resourceUri" -> Json.Str(resourceUri))))))

  end extension
end ToolSchema
