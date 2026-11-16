package whitelabel.captal.core.application.commands

import java.time.Instant

import cats.Monad
import cats.data.NonEmptyChain
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import whitelabel.captal.core.Op as CoreOp
import whitelabel.captal.core.application.{Error, Event}
import whitelabel.captal.core.infrastructure.{QuestionInfo, SurveyAuthoringRepository, SurveyInfo}
import whitelabel.captal.core.survey.Id as SurveyId
import whitelabel.captal.core.survey.authoring.{
  Error as AuthoringError,
  Event as AuthoringEvent,
  QuestionSpec,
  SurveyKind,
  TextField
}
import whitelabel.captal.core.survey.question.{Id as QuestionId, LocalizedText, OptionId}

/** Whose a new survey is: one of the identification surveys, deployment-wide, or the survey asked
  * after the video `video`.
  */
enum SurveyOwner:
  case Identification(kind: SurveyKind)
  case Video(video: String)

final case class CreateSurveyCommand(
    id: SurveyId,
    owner: SurveyOwner,
    name: Option[String],
    occurredAt: Instant)

/** The survey created, and its kind. */
final case class CreatedSurvey(id: SurveyId, kind: SurveyKind)

/** An identification survey is refused while another of its kind is active, since they are picked
  * by kind alone; a video's is refused for a video that does not exist, and takes its location and
  * advertiser from the video.
  */
object CreateSurveyHandler:
  def apply[F[_]: Monad](
      surveys: SurveyAuthoringRepository[F]): Handler.Aux[F, CreateSurveyCommand, CreatedSurvey] =
    new Handler[F, CreateSurveyCommand]:
      type Result = CreatedSurvey

      def handle(cmd: CreateSurveyCommand) =
        cmd.owner match
          case SurveyOwner.Identification(kind) =>
            surveys
              .activeSurveyOf(kind)
              .map: existing =>
                authored(
                  existing.map(AuthoringError.IdentificationSurveyExists(kind, _)).toList,
                  List(
                    AuthoringEvent.SurveyCreated(
                      cmd.id,
                      kind,
                      None,
                      None,
                      None,
                      cmd.name,
                      cmd.occurredAt)),
                  CreatedSurvey(cmd.id, kind)
                )
          case SurveyOwner.Video(video) =>
            surveys
              .videoOwner(video)
              .map:
                case None =>
                  refusedAuthoring(AuthoringError.VideoNotFound(video))
                case Some(owner) =>
                  authored(
                    Nil,
                    List(
                      AuthoringEvent.SurveyCreated(
                        cmd.id,
                        SurveyKind.Advertiser,
                        Some(video),
                        owner.location,
                        owner.advertiser,
                        cmd.name,
                        cmd.occurredAt)),
                    CreatedSurvey(cmd.id, SurveyKind.Advertiser)
                  )
end CreateSurveyHandler

/** Adds `question` to the survey `survey`, last. `identification` says which side the caller is on:
  * the identification surveys' or a location's, each with its own tools and scope.
  */
final case class AddQuestionCommand(
    survey: SurveyId,
    identification: Boolean,
    question: QuestionSpec,
    occurredAt: Instant)

/** The question added, where it is asked, and its options, in the order given. */
final case class QuestionPlaced(id: QuestionId, displayOrder: Int, options: List[OptionId])

/** Refused for a survey on the other side, and for every reason the question cannot be asked
  * ([[QuestionSpec.problems]]).
  */
object AddQuestionHandler:
  def apply[F[_]: Monad](
      surveys: SurveyAuthoringRepository[F]): Handler.Aux[F, AddQuestionCommand, QuestionPlaced] =
    new Handler[F, AddQuestionCommand]:
      type Result = QuestionPlaced

      def handle(cmd: AddQuestionCommand) =
        surveyOf(surveys, cmd.survey, cmd.identification): survey =>
          surveys
            .questionsOf(survey.id)
            .map: siblings =>
              val order = siblings.map(_.displayOrder).maxOption.getOrElse(0) + 1
              authored(
                QuestionSpec.problems(cmd.question, survey.kind),
                List(AuthoringEvent.QuestionAdded(survey.id, cmd.question, order, cmd.occurredAt)),
                QuestionPlaced(cmd.question.id, order, cmd.question.answer.choices.map(_.id))
              )
