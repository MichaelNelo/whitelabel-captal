package whitelabel.captal.client

import com.raquo.laminar.api.L.*
import whitelabel.captal.core.application.Phase

object AppState:
  // Locale from server (detected via Accept-Language)
  private val localeVar: Var[String] = Var("es")
  val locale: Signal[String] = localeVar.signal
  def setLocale(l: String): Unit = localeVar.set(l)

  // Current phase
  private val phaseVar: Var[Option[Phase]] = Var(None)
  val phase: Signal[Option[Phase]] = phaseVar.signal
  def setPhase(p: Phase): Unit = phaseVar.set(Some(p))
