package whitelabel.captal.client.i18n

import com.raquo.laminar.api.L.*
import whitelabel.captal.client.{ApiClient, Runtime}
import whitelabel.captal.core.i18n.I18n
import zio.*

object I18nClient:
  // Default empty I18n for initial state
  private val emptyI18n: I18n = I18n(
    welcome = I18n.Welcome(
      title = "",
      subtitle = "",
      steps = I18n.Welcome.Steps("", "", ""),
      button = I18n.Welcome.Button("", ""),
      selectLanguage = ""),
    loading = I18n.Loading(""),
    error = I18n.Error("", "", ""),
    question = I18n.Question("", "", "", "", "", "", "", "", "", "", "", "", "", "", "")
  )

  private val localeVar: Var[String] = Var("es")
  private val i18nVar: Var[I18n] = Var(emptyI18n)
  private val isLoadedVar: Var[Boolean] = Var(false)

  val locale: Signal[String] = localeVar.signal
  val i18n: Signal[I18n] = i18nVar.signal
  val isLoaded: Signal[Boolean] = isLoadedVar.signal

  def current: I18n = i18nVar.now()
  def currentLocale: String = localeVar.now()

  def setLocale(code: String): Unit =
    localeVar.set(code)
    load(code)

  def load(locale: String): Unit = Runtime.run:
    ApiClient
      .getI18n(locale)
      .tap:
        case Right(data) =>
          ZIO.succeed:
            i18nVar.set(data)
            isLoadedVar.set(true)
        case Left(_) =>
          ZIO.succeed(isLoadedVar.set(true)) // Still mark as loaded to show UI
end I18nClient