end AddQuestionHandler

/** Corrects the question `question` in place: what is given replaces what it had, the texts in the
  * locales given. The answers given to it stay attached, to the old wording.
  */
final case class UpdateQuestionCommand(
    question: QuestionId,
    identification: Boolean,
    text: Option[List[LocalizedText]],
    description: Option[List[LocalizedText]],
    placeholder: Option[List[LocalizedText]],
    required: Option[Boolean],
    points: Option[Int],
    occurredAt: Instant)

/** How many answers were given to the question before the change. */
final case class AnswersKept(answers: Int)

/** Refused for a question on the other side, negative points, and a text with a blank entry or
  * repeating a locale.
  */
object UpdateQuestionHandler:
  def apply[F[_]: Monad](
      surveys: SurveyAuthoringRepository[F]): Handler.Aux[F, UpdateQuestionCommand, AnswersKept] =
    new Handler[F, UpdateQuestionCommand]:
      type Result = AnswersKept

      def handle(cmd: UpdateQuestionCommand) =
        questionOf(surveys, cmd.question, cmd.identification): question =>
          surveys
            .answersTo(question.id)
            .map: answers =>
              val texts = List(
                TextField.Text        -> cmd.text,
                TextField.Description -> cmd.description,
                TextField.Placeholder -> cmd.placeholder).flatMap: (field, written) =>
                written.toList.flatMap(QuestionSpec.textProblems(field, _))
              val asked =
                cmd.text.isDefined || cmd.description.isDefined || cmd.placeholder.isDefined ||
                  cmd.required.isDefined || cmd.points.isDefined
              authored(
                cmd.points.filter(_ < 0).map(AuthoringError.NegativePoints(_)).toList ++ texts,
                // Asked for nothing, it changes nothing, and says so by naming no field.
                Option
                  .when(asked)(
                    AuthoringEvent.QuestionUpdated(
                      question.id,
                      cmd.text,
                      cmd.description,
                      cmd.placeholder,
                      cmd.required,
                      cmd.points,
                      cmd.occurredAt))
                  .toList,
                AnswersKept(answers)
              )
end UpdateQuestionHandler

/** Takes the question `question` out of its survey; its answers are kept. */
final case class RetireQuestionCommand(
    question: QuestionId,
    identification: Boolean,
    occurredAt: Instant)

/** Brings the question `question` back into its survey. */
final case class ReinstateQuestionCommand(
    question: QuestionId,
    identification: Boolean,
    occurredAt: Instant)

/** The answers the question keeps, and how many of its survey's questions are active after: none
  * leaves a survey that is never served.
  */
final case class QuestionActivity(answers: Int, remainingActive: Int)

object RetireQuestionHandler:
  def apply[F[_]: Monad](surveys: SurveyAuthoringRepository[F])
      : Handler.Aux[F, RetireQuestionCommand, QuestionActivity] =
    new Handler[F, RetireQuestionCommand]:
      type Result = QuestionActivity

      def handle(cmd: RetireQuestionCommand) =
        activity(surveys, cmd.question, cmd.identification, active = false)(
          AuthoringEvent.QuestionRetired(_, cmd.occurredAt))

object ReinstateQuestionHandler:
  def apply[F[_]: Monad](surveys: SurveyAuthoringRepository[F])
      : Handler.Aux[F, ReinstateQuestionCommand, QuestionActivity] =
    new Handler[F, ReinstateQuestionCommand]:
      type Result = QuestionActivity

      def handle(cmd: ReinstateQuestionCommand) =
        activity(surveys, cmd.question, cmd.identification, active = true)(
          AuthoringEvent.QuestionReinstated(_, cmd.occurredAt))

