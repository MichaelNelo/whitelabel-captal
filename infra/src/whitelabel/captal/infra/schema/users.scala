package whitelabel.captal.infra.schema

import scala.annotation.targetName

import io.getquill.*
import whitelabel.captal.core.user.{State as UserState, User}
import whitelabel.captal.core.{survey, user}
import whitelabel.captal.infra.SessionRow

package object users:
  // Re-export SessionRow SchemaMeta so QueryMeta can find it at inline expansion
  inline given SchemaMeta[SessionRow] = schemaMeta[SessionRow]("sessions")
  // ─────────────────────────────────────────────────────────────────────────────
  // Schema Meta - User[State.WithEmail]
  // ─────────────────────────────────────────────────────────────────────────────

  @targetName("schemaMetaUserWithEmail")
  inline given SchemaMeta[User[UserState.WithEmail]] = schemaMeta[User[UserState.WithEmail]](
    "users",
    _.id          -> "id",
    _.state.email -> "email")

  // ─────────────────────────────────────────────────────────────────────────────
  // Schema Meta + Query Meta - User[State.AnsweringQuestion]
  // ─────────────────────────────────────────────────────────────────────────────

  // DTO for join result (Option fields because SQL columns are nullable)
  final case class UserAnsweringDto(
      id: user.Id,
      surveyId: Option[survey.Id],
      questionId: Option[survey.question.Id])

  @targetName("schemaMetaUserAnsweringQuestion")
  inline given SchemaMeta[User[UserState.AnsweringQuestion]] =
    schemaMeta[User[UserState.AnsweringQuestion]]("users", _.id -> "id")

  inline given QueryMeta[User[UserState.AnsweringQuestion], UserAnsweringDto] =
    queryMeta[User[UserState.AnsweringQuestion], UserAnsweringDto](
      quote: userQuery =>
        for
          user    <- userQuery
          session <- query[SessionRow]
          if session.userId.contains(user.id) && session.currentSurveyId.isDefined &&
            session.currentQuestionId.isDefined
        yield UserAnsweringDto(user.id, session.currentSurveyId, session.currentQuestionId))(dto =>
      User(dto.id, UserState.AnsweringQuestion(dto.surveyId.get, dto.questionId.get)))
end users
