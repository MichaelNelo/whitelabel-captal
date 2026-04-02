package whitelabel.captal.infra.provision

import io.circe.*
import io.circe.generic.semiauto.*

// ─────────────────────────────────────────────────────────────────────────────
// YAML models for provisioning
// ─────────────────────────────────────────────────────────────────────────────

/** Location metadata — slug comes from LOCATION_SLUG env var */
final case class LocationYaml(name: String, ap_mac: Option[String] = None)
object LocationYaml:
  given Decoder[LocationYaml] = deriveDecoder

/** Flat key-value translations for the frontend */
type I18nYaml = Map[String, String]

/** Question option in a survey YAML */
final case class QuestionOptionYaml(text: Map[String, String])
object QuestionOptionYaml:
  given Decoder[QuestionOptionYaml] = deriveDecoder

/** Question rule definition */
final case class QuestionRuleYaml(`type`: String, value: Option[Json] = None)
object QuestionRuleYaml:
  given Decoder[QuestionRuleYaml] = deriveDecoder

/** Question definition in a survey YAML */
final case class QuestionYaml(
    `type`: String,
    points: Int = 10,
    required: Boolean = true,
    text: Map[String, String],
    description: Option[Map[String, String]] = None,
    placeholder: Option[Map[String, String]] = None,
    hierarchyLevel: Option[String] = None,
    options: Option[List[QuestionOptionYaml]] = None,
    rules: Option[List[QuestionRuleYaml]] = None)
object QuestionYaml:
  given Decoder[QuestionYaml] = deriveDecoder

/** Identification survey (email, profiling, location) */
final case class SurveyYaml(category: String, questions: List[QuestionYaml])
object SurveyYaml:
  given Decoder[SurveyYaml] = deriveDecoder

/** Advertiser metadata */
final case class AdvertiserYaml(name: String, priority: Int = 10)
object AdvertiserYaml:
  given Decoder[AdvertiserYaml] = deriveDecoder

/** Survey attached to a video (separate YAML file) */
final case class VideoSurveyYaml(
    name: Option[String] = None,
    questions: List[QuestionYaml])
object VideoSurveyYaml:
  given Decoder[VideoSurveyYaml] = deriveDecoder

/** Advertiser video metadata (video.yaml) */
final case class VideoYaml(
    advertiser: String,
    url: String,
    duration: Int,
    minWatch: Int = 5,
    showCountdown: Boolean = true,
    noRepeatSeconds: Option[Int] = None,
    priority: Int = 10,
    title: Map[String, String],
    description: Option[Map[String, String]] = None)
object VideoYaml:
  given Decoder[VideoYaml] = deriveDecoder

/** Promotional video (no advertiser) */
final case class PromoVideoYaml(
    url: String,
    duration: Int,
    minWatch: Int = 3,
    showCountdown: Boolean = false,
    priority: Int = 1,
    title: Map[String, String],
    description: Option[Map[String, String]] = None)
object PromoVideoYaml:
  given Decoder[PromoVideoYaml] = deriveDecoder
