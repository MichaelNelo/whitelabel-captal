package whitelabel.captal.client.i18n

import com.raquo.laminar.api.L.*
import whitelabel.captal.client.{ApiClient, Runtime}
import whitelabel.captal.core.i18n.I18n
import scala.concurrent.ExecutionContext.Implicits.global

object I18nClient:
  // Default empty I18n for initial state
  private val emptyI18n: I18n = I18n(
    welcome = I18n.Welcome(
      title = "",
      subtitle = "",
      steps = I18n.Welcome.Steps("", "", ""),
      button = I18n.Welcome.Button("", ""),
      selectLanguage = ""),
    ready = I18n.Ready("", "", ""),
    loading = I18n.Loading(""),
    error = I18n.Error("", "", ""),
    question = I18n.Question("", "", "", "", "", "", "", "", "", "", "", "", "", "", ""),
    video = I18n.Video("", "", "", "", "", "", "")
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
    ApiClient.getI18n(locale).map:
      case Right(data) =>
        i18nVar.set(data)
        isLoadedVar.set(true)
      case Left(_) =>
        isLoadedVar.set(true)
end I18nClient
