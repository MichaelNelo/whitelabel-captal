package whitelabel.captal.api

import zio.*
import zio.http.{Cookie, Header, Path}

/** Cross-location user cookie. Unlike `SessionCookieConfig` (which is slug-aware and scoped by
  * path), this cookie is broadcast to ALL locations under the same domain via `Path=/`. Used to
  * recognize a returning user who has already answered the email identification at another location
  * → skip that question on subsequent visits.
  *
  * Set when email is answered (`answerEmail`); read on session creation (`status`).
  */
final case class UserCookieConfig(name: String, path: String, maxAgeSeconds: Long):

  /** This cookie out of the request's `Cookie` header. See `SessionCookieConfig.valueFrom`. */
  def valueFrom(header: Option[Header.Cookie]): Option[String] = header.flatMap(
    _.value.find(_.name == name).map(_.content))

  /** All five attributes, unlike the session cookie's path-only equivalent.
    *
    * Each one is load-bearing here, because this cookie is the whole reason a returning visitor
    * skips the email question at another location: a `Path` that is not `/` would not be sent
    * across locations, and a missing `Max-Age` would make it a session cookie that dies with the
    * tab.
    */
  def asSetCookie(value: String): Header.SetCookie = Header.SetCookie(
    Cookie.Response(
      name = name,
      content = value,
      path = Some(Path.decode(path)),
      isSecure = true,
      isHttpOnly = true,
      maxAge = Some(Duration.fromSeconds(maxAgeSeconds)),
      sameSite = Some(Cookie.SameSite.Lax)))
end UserCookieConfig

object UserCookieConfig:
  val default: UserCookieConfig = UserCookieConfig(
    name = "captal_user",
    path = "/",
    maxAgeSeconds = 30L * 24L * 3600L)

  val layer: ULayer[UserCookieConfig] = ZLayer.succeed(default)
