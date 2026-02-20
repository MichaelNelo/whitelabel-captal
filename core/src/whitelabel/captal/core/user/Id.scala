package whitelabel.captal.core.user

import java.util.UUID

opaque type Id = UUID
object Id:
  def apply(value: UUID): Id = value
  def generate: Id = UUID.randomUUID()
  def fromString(s: String): Option[Id] = scala.util.Try(UUID.fromString(s)).toOption

  extension (id: Id)
    def value: UUID = id
    def asString: String = id.toString

opaque type SessionId = UUID
object SessionId:
  def apply(value: UUID): SessionId = value
  def generate: SessionId = UUID.randomUUID()
  def fromString(s: String): Option[SessionId] = scala.util.Try(UUID.fromString(s)).toOption

  extension (id: SessionId)
    def value: UUID = id
    def asString: String = id.toString

opaque type DeviceId = String
object DeviceId:
  def apply(value: String): DeviceId = value

  extension (id: DeviceId)
    def value: String = id

opaque type Email = String
object Email:
  private val emailRegex = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$".r

  def fromString(value: String): Either[String, Email] =
    if emailRegex.matches(value) then
      Right(value)
    else
      Left(s"Invalid email format: $value")

  def unsafeFrom(value: String): Email = value

  extension (email: Email)
    def value: String = email
