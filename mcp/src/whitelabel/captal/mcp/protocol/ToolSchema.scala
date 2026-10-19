package whitelabel.captal.mcp.protocol

import com.jamesward.ziohttp.mcp.*
import zio.json.ast.Json
import zio.{Chunk, NonEmptyChunk}

/** Pointing a tool at its MCP App.
  *
  * The CSP is attached to the page in [[UploadUiSource]] and [[EntityResources]], not to the tool.
  */
object ToolSchema:

  /** A `ui://` URI as the SDK's own type.
    *
    * Parsed at the boundary rather than carried as an `Either` through every page: these are built
    * from a constant and sometimes an id, so a failure is a programming error, and one that should
    * stop the server at startup rather than surface as a page that will not open.
    */
  def uiUri(value: String): McpUiUri = McpUiUri
    .parse(value)
    .fold(error => throw new IllegalArgumentException(s"Not a usable ui:// URI: $error"), identity)

  extension (tool: McpTool)
    /** Declares a tool as a read, so a host can stop treating it as an act.
      *
      * The distinction matters more here than it looks. A resource is context, which a host may
      * hand to a model freely; a tool call is an action, which a host asks the operator to approve.
      * This server answers most of its questions through tools — the resources that answer them do
      * not reach the model in the hosts these operators use — and without this, asking what a
      * location still needs arrives with the same weight as revoking someone's credentials.
      *
      * `readOnlyHint` is the protocol's word for "this changes nothing". It does not turn a tool
      * into context: the call is still made and still paid for. It is the difference between
      * friction on every read and none.
      *
      * Deliberately left unset on the writes. Absent, `destructiveHint` defaults to true, and the
      * cautious default is the right one for anything not declared otherwise.
      */
    def readOnly: McpTool = tool.annotations(readOnly = OptBool.True)

  extension [R](handler: McpToolHandlerR[R])
    /** Declares which App renders this tool's result.
      *
      * Visible to the model only: these are tools a model calls to open a page, never tools a page
      * calls. See [[visibleTo]] for why that is stated rather than left to the default.
      */
    def withUi(resourceUri: McpUiUri): McpToolHandlerR[R] = McpApps.tool(
      handler,
      McpAppsToolMeta(resourceUri, NonEmptyChunk(McpAppsVisibility.Model)))

    /** Who may call this tool: the model, a rendered App, or both.
      *
      * Worth declaring because the default is both. A page rendered in the host's frame can call
      * any tool that does not say otherwise — so without this, the UniFi credentials form, whose
      * whole purpose is to keep an API token out of the conversation, could call `create_token`.
      * Nothing makes that happen today; the HTML is ours and goes out inlined. It is a permission
      * held by default rather than by decision, which is the kind this codebase has spent a day
      * finding.
      *
      * Written by hand rather than through `McpApps.tool`, whose metadata requires a `resourceUri`
      * — most tools render nothing and still need to say who may call them.
      *
      * Being hand-written is also why every member of the trait has to be forwarded explicitly,
      * including those needed to preserve their defaults. Since [[CaptalMcp]] wraps every tool in
      * this, missing a forward would apply it server-wide.
      */
    def visibleTo(values: McpAppsVisibility*): McpToolHandlerR[R] =
      val visibility: Json = Json.Arr(
        Chunk.fromIterable(values.map(value => Json.Str(value.wire): Json)))
      new McpToolHandlerR[R]:
        def name: ToolName = handler.name
        def definition: ToolDefinition =
          val original = handler.definition
          val ui = original
            .meta
            .flatMap(_.get("ui"))
            .collect { case obj: Json.Obj =>
              obj.fields
            }
            .getOrElse(Chunk.empty)
            .filterNot(_._1 == "visibility")
          val rest = original.meta.map(_.fields).getOrElse(Chunk.empty).filterNot(_._1 == "ui")
          original.copy(meta = Some(
            Json.Obj(rest :+ ("ui" -> (Json.Obj(ui :+ ("visibility" -> visibility)): Json)))))
        override def requiredScopes: Set[auth.OauthScope] = handler.requiredScopes
        override def taskPolicy: TaskPolicy = handler.taskPolicy
        def call(args: Option[Json.Obj]) = handler.call(args)
        override def callWithContext(args: Option[Json.Obj], ctx: McpToolContext) = handler
          .callWithContext(args, ctx)
      end new
    end visibleTo
  end extension
end ToolSchema
