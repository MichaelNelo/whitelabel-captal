package whitelabel.captal.mcp

import whitelabel.captal.core.i18n.{I18n, I18nSchema}
import zio.test.*

/** Asserts against the resources as a host would see them — definition plus body — rather than
  * against the loader internals, so a packaging mistake (skills missing from the jar, a wrong
  * resource path) fails here rather than at runtime in front of an operator.
  */
object CaptalMcpSpec extends ZIOSpecDefault:
  private val ExpectedSkillCount = 14

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
        test("index every skill without inlining any body"):
          val bodies = Skills.all.map(_.body)
          assertTrue(
            Skills.all.forall(s => CaptalMcp.instructions.contains(s.uri)),
            CaptalMcp.instructions.contains(CaptalMcp.RequiredI18nKeysUri),
            // The whole point of an index: bodies stay behind the resources.
            bodies.forall(b => !CaptalMcp.instructions.contains(b)),
            CaptalMcp.instructions.length < 2000
          )),
      suite("tools")(
        test("the three i18n tools are registered with stable names"):
          assertTrue(
            I18nTools.all.map(_.name.value).sorted ==
              List("get_i18n_status", "remove_translation", "set_translations"))
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
          // Without this the operator has no way to learn which keys are legal, and the tool would
          // reject their input with nowhere to go next.
          assertTrue(
            I18nTools
              .setTranslations
              .definition
              .description
              .exists(_.contains("i18n://required-keys")))
      ),
      test("the asset tools declare what they need"):
        // Not exercised against S3 here: that would need a bucket and a distribution. What is worth
        // pinning is the contract — writes are scoped, reads are not, and both stylesheet tools name
        // the reference an operator has to read before rewriting one.
        assertTrue(
          List(AssetTools.setStyles, AssetTools.setIcon).forall(
            _.requiredScopes == Set(Auth.ContentWrite)),
          List(AssetTools.getStyles, AssetTools.getIcon).forall(_.requiredScopes.isEmpty),
          AssetTools
            .setStyles
            .definition
            .inputSchema
            .toString
            .contains("skill://style-reference/SKILL.md"),
          AssetTools.setStyles.definition.description.exists(_.contains("whole file"))
        )
      ,
      test("every published question variant names the tag the decoder demands"):
        // The document and the decoder are two readings of one `Schema`. The SDK's renderer read
        // fewer annotations than the codec, so the published contract was a strict subset of the
        // enforced one — silently. This asserts the two agree on the discriminator, per variant,
        // by going to the codec for the expected value rather than to a transcribed list.
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
        val samples = List(
          AnswerFormat.Radio(Nil),
          AnswerFormat.Dropdown(Nil),
          AnswerFormat.Checkbox(Nil, None),
          AnswerFormat.Input(None),
          AnswerFormat.Rating(None),
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
          // The same enum, nested inside an array of questions. The renderer reaches it wherever it
          // sits, which the per-tool patch it replaced did not.
          tagsOf(VideoTools.create("https://bucket.s3.amazonaws.com")) == fromCodec
        )
      ,
      test("a variant that rejects extra fields says so"):
        // `@rejectExtraFields` was enforced by the decoder and absent from the document, which is
        // how an `options` list sent to an `input` question was dropped without complaint and the
        // question created with none. Every AnswerFormat case carries it.
        val rendered =
          (SurveyTools.createQuestion.definition.inputSchema: zio.json.ast.Json).toString
        val needle = """"additionalProperties":false"""
        assertTrue(rendered.split(needle).length - 1 == AnswerFormat.caseIds.size)
      ,
      test("the published schema keeps every description an operator needs"):
        // The renderer changed; the guidance written for the operator must not have gone with it.
        val rendered =
          (SurveyTools.createQuestion.definition.inputSchema: zio.json.ast.Json).toString
        assertTrue(
          rendered.contains("Ask the operator for this; nothing else can supply it."),
          rendered.contains("Only for questions in a location survey; rejected elsewhere."),
          rendered.contains("Omit for email and url."),
          // And must not have brought back the generated filler, which told an agent nothing.
          !rendered.contains("a list of LocalizedText")
        )
      ,
      test("output schemas come from the same renderer as the inputs"):
        // McpOutput had the identical asymmetry: the encoder honours annotations the SDK's renderer
        // drops. No tool returns a discriminated enum today, so this pins the property rather than
        // a symptom — every published outputSchema must be what the shared renderer produces.
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
        // outputSchema and plain text content. Nothing returns String today; this is what would
        // catch the shadowing reaching further than intended.
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
        // The schema is what an agent reads before asking the operator anything, so an undocumented
        // field is a question asked badly or a value invented. Pinned rather than left to habit:
        // this started at 31 of 137 inputs and 3 of 98 outputs documented.
        val tools: List[com.jamesward.ziohttp.mcp.McpToolHandlerR[?]] =
          LocationTools.all ++ I18nTools.all ++ AdvertiserTools.all ++ SurveyTools.all ++
            AssetTools.all ++ VideoTools.all("https://bucket.s3.amazonaws.com")

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
                  // `kind` is synthesised by the generator to carry the discriminator; there
                  // is no field in the Scala type to annotate, and its one-value enum says what it is.
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
      test("the upload tool declares the origin its page has to reach"):
        // The host defaults `connect-src` to 'self'. Without this the page renders, the operator
        // picks a file, and the browser blocks the PUT — the first moment anything says so is the
        // last one. Nothing else in the server would fail.
        val meta = VideoTools
          .create("https://captal-assets.s3.us-east-1.amazonaws.com")
          .definition
          .meta
          .map(m => (m: zio.json.ast.Json).toString)
          .getOrElse("")
        val resumeMeta = VideoTools
          .resume("https://captal-assets.s3.us-east-1.amazonaws.com")
          .definition
          .meta
          .map(m => (m: zio.json.ast.Json).toString)
          .getOrElse("")
        assertTrue(
          // Both, because a View is instantiated only by calling a tool that carries this. A
          // resource a user opens is never rendered, so resume_video_upload without it would open
          // nothing and the only way back to an abandoned upload would be creating a second video.
          resumeMeta.contains("ui://upload-video"),
          resumeMeta.contains("connectDomains"),
          meta.contains("connectDomains"),
          meta.contains("captal-assets.s3.us-east-1.amazonaws.com"),
          meta.contains("ui://upload-video")
        )
    )
end CaptalMcpSpec
