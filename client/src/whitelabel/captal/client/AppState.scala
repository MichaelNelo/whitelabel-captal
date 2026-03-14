package whitelabel.captal.client

import com.raquo.laminar.api.L.*
import whitelabel.captal.core.application.Phase
import whitelabel.captal.core.application.commands.NextIdentificationSurvey

object AppState:
  // Locale from server (detected via Accept-Language)
  private val localeVar: Var[String] = Var("es")
  val locale: Signal[String] = localeVar.signal
  def setLocale(l: String): Unit = localeVar.set(l)

  // Current phase
  private val phaseVar: Var[Option[Phase]] = Var(None)
  val phase: Signal[Option[Phase]] = phaseVar.signal
  def setPhase(p: Phase): Unit = phaseVar.set(Some(p))

  // Current survey (shared between views)
  private val currentSurveyVar: Var[Option[NextIdentificationSurvey]] = Var(None)
  val currentSurvey: Signal[Option[NextIdentificationSurvey]] = currentSurveyVar.signal

  def getCurrentSurvey: Option[NextIdentificationSurvey] = currentSurveyVar.now()

  def setCurrentSurvey(survey: NextIdentificationSurvey): Unit = currentSurveyVar.set(Some(survey))

  def clearCurrentSurvey(): Unit = currentSurveyVar.set(None)

  // Navigation transition state
  private val navigatingVar: Var[Boolean] = Var(false)
  val isNavigating: Signal[Boolean] = navigatingVar.signal
  def setNavigating(v: Boolean): Unit = navigatingVar.set(v)
end AppState
