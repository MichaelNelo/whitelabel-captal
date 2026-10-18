package whitelabel.captal.mcp.protocol

import com.jamesward.ziohttp.mcp.{CallToolResult, McpInput, McpOutput, ToolContent}
import zio.Chunk
import zio.http.endpoint.openapi.JsonSchema
import zio.json.*
import zio.json.ast.Json
import zio.schema.Schema
import zio.schema.codec.JsonCodec as SchemaJsonCodec

/** Publishes the input schema the decoder actually enforces.
  *
  * The SDK derives both halves of a tool's contract from one `Schema[A]`:
  * `JsonSchemaGen.fromSchema` renders the document that goes out in `tools/list`, and
  * `JsonCodec.jsonDecoder` builds the decoder that runs. The two read different subsets of the same
  * tree. `JsonSchemaGen` reads `@description` and nothing else — so `@discriminatorName` and
  * `@rejectExtraFields` are enforced at runtime and invisible in the document, and a caller
  * following the schema is rejected for a rule it was never told about.
  *
  * The cost was not theoretical. `create_advertiser_video` published seven question variants of
  * which only four were distinguishable — `radio` and `dropdown` identical, `rating`, `numeric` and
  * `date` identical — with nothing naming the tag. Two operators' agents stopped there, both
  * guessing at the field name, both wrong.
  *
  * zio-http's OpenAPI generator reads the same tree and honours all three. It is already on the
  * classpath, so this replaces the rendering half and leaves the decoder exactly as the SDK built
  * it — the two now come from one reading of the schema rather than two.
  *
  * Importing this given shadows the SDK's, which is deliberate: the previous approach patched the
  * generated JSON per tool, and was twice forgotten at a call site. An import that is missing from
  * a file is visible in a way that an uncalled decorator is not.
  */
object McpSchema:

  /** `Inline` rather than `Reference`: a `$ref` points into an OpenAPI document that does not exist
    * here, and a host reading `tools/list` has nothing to resolve it against.
    *
    * The spec half says which document a `$ref` would point into, and names the right one for a
    * reader that speaks JSON Schema rather than OpenAPI — `#/$defs/` over `#/components/schemas/`.
    * `Inline` emits no refs, so it changes nothing today; it is the honest declaration if this ever
    * stops being inline. Taking the pair at all is what zio-http 3.8 deprecated the bare
    * `SchemaStyle` in favour of, and 0.10.0 moved this module from 3.0.1 to 3.11.6.
    */
  private val Style = JsonSchema.SchemaRef(
    JsonSchema.SchemaSpec.JsonSchema,
    JsonSchema.SchemaStyle.Inline)

  private[mcp] def fromSchema[A](schema: Schema[A]): Json.Obj = JsonSchema
    .fromZSchema(schema, Style)
    .toJson
    .fromJson[Json]
    .toOption
    .flatMap(_.asObject)
    // fromZSchema is total and toJson round-trips its own codec, so this is unreachable; failing
    // loudly beats publishing an empty schema that every caller would satisfy.
    .getOrElse(
      throw new IllegalStateException(
        s"Could not read back the JSON Schema generated for $schema."))
    .pipe(stripOpenApiDiscriminator)

  /** Drops the OpenAPI `discriminator` node, whose `mapping` names `#/components/schemas/…` entries
    * that an inline document does not contain. It is not a JSON Schema keyword, and the `oneOf`
    * variants already carry the tag as a one-element `enum`, which says the same thing in the
    * vocabulary a host actually speaks. Removing a key cannot misdescribe the contract; inventing
    * one can, which is what the previous patch did.
    *
    * Only stripped next to a `oneOf`, so a field a caller happens to have named `discriminator`
    * survives.
    */
  private def stripOpenApiDiscriminator(json: Json.Obj): Json.Obj =
    def go(value: Json): Json =
      value match
        case obj: Json.Obj =>
          val isEnumNode = obj.fields.exists(_._1 == "oneOf")
          Json.Obj(
            obj
              .fields
              .filterNot((name, _) => isEnumNode && name == "discriminator")
              .map((name, nested) => name -> go(nested)))
        case Json.Arr(items) =>
          Json.Arr(items.map(go))
        case other =>
          other
    go(json).asInstanceOf[Json.Obj]

  extension [A](self: A)
    private def pipe[B](f: A => B): B = f(self)

  /** Shadows `McpInput.given`. The decoder is the SDK's, built the same way from the same schema;
    * only the published document changes.
    */
  given derivedInput[A](using schema: Schema[A]): McpInput[A] with
    val jsonSchema: Json.Obj = fromSchema(schema)
    private val decoder = SchemaJsonCodec.jsonDecoder(schema)
    def decode(args: Option[Json.Obj]): Either[String, A] = decoder.decodeJson(
      args.getOrElse(Json.Obj(Chunk.empty)).toJson)

  /** The spec constrains `outputSchema` to an object and `structuredContent` to a JSON object, so a
    * value that is not object-shaped is nested under one property. Kept identical to the SDK's
    * handling, key included, because the wrapping is a protocol requirement rather than a choice —
    * only the rendering of the value's own schema changes.
    */
  private val WrapperKey = "result"

  /** Shadows `McpOutput.given` for the same reason as the input side.
    *
    * No tool returns a discriminated enum today, so nothing is misdescribed right now. It is the
    * same asymmetry all the same: the encoder honours annotations the renderer drops, and the first
    * tool to return one would reintroduce the bug that cost this migration two sessions.
    */
  given derivedOutput[A](using schema: Schema[A]): McpOutput[A] with
    private val valueSchema = fromSchema(schema)
    private val isObject = valueSchema.get("type").flatMap(_.asString).contains("object")

    val outputSchema: Option[Json.Obj] =
      if isObject then
        Some(valueSchema)
      else
        Some(
          Json.Obj(
            Chunk(
              "type"       -> Json.Str("object"),
              "properties" -> Json.Obj(Chunk(WrapperKey -> (valueSchema: Json))),
              "required"   -> Json.Arr(Json.Str(WrapperKey)))))

    private val encoder = SchemaJsonCodec.jsonEncoder(schema)

    def toResult(output: A): CallToolResult =
      val encoded = encoder.encodeJson(output, None).toString.fromJson[Json].getOrElse(Json.Null)
      val structured: Json.Obj =
        encoded match
          case obj: Json.Obj =>
            obj
          case other =>
            Json.Obj(Chunk(WrapperKey -> other))
      // Text content mirrors the structured content, which is what the spec recommends for hosts
      // that predate structured output.
      CallToolResult(
        content = Chunk(ToolContent.text(structured.toJson)),
        structuredContent = Some(structured))
  end derivedOutput
end McpSchema
