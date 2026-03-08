package whitelabel.captal.core.user

import java.util.UUID

opaque type Id = UUID
object Id:
  def apply(value: UUID): Id = value
  def generate: Id = UUID.randomUUID()
  def fromString(s: String): Option[Id] = scala.util.Try(UUID.fromString(s)).toOption
  def unsafe(s: String): Id = UUID.fromString(s)

  extension (id: Id)
    def value: UUID = id
    def asString: String = id.toString

opaque type SessionId = UUID
object SessionId:
  def apply(value: UUID): SessionId = value
  def generate: SessionId = UUID.randomUUID()
  def fromString(s: String): Option[SessionId] = scala.util.Try(UUID.fromString(s)).toOption
  def unsafe(s: String): SessionId = UUID.fromString(s)

  extension (id: SessionId)
    def value: UUID = id
    def asString: String = id.toString

opaque type DeviceId = UUID
object DeviceId:
  // UUID namespace for generating UUIDv5 from User-Agent
  private val Namespace: UUID = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8")

  def apply(value: UUID): DeviceId = value
  def fromString(s: String): Option[DeviceId] = scala.util.Try(UUID.fromString(s)).toOption
  def unsafe(s: String): DeviceId = UUID.fromString(s)

  /** Generate a UUIDv5 from a User-Agent string */
  def fromUserAgent(userAgent: String): DeviceId =
    UUID.nameUUIDFromBytes((Namespace.toString + userAgent).getBytes)

  extension (id: DeviceId)
    def value: UUID = id
    def asString: String = id.toString

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
