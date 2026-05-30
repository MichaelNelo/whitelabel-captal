package whitelabel.captal.api

import sttp.model.headers.Cookie.SameSite
import sttp.model.headers.CookieValueWithMeta
import sttp.tapir.{EndpointInput, EndpointOutput, cookie, setCookieOpt}
import zio.*

/** Cross-location user cookie. Unlike `SessionCookieConfig` (which is slug-aware and scoped by
  * path), this cookie is broadcast to ALL locations under the same domain via `Path=/`. Used to
  * recognize a returning user who has already answered the email identification at another location
  * → skip that question on subsequent visits.
  *
  * Set when email is answered (`answerEmailRoute`); read on session creation (`statusRoute`).
  */
final case class UserCookieConfig(name: String, path: String, maxAgeSeconds: Long):
  def asMeta(value: String): CookieValueWithMeta = CookieValueWithMeta.unsafeApply(
    value,
    path = Some(path),
    maxAge = Some(maxAgeSeconds),
    httpOnly = true,
    secure = true,
    sameSite = Some(SameSite.Lax))

  val tapirInput: EndpointInput[Option[String]] = cookie[Option[String]](name)
  val tapirOutput: EndpointOutput[Option[CookieValueWithMeta]] = setCookieOpt(name)

object UserCookieConfig:
  val default: UserCookieConfig = UserCookieConfig(
    name = "captal_user",
    path = "/",
    maxAgeSeconds = 30L * 24L * 3600L)

  val layer: ULayer[UserCookieConfig] = ZLayer.succeed(default)
