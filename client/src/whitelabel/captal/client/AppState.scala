package whitelabel.captal.client

import com.raquo.laminar.api.L.*
import whitelabel.captal.core.application.Phase
import whitelabel.captal.core.application.commands.{NextAdvertiserSurvey, NextIdentificationSurvey}

object AppState:
  // Locale from server (detected via Accept-Language)
  private val localeVar: Var[String] = Var("es")
  val locale: Signal[String] = localeVar.signal
  def setLocale(l: String): Unit = localeVar.set(l)

  // Current phase
  private val phaseVar: Var[Option[Phase]] = Var(None)
  val phase: Signal[Option[Phase]] = phaseVar.signal
  def setPhase(p: Phase): Unit = phaseVar.set(Some(p))

  // Current identification survey (shared between views)
  private val currentSurveyVar: Var[Option[NextIdentificationSurvey]] = Var(None)
  val currentSurvey: Signal[Option[NextIdentificationSurvey]] = currentSurveyVar.signal

  def getCurrentSurvey: Option[NextIdentificationSurvey] = currentSurveyVar.now()

  def setCurrentSurvey(survey: NextIdentificationSurvey): Unit = currentSurveyVar.set(Some(survey))

  def clearCurrentSurvey(): Unit = currentSurveyVar.set(None)

  // Current advertiser survey
  private val currentAdvertiserSurveyVar: Var[Option[NextAdvertiserSurvey]] = Var(None)
  val currentAdvertiserSurvey: Signal[Option[NextAdvertiserSurvey]] = currentAdvertiserSurveyVar.signal

  def getCurrentAdvertiserSurvey: Option[NextAdvertiserSurvey] = currentAdvertiserSurveyVar.now()

  def setCurrentAdvertiserSurvey(survey: NextAdvertiserSurvey): Unit =
    currentAdvertiserSurveyVar.set(Some(survey))

  def clearCurrentAdvertiserSurvey(): Unit = currentAdvertiserSurveyVar.set(None)

  // Navigation transition state
  private val navigatingVar: Var[Boolean] = Var(false)
  val isNavigating: Signal[Boolean] = navigatingVar.signal
  def setNavigating(v: Boolean): Unit = navigatingVar.set(v)
end AppState
