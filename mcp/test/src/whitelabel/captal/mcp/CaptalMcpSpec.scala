package whitelabel.captal.mcp

import whitelabel.captal.core.i18n.{I18n, I18nSchema}
import whitelabel.captal.mcp.access.Auth
import whitelabel.captal.mcp.apps.UploadUiSource
import whitelabel.captal.mcp.protocol.{McpSchema, Skills}
import whitelabel.captal.mcp.tools.{
  AdvertiserTools,
  AnswerFormat,
  AssetTools,
  I18nTools,
  LocalizedText,
  LocationTools,
  SurveyTools,
  VideoTools
}
import zio.test.*

/** Asserts against the resources as a host would see them — definition plus body — so a packaging
  * mistake (skills missing from the jar, a wrong resource path) fails here rather than at runtime.
  */
object CaptalMcpSpec extends ZIOSpecDefault:
  /** A bare count rather than a list of slugs: it catches a packaging mistake, not staleness. */
  private val ExpectedSkillCount = 12

  def spec =
    suite("CaptalMcp")(
      suite("skills")(
        test("every bundled skill is loaded from the packaged resources"):
          assertTrue(Skills.all.size == ExpectedSkillCount)
        ,
        test("frontmatter becomes the resource name and description"):
          val survey = Skills.bySlug.get("add-survey")
          assertTrue(
            survey.isDefined,
            survey.get.name == "add-survey",
            survey.get.description.contains("identification surveys"),
            // The frontmatter must not leak into the served body.
            !survey.get.body.startsWith("---"),
            survey.get.body.nonEmpty
          )
        ,
        test("no skill loses its body or its description"):
          val empty = Skills.all.filter(s => s.body.isBlank || s.description.isBlank)
          assertTrue(empty.isEmpty)
        ,
        test("uris follow the skill:// convention and are unique"):
          val uris = Skills.all.map(_.uri)
          assertTrue(
            uris.forall(u => u.startsWith("skill://") && u.endsWith("/SKILL.md")),
            uris.distinct.size == uris.size)
      ),
      suite("frontmatter parsing")(
        test("a document without frontmatter is returned untouched"):
          val (fm, body) = Skills.splitFrontmatter("# Title\n\ntext")
          assertTrue(fm.isEmpty, body == "# Title\n\ntext")
        ,
        test("an unterminated frontmatter block is treated as body, not truncated"):
          val raw = "---\nname: x\nstill going"
          val (fm, body) = Skills.splitFrontmatter(raw)
          assertTrue(fm.isEmpty, body == raw)
        ,
        test("values containing colons survive"):
          val (fm, _) = Skills.splitFrontmatter("---\ndescription: Use this: carefully\n---\nbody")
          assertTrue(fm.get("description").contains("Use this: carefully"))
      ),
      suite("resources")(
        test("one resource per skill plus the derived i18n key list"):
          assertTrue(CaptalMcp.resources.size == ExpectedSkillCount + 1)
        ,
        test("required-keys serves the derived keys, each with its description"):
          val handler =
            CaptalMcp.resources.find(_.definition.uri == CaptalMcp.RequiredI18nKeysUri).get
          for contents <- handler.read(CaptalMcp.RequiredI18nKeysUri)
          yield
            val lines = contents.head.text.get.linesIterator.toList
            val served = lines.map(_.takeWhile(_ != ':'))
            assertTrue(
              served == I18nSchema.keys[I18n],
              served.size == 39,
              served.contains("welcome.steps.step1"),
              // Every line must carry a description, not just the bare key.
              lines.forall(l => l.split(": ", 2).lift(1).exists(_.trim.nonEmpty))
            )
        ,
        test("a skill resource serves its own body"):
          val handler = CaptalMcp.resources.find(_.definition.uri.contains("add-survey")).get
          for contents <- handler.read(handler.definition.uri)
          yield assertTrue(
            contents.size == 1,
            contents.head.mimeType.contains("text/markdown"),
            contents.head.text.exists(_.nonEmpty))
      ),
      suite("instructions")(
        test("point at the tools rather than inlining anything"):
          val bodies = Skills.all.map(_.body)
          assertTrue(
            // Loaded in every conversation before any schema, so it stays an index.
            bodies.forall(b => !CaptalMcp.instructions.contains(b)),
            CaptalMcp.instructions.length < 2000,
            // The catalogue lives in read_skill's description.
            CaptalMcp.instructions.contains("read_skill"),
            CaptalMcp.instructions.contains("get_location_readiness")
          )
        ,
        // A host decides whether a model ever sees a resource, and a prompt is invoked by the
        // operator, so every capability the instructions name has to be one the model can reach.
        test("never instruct the model to read a resource or run a prompt"):
          val text = CaptalMcp.instructions
          assertTrue(
            !text.contains("Read the relevant `skill://`"),
            !text.contains("Re-run it as you go"),
            Skills.all.forall(s => !text.contains(s"Read ${s.uri}")))
      ),
      suite("tools")(
        test("the i18n tools are registered with stable names"):
          assertTrue(
            I18nTools.all.map(_.name.value).sorted ==
              List("get_i18n_schema", "get_i18n_status", "remove_translation", "set_translations"))
        ,
        test("every tool carries a description and an object input schema"):
          val defs = I18nTools.all.map(_.definition)
          assertTrue(
            defs.forall(_.description.exists(_.length > 20)),
            defs.forall(_.inputSchema.get("type").contains(zio.json.ast.Json.Str("object"))))
        ,
        test("set_translations declares the fields an operator must supply"):
          val props = I18nTools
            .setTranslations
            .definition
            .inputSchema
            .get("properties")
            .collect { case o: zio.json.ast.Json.Obj =>
              o
            }
          assertTrue(
            props.isDefined,
            List("slug", "locale", "entries").forall(f => props.get.get(f).isDefined))
        ,
        test("the write tool points the reader at the derived key list"):
          // What a tool tells its caller to read next has to be another tool, since a resource is
          // reachable only if the host passes it on.
          assertTrue(
            I18nTools.setTranslations.definition.description.exists(_.contains("get_i18n_schema")),
            I18nTools
              .all
              .flatMap(_.definition.description)
              .forall(d => !d.contains("i18n://") && !d.contains("skill://"))
          )
      ),
      test("the asset tools declare what they need"):
        // Not exercised against S3 here: that would need a bucket and a distribution.
        assertTrue(
          List(AssetTools.setStyles, AssetTools.setIcon).forall(
            _.requiredScopes == Set(Auth.ContentWrite)),
          List(AssetTools.getStyles, AssetTools.getIcon, AssetTools.cssSchema).forall(
            _.requiredScopes.isEmpty),
          AssetTools.setStyles.definition.inputSchema.toString.contains("get_css_schema"),
          AssetTools.setStyles.definition.inputSchema.toString.contains("read_skill"),
          AssetTools.setStyles.definition.description.exists(_.contains("whole file"))
        )
      ,
      test("every published question variant names the tag the decoder demands"):
        // The document and the decoder are two readings of one `Schema`; the expected tags come
        // from the codec rather than from a transcribed list.
        def variantsOf(schema: zio.json.ast.Json): List[zio.json.ast.Json] =
          schema match
            case zio.json.ast.Json.Obj(fields) =>
              fields
                .toList
                .flatMap:
                  case ("oneOf", zio.json.ast.Json.Arr(variants)) =>
                    variants.toList
                  case (_, nested) =>
                    variantsOf(nested)
            case zio.json.ast.Json.Arr(items) =>
              items.toList.flatMap(variantsOf)
            case _ =>
              Nil

        def tagsOf(handler: com.jamesward.ziohttp.mcp.McpToolHandlerR[?]): List[String] =
          variantsOf(handler.definition.inputSchema).flatMap:
            case zio.json.ast.Json.Obj(fields) =>
              fields
                .toList
                .collect { case ("properties", zio.json.ast.Json.Obj(props)) =>
                  props
                }
                .flatMap(_.toList)
                .collect { case ("kind", zio.json.ast.Json.Obj(tag)) =>
                  tag
                }
                .flatMap(_.toList)
                .collect { case ("enum", zio.json.ast.Json.Arr(values)) =>
                  values
                }
                .flatMap(_.toList)
                .collect { case zio.json.ast.Json.Str(value) =>
                  value
                }
            case _ =>
              Nil

        // What the codec puts on the wire for one instance of each case, which is the authority.
        // No `rating`: it still decodes in core, but a new one cannot be asked for.
        val samples = List(
          AnswerFormat.Radio(Nil),
          AnswerFormat.Dropdown(Nil),
          AnswerFormat.Checkbox(Nil, None),
          AnswerFormat.Input(None),
          AnswerFormat.Numeric(None),
          AnswerFormat.Date(None)
        )
        val encoder = zio.schema.codec.JsonCodec.jsonEncoder(AnswerFormat.schema)
        val fromCodec = samples.map: sample =>
          zio
            .json
            .ast
            .Json
            .decoder
            .decodeJson(encoder.encodeJson(sample))
            .toOption
            .flatMap(_.asObject)
            .flatMap(
              _.fields
                .collectFirst { case ("kind", zio.json.ast.Json.Str(v)) =>
                  v
                })
            .getOrElse("<no kind>")

        assertTrue(
          tagsOf(SurveyTools.createQuestion) == fromCodec,
          // The same enum, nested inside an array of questions.
          tagsOf(VideoTools.create) == fromCodec)
      ,
      test("a variant that rejects extra fields says so"):
        // `@rejectExtraFields` has to reach the document and not only the decoder; every
        // AnswerFormat case carries it.
        val rendered =
          (SurveyTools.createQuestion.definition.inputSchema: zio.json.ast.Json).toString
        val needle = """"additionalProperties":false"""
        assertTrue(rendered.split(needle).length - 1 == AnswerFormat.caseIds.size)
      ,
      test("the published schema keeps every description an operator needs"):
        val rendered =
          (SurveyTools.createQuestion.definition.inputSchema: zio.json.ast.Json).toString
        assertTrue(
          rendered.contains("Ask the operator for this; nothing else can supply it."),
          rendered.contains("Only for questions in a location survey; rejected elsewhere."),
          rendered.contains("Omit for email and url."),
          // And not the generated filler.
          !rendered.contains("a list of LocalizedText")
        )
      ,
      test("output schemas come from the same renderer as the inputs"):
        // Every published outputSchema must be what the shared renderer produces.
        val cases = List(
          SurveyTools.createQuestion ->
            McpSchema.fromSchema(summon[zio.schema.Schema[SurveyTools.CreateQuestionResponse]]),
          LocationTools.create ->
            McpSchema.fromSchema(summon[zio.schema.Schema[LocationTools.CreateResponse]]),
          AdvertiserTools.list ->
            McpSchema.fromSchema(summon[zio.schema.Schema[AdvertiserTools.ListResponse]])
        )
        assertTrue(
          cases.forall((handler, expected) => handler.definition.outputSchema.contains(expected)))
      ,
      test("a String-returning tool still answers as text, not wrapped"):
        // The generic given must not out-compete the SDK's `McpOutput[String]`, which returns no
        // outputSchema and plain text content.
        val plain = com
          .jamesward
          .ziohttp
          .mcp
          .McpTool("probe")
          .description("probe")
          .handle((_: Unit) => zio.ZIO.succeed("hello"))
        assertTrue(plain.definition.outputSchema.isEmpty)
      ,
      test("every published property carries a description"):
        val tools: List[com.jamesward.ziohttp.mcp.McpToolHandlerR[?]] =
          LocationTools.all ++ I18nTools.all ++ AdvertiserTools.all ++ SurveyTools.all ++
            AssetTools.all ++ VideoTools.all

        def undocumented(node: zio.json.ast.Json, path: String): List[String] =
          node match
            case zio.json.ast.Json.Obj(fields) =>
              val here = fields
                .collectFirst { case ("properties", zio.json.ast.Json.Obj(props)) =>
                  props
                }
                .toList
                .flatMap(_.toList)
                .flatMap: (name, sub) =>
                  val documented = sub.asObject.exists(_.fields.exists(_._1 == "description"))
                  // `kind` is synthesised to carry the discriminator; there is no field in the
                  // Scala type to annotate.
                  val exempt = name == "kind"
                  (
                    if documented || exempt then
                      Nil
                    else
                      List(s"$path.$name")
                  ) ++ undocumented(sub, s"$path.$name")
              val nested = fields.flatMap:
                case ("properties", _) =>
                  Nil
                case (key, value) =>
                  undocumented(value, s"$path.$key")
              here ++ nested.toList
            case zio.json.ast.Json.Arr(items) =>
              items.toList.zipWithIndex.flatMap((item, i) => undocumented(item, s"$path[$i]"))
            case _ =>
              Nil

        val gaps = tools.flatMap: tool =>
          val name = tool.name.toString
          undocumented(tool.definition.inputSchema, s"$name.in") ++
            tool.definition.outputSchema.toList.flatMap(undocumented(_, s"$name.out"))

        assertTrue(gaps == Nil)
      ,
      test("both upload tools name the page that renders them"):
        def metaOf(tool: com.jamesward.ziohttp.mcp.McpToolHandlerR[?]) = tool
          .definition
          .meta
          .map(m => (m: zio.json.ast.Json).toString)
          .getOrElse("")
        assertTrue(
          // Both, because a View is instantiated only by calling a tool that carries this; a
          // resource a user opens is never rendered.
          metaOf(VideoTools.create).contains("ui://upload-video"),
          metaOf(VideoTools.resume).contains("ui://upload-video")
        )
      ,
      test("the upload page declares the origin it has to reach"):
        // The host defaults `connect-src` to 'self', and the policy has to sit on the resource:
        // `McpAppsToolMeta` has no field for one, so the host never reads it off the tool.
        val origin = "https://captal-assets.s3.us-east-1.amazonaws.com"
        // Distinct from the PUT origin on purpose: `connect-src` for the bucket, `script-src` for
        // the CDN, so one value for both could not tell a swap from a correct policy.
        val scriptOrigin = "https://d111.cloudfront.net"
        val source = UploadUiSource(origin, scriptOrigin)
        def metaOf(definition: com.jamesward.ziohttp.mcp.ResourceDefinition) = definition
          .meta
          .map(m => (m: zio.json.ast.Json).toString)
          .getOrElse("")
        val bare = metaOf(source.bareDefinition)
        val perVideo = metaOf(source.pageDefinition("v1", "A video", "Upload it"))
        assertTrue(
          bare.contains("connectDomains"),
          bare.contains("captal-assets.s3.us-east-1.amazonaws.com"),
          // Plain `text/html` describes a document to show; the profile says the page is an app to
          // render.
          source.bareDefinition.mimeType.contains("text/html;profile=mcp-app"),
          // The per-video pages, listed from `EntityResources`, carry the same policy as the bare
          // one.
          perVideo.contains("connectDomains"),
          perVideo.contains("captal-assets.s3.us-east-1.amazonaws.com")
        )
    )
end CaptalMcpSpec
