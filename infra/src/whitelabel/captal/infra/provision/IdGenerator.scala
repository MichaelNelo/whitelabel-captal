package whitelabel.captal.infra.provision

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/** Deterministic UUID v5 generation for provisioning. Uses a fixed namespace UUID and generates
  * reproducible IDs from entity keys.
  */
object IdGenerator:
  // Captal namespace UUID for deterministic ID generation
  private val Namespace: UUID = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8")

  /** Generate a deterministic UUID v5 from a key string. */
  def uuid5(key: String): String =
    val md = MessageDigest.getInstance("SHA-1")
    md.update(toBytes(Namespace))
    md.update(key.getBytes(StandardCharsets.UTF_8))
    val hash = md.digest()

    // Set version 5
    hash(6) = ((hash(6) & 0x0f) | 0x50).toByte
    // Set variant
    hash(8) = ((hash(8) & 0x3f) | 0x80).toByte

    val msb = bytesToLong(hash, 0)
    val lsb = bytesToLong(hash, 8)
    new UUID(msb, lsb).toString

  /** Generate ID for a location entity */
  def locationId(slug: String): String = uuid5(s"location:$slug")

  /** Generate ID for an advertiser entity */
  def advertiserId(slug: String): String = uuid5(s"advertiser:$slug")

  /** Generate ID for a system survey (email, profiling, location) */
  def surveyId(category: String): String = uuid5(s"survey:$category")

  /** Generate ID for a location-scoped video */
  def videoId(locationSlug: String, advertiserSlug: String, videoSlug: String): String = uuid5(
    s"video:$locationSlug/$advertiserSlug/$videoSlug")

  /** Generate ID for a promo video */
  def promoVideoId(locationSlug: String, videoSlug: String): String = uuid5(
    s"video:$locationSlug/promo/$videoSlug")

  /** Generate ID for an advertiser survey (scoped to video) */
  def advertiserSurveyId(locationSlug: String, advertiserSlug: String, videoSlug: String): String =
    uuid5(s"survey:$locationSlug/$advertiserSlug/$videoSlug")

  /** Generate ID for a question within a survey */
  def questionId(surveyKey: String, index: Int): String = uuid5(s"question:$surveyKey/$index")

  /** Generate ID for a question option */
  def optionId(surveyKey: String, questionIndex: Int, optionIndex: Int): String = uuid5(
    s"option:$surveyKey/$questionIndex/$optionIndex")

  /** Generate ID for a localized text entry */
  def localizedTextId(entityId: String, locale: String, suffix: String = ""): String =
    val key =
      if suffix.isEmpty then
        s"lt:$entityId:$locale"
      else
        s"lt:$entityId:$suffix:$locale"
    uuid5(key)

  /** Generate ID for a question rule */
  def ruleId(questionKey: String, ruleIndex: Int): String = uuid5(s"rule:$questionKey/$ruleIndex")

  private def toBytes(uuid: UUID): Array[Byte] =
    val bb = java.nio.ByteBuffer.allocate(16)
    bb.putLong(uuid.getMostSignificantBits)
    bb.putLong(uuid.getLeastSignificantBits)
    bb.array()

  private def bytesToLong(bytes: Array[Byte], offset: Int): Long =
    var result = 0L
    for i <- 0 until 8 do
      result = (result << 8) | (bytes(offset + i) & 0xff)
    result
end IdGenerator
