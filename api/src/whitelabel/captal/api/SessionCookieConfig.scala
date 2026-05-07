package whitelabel.captal.api

import sttp.model.headers.CookieValueWithMeta
import sttp.tapir.{EndpointInput, EndpointOutput, cookie, setCookieOpt}

/** Per-deployment session cookie name + path. Derived from `LOCATION_SLUG` so that two locations
  * served on the same domain (e.g. `/cafe-centro/` and `/valmy/` under `production.captal...`) have
  * isolated cookies in the browser — different name AND different path means the browser stores
  * them as separate entries and only sends each to its own location's URLs.
  */
final case class SessionCookieConfig(name: String, path: String):
  def asMeta(value: String): CookieValueWithMeta = CookieValueWithMeta.unsafeApply(
    value,
    path = Some(path))

  val tapirInput: EndpointInput[Option[String]] = cookie[Option[String]](name)
  val tapirOutput: EndpointOutput[Option[CookieValueWithMeta]] = setCookieOpt(name)

object SessionCookieConfig:
  def fromSlug(slug: Option[String]): SessionCookieConfig =
    slug match
      case Some(s) =>
        SessionCookieConfig(s"captal_session_$s", s"/$s")
      case None =>
        SessionCookieConfig("captal_session", "/")
