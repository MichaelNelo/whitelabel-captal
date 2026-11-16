package whitelabel.captal.platform.queries

import cats.data.NonEmptyChain
import io.getquill.*
import whitelabel.captal.core.application.Error
import whitelabel.captal.core.application.queries.{
  ListSurveysQuery,
  OptionReport,
  Query,
  QuestionReport,
  QuestionSummaryReport,
  SurveyOfQuery,
  SurveyReport,
  SurveySummaryReport
}
import whitelabel.captal.core.location.Error as LocationError
import whitelabel.captal.core.survey.authoring.Error as AuthoringError
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.core.given
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.{
  AnswerRow,
  BackendTexts,
  LocationRow,
  QuestionOptionRow,
  QuestionRow,
  QuestionRuleRow,
  SurveyRow
}
import zio.*

/** [[ListSurveysQuery]] and [[SurveyOfQuery]], read from the survey tables and their texts. */
object Surveys:
  def list(quill: QuillSqlite): Query.Aux[Task, ListSurveysQuery, List[SurveySummaryReport]] =
    new Query[Task, ListSurveysQuery]:
      type Result = List[SurveySummaryReport]

      def execute(q: ListSurveysQuery): Task[Either[Error, List[SurveySummaryReport]]] =
        import quill.*
        for
          surveys   <- run(query[SurveyRow])
          questions <- run(query[QuestionRow])
          locations <- run(query[LocationRow])
          slugOf = locations.map(l => l.id -> l.slug).toMap
          reports <-
            // An unknown slug is refused rather than filtering everything out, which would read
            // as "this location has no surveys".
            q.slug.filterNot(wanted => locations.exists(_.slug == wanted.asString)) match
              case Some(unknown) =>
                ZIO.left(Error.Location(NonEmptyChain.one(LocationError.NotFound(unknown))))
              case None =>
                val visible = surveys
                  .filter(s => q.includeInactive || s.isActive == 1)
                  // A survey with no location is kept whatever the slug: every location asks it.
                  .filter(s =>
                    q.slug
                      .forall(wanted =>
                        s.locationId.forall(id => slugOf.get(id).contains(wanted.asString))))
                ZIO
                  .foreach(visible.sortBy(s => (s.category, s.name.getOrElse("")))): s =>
                    ZIO
                      .foreach(questions.filter(_.surveyId == s.id).sortBy(_.displayOrder)): q =>
                        BackendTexts
                          .of(quill, q.id.asString)
                          .map(QuestionSummaryReport(q.id.asString, q.isActive == 1, _))
                      .map: summaries =>
                        SurveySummaryReport(
                          id = s.id.asString,
                          category = s.category,
                          name = s.name,
                          videoId = s.videoId,
                          locationSlug = s.locationId.flatMap(slugOf.get),
                          active = s.isActive == 1,
                          questions = summaries
                        )
                  .map(Right(_))
        yield reports
        end for
      end execute

  def of(quill: QuillSqlite): Query.Aux[Task, SurveyOfQuery, SurveyReport] =
    new Query[Task, SurveyOfQuery]:
      type Result = SurveyReport

      def execute(q: SurveyOfQuery): Task[Either[Error, SurveyReport]] =
        import quill.*
        run(query[SurveyRow]).flatMap: surveys =>
          surveys.find(_.id.asString == q.id) match
            case None =>
              ZIO.left(
                Error.SurveyAuthoring(NonEmptyChain.one(AuthoringError.SurveyNotFound(q.id))))
            case Some(survey) =>
              for
                questionRows <- run(query[QuestionRow].filter(_.surveyId == lift(survey.id)))
                optionRows   <- run(query[QuestionOptionRow])
                ruleRows     <- run(query[QuestionRuleRow])
                questions    <-
                  ZIO.foreach(questionRows.sortBy(_.displayOrder)): question =>
                    for
                      text    <- BackendTexts.of(quill, question.id.asString)
                      answers <- run(
                        query[AnswerRow].filter(_.questionId == lift(question.id)).size)
                      options <-
                        ZIO.foreach(
                          optionRows.filter(_.questionId == question.id).sortBy(_.displayOrder)):
                          option =>
                            BackendTexts
                              .of(quill, option.id.asString)
                              .map(
                                OptionReport(
                                  option.id.asString,
                                  option.displayOrder,
                                  option.isActive == 1,
                                  _))
                    yield QuestionReport(
                      id = question.id.asString,
                      displayOrder = question.displayOrder,
                      required = question.isRequired == 1,
                      points = question.pointsAwarded,
                      active = question.isActive == 1,
                      answers = answers.toInt,
                      text = text,
                      options = options,
                      // As stored rather than decoded: decoding drops a rule that does not match
                      // the question's type, which only the stored text shows.
                      rules = ruleRows
                        .filter(r => r.questionId == question.id && r.isActive == 1)
                        .map(_.ruleConfig)
                    )
              yield Right(
                SurveyReport(
                  survey.id.asString,
                  survey.category,
                  survey.name,
                  survey.isActive == 1,
                  questions))
      end execute
end Surveys
