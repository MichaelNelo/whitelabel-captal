package whitelabel.captal.api.session

import zio.http.{Cookie, Header, Path}

/** Per-deployment session cookie name + path, derived from `LOCATION_SLUG`. Different name AND
  * different path keep two locations served on the same domain isolated: the browser stores them as
  * separate entries and only sends each to its own location's URLs.
  */
final case class SessionCookieConfig(name: String, path: String):

  /** This deployment's session cookie, picked by name out of the request's whole `Cookie` header.
    *
    * The header must stay an `Option` input: a required codec answers a request carrying no cookies
    * with a 400, where a missing session is each route's own decision — `OnMissing.Create` for
    * `/api/status`, a failure for the rest.
    */
  def valueFrom(header: Option[Header.Cookie]): Option[String] = header.flatMap(
    _.value.find(_.name == name).map(_.content))

  /** Sets name, value and path, and no other attribute. Name and path are what the browser keys an
    * entry on, so every route writing this cookie must set the same pair.
    */
  def asSetCookie(value: String): Header.SetCookie = Header.SetCookie(
    Cookie.Response(name, value, path = Some(Path.decode(path))))
end SessionCookieConfig

object SessionCookieConfig:
  def fromSlug(slug: Option[String]): SessionCookieConfig =
    slug match
      case Some(s) =>
        SessionCookieConfig(s"captal_session_$s", s"/$s")
      case None =>
        SessionCookieConfig("captal_session", "/")
