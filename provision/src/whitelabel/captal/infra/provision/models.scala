package whitelabel.captal.infra.provision

import io.circe.*
import io.circe.generic.semiauto.*

// ─────────────────────────────────────────────────────────────────────────────
// YAML models for provisioning
// ─────────────────────────────────────────────────────────────────────────────

/** Location metadata — slug comes from LOCATION_SLUG env var */
final case class LocationYaml(
    name: String,
    desiredCount: Option[Int] = None,
    unifi: Option[UnifiYaml] = None)
object LocationYaml:
  given Decoder[LocationYaml] = deriveDecoder

/** UniFi Controller access config for guest authorization (Integration v1 API) + captive portal
  * routing.
  *
  *   - `apMac`: MAC of the AP that the UCG redirects from. Used by the captive portal dispatcher
  *     Lambda to resolve which location's slug a request belongs to. Required when this location is
  *     reachable via the dispatcher (i.e. effectively always).
  *   - `siteId`: UUID returned by `GET /proxy/network/integration/v1/sites`. The legacy site name
  *     ("default") does NOT work — the operator looks it up once and copies.
  *   - `redirectUrl`: optional override for the dispatcher's redirect target. When set, the Lambda
  *     302s the device to this URL instead of `<cloudfront-host>/<slug>/`, preserving all UCG query
  *     params (`ap`, `id`, `t`, `url`, `ssid`). Use when the location runs a different portal SPA
  *     (e.g. branded marketing landing) but still wants to route via captal's GA IP.
  */
final case class UnifiYaml(
    host: String,
    apiToken: String,
    apMac: Option[String] = None,
    port: Option[Int] = None,
    siteId: Option[String] = None,
    redirectUrl: Option[String] = None,
    defaultDurationMinutes: Option[Int] = None)
object UnifiYaml:
  given Decoder[UnifiYaml] = deriveDecoder

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
final case class VideoSurveyYaml(name: Option[String] = None, questions: List[QuestionYaml])
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
    description: Option[Map[String, String]] = None,
    productCampaignId: Option[String] = None)
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
