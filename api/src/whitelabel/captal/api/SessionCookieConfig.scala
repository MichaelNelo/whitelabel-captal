package whitelabel.captal.api

import zio.http.{Cookie, Header, Path}

/** Per-deployment session cookie name + path. Derived from `LOCATION_SLUG` so that two locations
  * served on the same domain (e.g. `/cafe-centro/` and `/valmy/` under `production.captal...`) have
  * isolated cookies in the browser — different name AND different path means the browser stores
  * them as separate entries and only sends each to its own location's URLs.
  */
final case class SessionCookieConfig(name: String, path: String):

  /** This deployment's session cookie, picked out of the request's whole `Cookie` header.
    *
    * By hand, because zio-http's cookie codec hands over every cookie the browser sent rather than
    * one by name — where tapir took a `cookie[Option[String]]` input per cookie. Which is the
    * easier end of the trade: `/api/status` reads two of them, and reading two out of one header
    * is two calls to this rather than two inputs on the endpoint.
    *
    * The header is `Option` on purpose: a required codec would answer a request carrying no cookies
    * with a 400 from the codec, and a missing session is something each route decides about —
    * `OnMissing.Create` for the status route, a failure for the rest.
    */
  def valueFrom(header: Option[Header.Cookie]): Option[String] = header.flatMap(
    _.value.find(_.name == name).map(_.content))

  /** Path and nothing else, which is what the tapir `CookieValueWithMeta` this replaces attached.
    *
    * Name and path are what the browser keys an entry on, so the two have to agree for every route
    * that writes this cookie — see the test comparing the attributes a converted route sets against
    * the ones the rest do.
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
