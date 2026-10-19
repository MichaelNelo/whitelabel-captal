package whitelabel.captal.api.session

import zio.*
import zio.http.{Cookie, Header, Path}

/** Cross-location user cookie. Unlike `SessionCookieConfig` (slug-aware, path-scoped), this one is
  * broadcast to ALL locations under the same domain via `Path=/`, so a returning user who already
  * answered the email identification at another location skips that question.
  *
  * Set when email is answered (`answerEmail`); read on session creation (`status`).
  */
final case class UserCookieConfig(name: String, path: String, maxAgeSeconds: Long):

  /** This cookie out of the request's `Cookie` header. See `SessionCookieConfig.valueFrom`. */
  def valueFrom(header: Option[Header.Cookie]): Option[String] = header.flatMap(
    _.value.find(_.name == name).map(_.content))

  /** Sets `Path`, `Secure`, `HttpOnly`, `Max-Age` and `SameSite=Lax` — all five load-bearing: a
    * `Path` other than `/` is not sent across locations, and without `Max-Age` the cookie dies with
    * the tab.
    */
  def asSetCookie(value: String): Header.SetCookie = Header.SetCookie(
    Cookie.Response(
      name = name,
      content = value,
      path = Some(Path.decode(path)),
      isSecure = true,
      isHttpOnly = true,
      maxAge = Some(Duration.fromSeconds(maxAgeSeconds)),
      sameSite = Some(Cookie.SameSite.Lax)
    ))
end UserCookieConfig

object UserCookieConfig:
  val default: UserCookieConfig = UserCookieConfig(
    name = "captal_user",
    path = "/",
    maxAgeSeconds = 30L * 24L * 3600L)

  val layer: ULayer[UserCookieConfig] = ZLayer.succeed(default)
