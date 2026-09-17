package whitelabel.captal.mcp

import com.jamesward.ziohttp.mcp.McpInput
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
    */
  private val Style = JsonSchema.SchemaStyle.Inline

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
  given derived[A](using schema: Schema[A]): McpInput[A] with
    val jsonSchema: Json.Obj = fromSchema(schema)
    private val decoder = SchemaJsonCodec.jsonDecoder(schema)
    def decode(args: Option[Json.Obj]): Either[String, A] = decoder.decodeJson(
      args.getOrElse(Json.Obj(Chunk.empty)).toJson)
end McpSchema
