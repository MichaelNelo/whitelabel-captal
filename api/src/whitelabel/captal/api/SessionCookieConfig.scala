package whitelabel.captal.api

import sttp.model.headers.CookieValueWithMeta
import sttp.tapir.{EndpointInput, EndpointOutput, cookie, setCookieOpt}
import zio.http.{Cookie, Header, Path}

/** Per-deployment session cookie name + path. Derived from `LOCATION_SLUG` so that two locations
  * served on the same domain (e.g. `/cafe-centro/` and `/valmy/` under `production.captal...`) have
  * isolated cookies in the browser — different name AND different path means the browser stores
  * them as separate entries and only sends each to its own location's URLs.
  */
final case class SessionCookieConfig(name: String, path: String):

  val tapirInput: EndpointInput[Option[String]] = cookie[Option[String]](name)
  val tapirOutput: EndpointOutput[Option[CookieValueWithMeta]] = setCookieOpt(name)

  def asMeta(value: String): CookieValueWithMeta = CookieValueWithMeta.unsafeApply(
    value,
    path = Some(path))

  /** `asMeta` for a converted route: the same cookie, as the header zio-http sets.
    *
    * Path and nothing else, because that is what `asMeta` attaches and the two have to be the same
    * cookie — the browser keys an entry on name and path, so a route that set one of these and a
    * route that set the other would be writing to two separate entries.
    */
  def asSetCookie(value: String): Header.SetCookie = Header.SetCookie(
    Cookie.Response(name, value, path = Some(Path.decode(path))))

  /** This deployment's session cookie, picked out of the request's whole `Cookie` header.
    *
    * What `tapirInput` does for a tapir route, by hand, because zio-http's cookie codec hands over
    * every cookie the browser sent rather than one by name. The header is `Option` on purpose: a
    * required codec would answer a request carrying no cookies with a 400 from the codec, and a
    * missing session is a `SessionMissing` the route decides how to treat — `OnMissing.Create` for
    * the status route, a failure for the rest.
    */
  def valueFrom(header: Option[Header.Cookie]): Option[String] = header.flatMap(
    _.value.find(_.name == name).map(_.content))
end SessionCookieConfig

object SessionCookieConfig:
  def fromSlug(slug: Option[String]): SessionCookieConfig =
    slug match
      case Some(s) =>
        SessionCookieConfig(s"captal_session_$s", s"/$s")
      case None =>
        SessionCookieConfig("captal_session", "/")
