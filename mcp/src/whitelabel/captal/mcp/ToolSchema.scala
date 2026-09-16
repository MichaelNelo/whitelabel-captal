package whitelabel.captal.mcp

import com.jamesward.ziohttp.mcp.*
import zio.Chunk
import zio.json.ast.Json

/** Repairs the generated input schema so it describes what the decoder actually accepts.
  *
  * `JsonSchemaGen` ignores zio-schema's `@discriminatorName`: for an enum with parameterised cases
  * it emits a bare `oneOf` of the case shapes, while the codec expects each variant to carry the
  * discriminator field. A caller following the schema sends the variant without it and is told
  * "missing subtype" — the schema is not merely incomplete, it is wrong.
  *
  * `McpToolHandlerR` exposes `definition`, so the fix is a decorator rather than a fork: the typed
  * derivation still produces the shapes, and this puts back the one property it drops.
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

    /** Adds `discriminator: <caseId>` as a required const to every variant of the `oneOf` under
      * `properties.<field>`. The variants come out in declaration order, so `caseIds` is zipped
      * positionally against them.
      */
    def withDiscriminator(
        field: String,
        discriminator: String,
        caseIds: List[String]): McpToolHandlerR[R] = withDefinition: definition =>
      definition.copy(inputSchema =
        patchProperty(definition.inputSchema, field)(variantSchema =>
          patchOneOf(variantSchema, discriminator, caseIds)))

    /** The same repair, one level down: `properties.<arrayField>.items.properties.<field>`.
      *
      * A tool that takes a list of questions rather than one carries the identical `oneOf`, and
      * leaving it unpatched is worse there than at the top level — four of the seven variants have
      * no properties beyond `rules`, so they are not merely undocumented but indistinguishable from
      * each other. Two separate agents have already been stopped by it, both reporting the same
      * "missing subtype" and both guessing at the field name.
      */
    def withDiscriminatorInArray(
        arrayField: String,
        field: String,
        discriminator: String,
        caseIds: List[String]): McpToolHandlerR[R] = withDefinition: definition =>
      definition.copy(inputSchema =
        patchProperty(definition.inputSchema, arrayField): arraySchema =>
          mapField(arraySchema, "items"): item =>
            patchProperty(item, field)(variantSchema =>
              patchOneOf(variantSchema, discriminator, caseIds)))
  end extension

  private def patchProperty(schema: Json.Obj, field: String)(f: Json.Obj => Json.Obj): Json.Obj =
    mapField(schema, "properties"): properties =>
      mapField(properties, field)(f)

  private def patchOneOf(schema: Json.Obj, discriminator: String, caseIds: List[String]): Json.Obj =
    schema
      .fields
      .collectFirst { case ("oneOf", Json.Arr(variants)) =>
        variants
      } match
      case Some(variants) if variants.length == caseIds.length =>
        val patched = variants
          .zip(Chunk.fromIterable(caseIds))
          .map: (variant, caseId) =>
            variant match
              case obj: Json.Obj =>
                addConst(obj, discriminator, caseId): Json
              case other =>
                other
        Json.Obj(
          schema
            .fields
            .map((k, v) =>
              if k == "oneOf" then
                k -> Json.Arr(patched)
              else
                k -> v))
      // Mismatched arity means the generator changed shape; leaving the schema untouched is safer
      // than emitting one built on a guess.
      case _ =>
        schema

  private def addConst(variant: Json.Obj, discriminator: String, caseId: String): Json.Obj =
    val withProperty =
      mapField(variant, "properties"): properties =>
        Json.Obj(
          properties.fields :+ (discriminator -> Json.Obj(Chunk("const" -> Json.Str(caseId)))))
    val existing = withProperty
      .fields
      .collectFirst { case ("required", Json.Arr(v)) =>
        v
      }
    val required = Json.Arr(existing.getOrElse(Chunk.empty) :+ Json.Str(discriminator))
    Json.Obj(withProperty.fields.filterNot(_._1 == "required") :+ ("required" -> (required: Json)))

  private def mapField(obj: Json.Obj, key: String)(f: Json.Obj => Json.Obj): Json.Obj = Json.Obj(
    obj
      .fields
      .map: (k, v) =>
        (k, v) match
          case (`key`, inner: Json.Obj) =>
            k -> (f(inner): Json)
          case _ =>
            k -> v)
end ToolSchema
