package whitelabel.captal.core.application

import cats.data.NonEmptyChain
import whitelabel.captal.core.{
  advertiser,
  device,
  i18n,
  location,
  recovery,
  survey,
  terms,
  user,
  video
}

enum Error:
  case NoSurveyAssigned
  case UserNotIdentified

  /** The device has not accepted the terms of service in force. */
  case TermsNotAccepted

  /** No version of the terms of service exists to accept. */
  case NoTermsOfService
  case InvalidEmailFormat(value: String)
  case Survey(errors: NonEmptyChain[survey.Error])
  case User(errors: NonEmptyChain[user.Error])
  case Video(errors: NonEmptyChain[video.Error])
  case Recovery(errors: NonEmptyChain[recovery.Error])
  case Location(errors: NonEmptyChain[location.Error])
  case Terms(errors: NonEmptyChain[terms.Error])
  case I18n(errors: NonEmptyChain[i18n.Error])
  case Advertiser(errors: NonEmptyChain[advertiser.Error])
  case SurveyAuthoring(errors: NonEmptyChain[survey.authoring.Error])

  /** The page was not reached through the network's redirect: what UniFi always sends — the
    * device's MAC, the access point's — is `missing` or unreadable, so there is no device to grant
    * access to.
    */
  case InvalidClient(missing: List[RedirectField])

  /** The redirect came through an access point that is not one of the location's, so no controller
    * could grant the guest access.
    */
  case UnknownAccessPoint(apMac: device.Mac)

  /** The location the portal serves is not deployed — still being stood up, or an operation on it
    * did not finish — so it admits no guest.
    */
  case LocationNotDeployed(id: location.LocationId)
end Error

/** What UniFi's redirect always carries. */
enum RedirectField:
  case ClientMac,
    AccessPoint
