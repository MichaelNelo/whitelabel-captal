package whitelabel.captal.mcp

import java.util.UUID

import com.jamesward.ziohttp.mcp.{CallToolResult, ToolContent}
import com.typesafe.config.ConfigFactory
import fly4s.Fly4s
import fly4s.data.{Fly4sConfig, Location, MigrationVersion}
import io.getquill.*
import io.getquill.jdbczio.Quill
import whitelabel.captal.core.survey
import whitelabel.captal.infra.schema.QuillSqlite
import whitelabel.captal.infra.schema.core.given
import whitelabel.captal.infra.schema.given
import whitelabel.captal.infra.{LocalizedTextRow, QuestionRow, RqliteDataSource, SurveyRow}
import zio.*
import zio.interop.catz.*
import zio.test.*

/** Survey tools against a real rqlite instance.
  *
  * The behaviour worth pinning is that retiring never destroys: `answers.question_id` references
  * `questions`, and a retired question has to keep resolving for everything already recorded.
  */
object SurveyToolsSpec extends ZIOSpecDefault:
  private val Now = java.time.Instant.EPOCH.toString

  private val migrate: Task[Unit] = ZIO
    .attempt(RqliteDataSource.create(ConfigFactory.load().getString("database.jdbcUrl")))
    .flatMap: ds =>
      Fly4s
        .makeFor[Task](
          ZIO.succeed(ds),
          config = Fly4sConfig
            .default
            .copy(
              locations = List(Location("db/migration")),
              baselineOnMigrate = true,
              baselineVersion = MigrationVersion("0"),
              cleanOnValidationError = true)
        )
        .use(_.migrate)
        .unit

  final private case class Fixture(surveyId: String, questionIds: List[String])

  /** Three questions so retiring one still leaves an order to observe; with a single question a
    * reorder or a retire cannot be told apart from doing nothing.
    */
  private def seed: ZIO[QuillSqlite, Throwable, Fixture] = ZIO.serviceWithZIO[QuillSqlite]: quill =>
    import quill.*
    val surveyId = survey.Id.generate
    for
      _ <- quill.run(query[QuestionRow].delete)
      _ <- quill.run(query[SurveyRow].delete)
      _ <- quill.run(query[LocalizedTextRow].filter(_.category == "backend").delete)
      _ <- quill.run(
        query[SurveyRow].insertValue(
          lift(SurveyRow(surveyId, "profiling", None, None, None, 1, Now, Some("Profiling")))))
      ids <-
        ZIO.foreach(List(1, 2, 3)): order =>
          val qid = survey.question.Id.generate
          for
            _ <- quill.run(
              query[QuestionRow].insertValue(
                lift(QuestionRow(qid, surveyId, "radio", 10, order, None, 1, Now, 1))))
            _ <- quill.run(
              query[LocalizedTextRow].insertValue(
                lift(
                  LocalizedTextRow(
                    UUID.randomUUID.toString,
                    qid.asString,
                    "en",
                    s"Question $order",
                    "backend",
                    Now,
                    Now))))
          yield qid.asString
    yield Fixture(surveyId.asString, ids)
    end for

  private def questions
      : ZIO[QuillSqlite, Throwable, List[QuestionRow]] = ZIO.serviceWithZIO[QuillSqlite]: quill =>
    import quill.*
    quill.run(query[QuestionRow])

  private def json(fields: (String, zio.json.ast.Json)*) = Some(
    zio.json.ast.Json.Obj(Chunk.fromIterable(fields)))
  private def str(s: String) = zio.json.ast.Json.Str(s)
  private def arr(items: String*) = zio.json.ast.Json.Arr(Chunk.fromIterable(items.map(str)))

  private def textOf(r: CallToolResult): String =
    r.content
      .collect { case ToolContent.Text(t, _) =>
        t
      }
      .mkString

  private def textArr(locale: String, value: String) = zio
    .json
    .ast
    .Json
    .Arr(Chunk(zio.json.ast.Json.Obj(Chunk("locale" -> str(locale), "value" -> str(value)))))

  private def optionJson(label: String) = zio
    .json
    .ast
    .Json
    .Obj(Chunk("text" -> textArr("en", label)))

  private def ruleJson(kind: String, value: Option[String] = None) = zio
    .json
    .ast
    .Json
    .Obj(Chunk("kind" -> str(kind)) ++ value.fold(Chunk.empty)(v => Chunk("value" -> str(v))))

  /** The discriminated shape: `question` carries the type and whatever that type accepts. Only
    * surveyId, text and question are required, which is the point — an agent adding a plain text
    * question supplies the wording and nothing else.
    */
  private def questionJson(
      surveyId: String,
      questionType: String,
      options: Chunk[zio.json.ast.Json] = Chunk.empty,
      rules: Chunk[zio.json.ast.Json] = Chunk.empty) =
    val spec =
      Chunk("questionType" -> str(questionType)) ++ (
        if options.isEmpty then
          Chunk.empty
        else
          Chunk("options" -> zio.json.ast.Json.Arr(options))
      ) ++ (
        if rules.isEmpty then
          Chunk.empty
        else
          Chunk("rules" -> zio.json.ast.Json.Arr(rules))
      )
    Some(
      zio
        .json
        .ast
        .Json
        .Obj(
          Chunk(
            "surveyId" -> str(surveyId),
            "text"     -> textArr("en", "A question"),
            "question" -> zio.json.ast.Json.Obj(spec))))
  end questionJson

  private val quillLayer =
    RqliteDataSource.layer >>> Quill.Sqlite.fromNamingStrategy(io.getquill.SnakeCase)

  def spec =
    suite("SurveyTools against rqlite")(
      test("get_survey returns questions in display order with their texts"):
        for
          fixture <- seed
          result  <- SurveyTools.get.call(json("surveyId" -> str(fixture.surveyId)))
          body = textOf(result)
        yield assertTrue(
          !result.isError.contains(true),
          body.contains("Question 1"),
          body.contains("Question 3"),
          // Order in the payload follows display_order, not insertion or id order.
          body.indexOf("Question 1") < body.indexOf("Question 2")
        )
      ,
      test("retiring a question flips is_active without removing the row"):
        for
          fixture <- seed
          target = fixture.questionIds.head
          result <- SurveyTools
            .setQuestionActive
            .call(json("questionId" -> str(target), "active" -> zio.json.ast.Json.Bool(false)))
          stored <- questions
        yield assertTrue(
          !result.isError.contains(true),
          // Still three rows: answers reference questions, so nothing may be deleted.
          stored.size == 3,
          stored.find(_.id.asString == target).exists(_.isActive == 0),
          textOf(result).contains("\"remainingActive\":2")
        )
      ,
      test("reorder rewrites display_order to match the list"):
        for
          fixture <- seed
          reversed = fixture.questionIds.reverse
          result <- SurveyTools
            .reorder
            .call(json("surveyId" -> str(fixture.surveyId), "orderedQuestionIds" -> arr(reversed*)))
          stored <- questions
          byOrder = stored.sortBy(_.displayOrder).map(_.id.asString)
        yield assertTrue(!result.isError.contains(true), byOrder == reversed)
      ,
      test("a partial reorder is refused, leaving the order untouched"):
        for
          fixture <- seed
          before  <- questions
          result  <- SurveyTools
            .reorder
            .call(
              json(
                "surveyId"           -> str(fixture.surveyId),
                "orderedQuestionIds" -> arr(fixture.questionIds.take(2)*)))
          after <- questions
        yield assertTrue(
          result.isError.contains(true),
          textOf(result).contains("every active question exactly once"),
          after.map(_.displayOrder).sorted == before.map(_.displayOrder).sorted
        )
      ,
      test("reorder refuses a question from another survey"):
        for
          fixture <- seed
          result  <- SurveyTools
            .reorder
            .call(
              json(
                "surveyId"           -> str(fixture.surveyId),
                "orderedQuestionIds" ->
                  arr((fixture.questionIds.drop(1) :+ survey.question.Id.generate.asString)*)))
        yield assertTrue(
          result.isError.contains(true),
          textOf(result).contains("Not in this survey"))
      ,
      test("an unknown survey is reported rather than silently doing nothing"):
        for
          _      <- seed
          result <- SurveyTools.get.call(json("surveyId" -> str("nope")))
        yield assertTrue(result.isError.contains(true), textOf(result).contains("No survey"))
      ,
      test("creating a choice question writes its options and texts"):
        for
          fixture <- seed
          created <- SurveyTools
            .createQuestion
            .call(
              questionJson(
                fixture.surveyId,
                "radio",
                options = Chunk(optionJson("Under 30"), optionJson("Over 30"))))
          fetched <- SurveyTools.get.call(json("surveyId" -> str(fixture.surveyId)))
          body = textOf(fetched)
        yield assertTrue(
          !created.isError.contains(true),
          // Appended after the three seeded questions rather than inserted anywhere.
          textOf(created).contains("\"displayOrder\":4"),
          body.contains("Under 30"),
          body.contains("Over 30")
        )
      ,
      test("a rule that does not match the question type is refused"):
        // The whole point: decodeRules drops a mismatched rule at runtime with no error, so a
        // min_selections rule on an input question would be stored and never applied.
        for
          fixture <- seed
          result  <- SurveyTools
            .createQuestion
            .call(
              questionJson(
                fixture.surveyId,
                "input",
                rules = Chunk(ruleJson("min_selections", Some("2")))))
        yield assertTrue(
          result.isError.contains(true),
          // Structural now: the input variant has no selection rules to pass.
          textOf(result).contains("unrecognized string"))
      ,
      test("a matching rule is accepted"):
        for
          fixture <- seed
          result  <- SurveyTools
            .createQuestion
            .call(
              questionJson(
                fixture.surveyId,
                "input",
                rules = Chunk(ruleJson("min_length", Some("3")))))
        yield assertTrue(!result.isError.contains(true))
      ,
      test("a radio question with no options is refused"):
        for
          fixture <- seed
          result  <- SurveyTools.createQuestion.call(questionJson(fixture.surveyId, "radio"))
        yield assertTrue(
          result.isError.contains(true),
          textOf(result).contains("needs at least one option"))
      ,
      test("an input question carrying options is refused"):
        for
          fixture <- seed
          result  <- SurveyTools
            .createQuestion
            .call(questionJson(fixture.surveyId, "input", options = Chunk(optionJson("nope"))))
        yield assertTrue(
          result.isError.contains(true),
          // The input variant has no options field, and extra fields are rejected.
          textOf(result).contains("extra field"))
      ,
      test("a second active survey of the same category is refused"):
        for
          _      <- seed
          result <- SurveyTools.createSurvey.call(json("category" -> str("profiling")))
        yield assertTrue(result.isError.contains(true), textOf(result).contains("already exists"))
      ,
      test("an advertiser survey without a video is refused"):
        for
          _      <- seed
          result <- SurveyTools.createSurvey.call(json("category" -> str("advertiser")))
        yield assertTrue(
          result.isError.contains(true),
          textOf(result).contains("needs the videoId"))
      ,
      test("an advertiser survey naming a video that does not exist is refused"):
        // Nothing else would catch it: advertiser_videos has no foreign key from surveys, so the
        // row would store fine and the survey would simply be unreachable forever.
        for
          _      <- seed
          result <- SurveyTools
            .createSurvey
            .call(json("category" -> str("advertiser"), "videoId" -> str("no-such-video")))
        yield assertTrue(result.isError.contains(true), textOf(result).contains("No video with id"))
    ).provideShared(quillLayer, ZLayer.fromZIO(migrate.unit)) @@ TestAspect.sequential
end SurveyToolsSpec
