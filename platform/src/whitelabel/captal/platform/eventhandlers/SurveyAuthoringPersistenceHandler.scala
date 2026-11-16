package whitelabel.captal.platform.eventhandlers

import java.util.UUID

import io.getquill.*
import whitelabel.captal.core.application.Event
import whitelabel.captal.core.survey.authoring.{AnswerSpec, Event as AuthoringEvent, QuestionSpec}
import whitelabel.captal.core.survey.question.Id as QuestionId
import whitelabel.captal.platform.repositories.SurveyAuthoringRepositoryQuill
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.core.given
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.surveySchemas.given
import whitelabel.captal.platform.{
  BackendTexts,
  QuestionOptionRow,
  QuestionRow,
  QuestionRuleRow,
  SurveyRow,
  surveySchemas
}
import zio.*

/** The surveys and their questions, as the backoffice's commands decided them: a question's
  * wording, options and rules in the tables the portal reads them from, each rule as the JSON
  * `surveySchemas` reads back.
  */
object SurveyAuthoringPersistenceHandler:
  def apply(): DbEventHandler[Event] =
    new DbEventHandler[Event]:
      def handle(events: List[Event], quill: QuillSqlite): Task[Unit] =
        import quill.*
        def active(question: QuestionId, flag: Int) =
          run(query[QuestionRow].filter(_.id == lift(question)).update(_.isActive -> lift(flag)))
            .unit
        ZIO.foreachDiscard(events):
          case Event.SurveyAuthoring(
                AuthoringEvent.SurveyCreated(
                  id,
                  kind,
                  video,
                  location,
                  advertiser,
                  name,
                  occurredAt)) =>
            val row = SurveyRow(
              id,
              SurveyAuthoringRepositoryQuill.categoryOf(kind),
              advertiser,
              video,
              location,
              1,
              occurredAt.toString,
              name)
            run(query[SurveyRow].insertValue(lift(row))).unit
          case Event.SurveyAuthoring(
                AuthoringEvent.QuestionAdded(survey, question, displayOrder, occurredAt)) =>
            added(quill, survey, question, displayOrder, occurredAt.toString)
          case Event.SurveyAuthoring(
                AuthoringEvent.QuestionUpdated(
                  question,
                  text,
                  description,
                  placeholder,
                  required,
                  points,
                  occurredAt)) =>
            val at = occurredAt.toString
            val id = question.asString
            for
              _ <- ZIO.foreachDiscard(text)(BackendTexts.replace(quill, id, _, at))
              _ <-
                ZIO.foreachDiscard(description)(BackendTexts.replace(quill, s"${id}_desc", _, at))
              _ <-
                ZIO.foreachDiscard(placeholder)(
                  BackendTexts.replace(quill, s"${id}_placeholder", _, at))
              _ <-
                ZIO.foreachDiscard(required): yes =>
                  val flag =
                    if yes then
                      1
                    else
                      0
                  run(
                    query[QuestionRow]
                      .filter(_.id == lift(question))
                      .update(_.isRequired -> lift(flag)))
              _ <-
                ZIO.foreachDiscard(points): awarded =>
                  run(
                    query[QuestionRow]
                      .filter(_.id == lift(question))
                      .update(_.pointsAwarded -> lift(awarded)))
            yield ()
            end for
          case Event.SurveyAuthoring(AuthoringEvent.QuestionRetired(question, _)) =>
            active(question, 0)
          case Event.SurveyAuthoring(AuthoringEvent.QuestionReinstated(question, _)) =>
            active(question, 1)
          case Event.SurveyAuthoring(AuthoringEvent.QuestionsReordered(_, order, _)) =>
            ZIO.foreachDiscard(order.zipWithIndex): (question, index) =>
              run(
                query[QuestionRow]
                  .filter(_.id == lift(question))
                  .update(_.displayOrder -> lift(index + 1)))
          case _ =>
            ZIO.unit
      end handle

  /** A question's row, its texts, its options with theirs, and its rules. */
  def added(
      quill: QuillSqlite,
      survey: whitelabel.captal.core.survey.Id,
      question: QuestionSpec,
      displayOrder: Int,
      at: String): Task[Unit] =
    import quill.*
    val id = question.id.asString
    val kind = storedType(question.answer)
    val row = QuestionRow(
      question.id,
      survey,
      kind,
      question.points,
      displayOrder,
      question.hierarchyLevel.map(_.toString.toLowerCase),
      if question.required then
        1
      else
        0
      ,
      at,
      1)
    val rules = ruleConfigs(question.answer).map(config =>
      QuestionRuleRow(UUID.randomUUID.toString, question.id, kind, config, 1))
    for
      _ <- run(query[QuestionRow].insertValue(lift(row)))
      _ <- BackendTexts.write(quill, id, question.text, at)
      _ <- BackendTexts.write(quill, s"${id}_desc", question.description, at)
      _ <- BackendTexts.write(quill, s"${id}_placeholder", question.placeholder, at)
      _ <-
        ZIO.foreachDiscard(question.answer.choices.zipWithIndex): (option, index) =>
          run(
            query[QuestionOptionRow].insertValue(
              lift(QuestionOptionRow(option.id, question.id, index + 1, None, 1)))) *>
            BackendTexts.write(quill, option.id.asString, option.text, at)
      _ <-
        ZIO.when(rules.nonEmpty)(
          run(liftQuery(rules).foreach(rule => query[QuestionRuleRow].insertValue(rule))))
    yield ()
  end added

  /** The word `questions.question_type` holds for what the question asks for. */
  private def storedType(answer: AnswerSpec): String =
    answer match
      case AnswerSpec.Radio(_) =>
        "radio"
      case AnswerSpec.Dropdown(_) =>
        "dropdown"
      case AnswerSpec.Checkbox(_, _) =>
        "checkbox"
      case AnswerSpec.Input(_) =>
        "input"
      case AnswerSpec.Numeric(_) =>
        "numeric"
      case AnswerSpec.Date(_) =>
        "date"

  private def ruleConfigs(answer: AnswerSpec): List[String] =
    answer match
      case AnswerSpec.Checkbox(_, rules) =>
        rules.map(surveySchemas.encode(_))
      case AnswerSpec.Input(rules) =>
        rules.map(surveySchemas.encode(_))
      case AnswerSpec.Numeric(rules) =>
        rules.map(surveySchemas.encode(_)(using surveySchemas.rangeRuleBigDecimalSchema))
      case AnswerSpec.Date(rules) =>
        rules.map(surveySchemas.encode(_)(using surveySchemas.rangeRuleLocalDateSchema))
      case _ =>
        Nil
end SurveyAuthoringPersistenceHandler
