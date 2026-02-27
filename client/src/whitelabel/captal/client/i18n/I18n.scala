package whitelabel.captal.client.i18n

import com.raquo.laminar.api.L.*

object I18n:
  private val localeVar: Var[String] = Var(Messages.defaultLocale)

  val locale: Signal[String] = localeVar.signal

  def setLocale(code: String): Unit = localeVar.set(code)

  def currentLocale: String = localeVar.now()

  /** Reactive translation - updates when locale changes */
  def t(key: String): Signal[String] =
    locale.map(l => Messages.get(key, l))

  /** Static translation - uses current locale */
  def ts(key: String): String =
    Messages.get(key, localeVar.now())