/** The question `id` left `active`, as `event` says, with what that leaves its survey. */
private def activity[F[_]: Monad](
    surveys: SurveyAuthoringRepository[F],
    id: QuestionId,
    identification: Boolean,
    active: Boolean)(event: QuestionId => AuthoringEvent): F[Op[QuestionActivity]] =
  questionOf(surveys, id, identification): question =>
    for
      answers  <- surveys.answersTo(question.id)
      siblings <- surveys.questionsOf(question.survey)
    yield
      val others = siblings.count(q => q.active && q.id != question.id)
      authored(
        Nil,
        List(event(question.id)),
        QuestionActivity(
          answers,
          others + (
            if active then
              1
            else
              0
          )))

/** Asks the active questions of the survey `survey` in `order`. */
final case class ReorderQuestionsCommand(
    survey: SurveyId,
    identification: Boolean,
    order: List[String],
    occurredAt: Instant)

/** Refused unless `order` names every active question exactly once: a partial one would leave
  * questions sharing a place.
  */
object ReorderQuestionsHandler:
  def apply[F[_]: Monad](
      surveys: SurveyAuthoringRepository[F]): Handler.Aux[F, ReorderQuestionsCommand, Unit] =
    new Handler[F, ReorderQuestionsCommand]:
      type Result = Unit

      def handle(cmd: ReorderQuestionsCommand) =
        surveyOf(surveys, cmd.survey, cmd.identification): survey =>
          surveys
            .questionsOf(survey.id)
            .map: questions =>
              val active = questions.filter(_.active).map(q => q.id.asString -> q.id).toMap
              val named = cmd.order.toSet
              val missing = (active.keySet -- named).toList.sorted
              val extra = (named -- active.keySet).toList.sorted
              val refusals =
                if questions.isEmpty then
                  List(AuthoringError.NoQuestions(survey.id.asString))
                else
                  List(
                    Option.when(missing.nonEmpty || extra.nonEmpty)(
                      AuthoringError.NotEveryQuestion(missing, extra)),
                    Option.when(cmd.order.distinct.sizeIs != cmd.order.size)(
                      AuthoringError.RepeatedQuestion)
                  ).flatten
              authored(
                refusals,
                List(
                  AuthoringEvent.QuestionsReordered(
                    survey.id,
                    cmd.order.flatMap(active.get),
                    cmd.occurredAt)),
                ())
end ReorderQuestionsHandler

/** The survey `id`, when it is on the caller's side; refused otherwise. */
private def surveyOf[F[_]: Monad, A](
    surveys: SurveyAuthoringRepository[F],
    id: SurveyId,
    identification: Boolean)(decide: SurveyInfo => F[Op[A]]): F[Op[A]] = surveys
  .survey(id)
  .flatMap:
    case None =>
      Monad[F].pure(refusedAuthoring(AuthoringError.SurveyNotFound(id.asString)))
    case Some(survey) if survey.kind.identification != identification =>
      Monad[F].pure(refusedAuthoring(AuthoringError.OtherSide(survey.kind)))
    case Some(survey) =>
      decide(survey)

/** The question `id`, when its survey is on the caller's side; refused otherwise. */
private def questionOf[F[_]: Monad, A](
    surveys: SurveyAuthoringRepository[F],
    id: QuestionId,
    identification: Boolean)(decide: QuestionInfo => F[Op[A]]): F[Op[A]] = surveys
  .question(id)
  .flatMap:
    case None =>
      Monad[F].pure(refusedAuthoring(AuthoringError.QuestionNotFound(id.asString)))
    case Some(question) if question.kind.identification != identification =>
      Monad[F].pure(refusedAuthoring(AuthoringError.OtherSide(question.kind)))
    case Some(question) =>
      decide(question)

/** Refused with `errors` when there are any; otherwise `events`, answering `result`. */
private def authored[A](
    errors: List[AuthoringError],
    events: => List[AuthoringEvent],
    result: => A): Op[A] =
  NonEmptyChain.fromSeq(errors) match
    case Some(found) =>
      CoreOp.fail[Event, Error, A](Error.SurveyAuthoring(found))
    case None =>
      CoreOp.emitAll[Event, Error, A](events.map(Event.SurveyAuthoring(_)), result)

private def refusedAuthoring[A](error: AuthoringError): Op[A] = CoreOp.fail[Event, Error, A](
  Error.SurveyAuthoring(NonEmptyChain.one(error)))
