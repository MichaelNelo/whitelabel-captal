package whitelabel.captal.core.location

import whitelabel.captal.core.device.Mac

enum Error:
  /** Not lowercase kebab-case, or blank. */
  case InvalidSlug(slug: String)
  case BlankName

  /** Another location, named `by`, has the slug. */
  case SlugTaken(slug: Slug, by: String)
  case NotFound(slug: Slug)

  /** Not an http or https URL. */
  case InvalidRedirectUrl(url: String)

  /** Not a whole number of minutes from 1 up. */
  case InvalidDuration(minutes: Int)
  case BlankHost
  case BlankSiteId
  case InvalidPort(port: Int)

  /** The access point has no token stored yet, and none was given. */
  case TokenRequired(mac: Mac)
  case NoSuchAccessPoint(mac: Mac)

  /** The location already has an access point with this MAC. */
  case AccessPointExists(mac: Mac)

  /** The deployed location `by` takes its guests through this access point: one access point leads
    * to one deployed location, which is what the portal dispatcher counts on.
    */
  case AccessPointHeld(mac: Mac, by: Slug)

  /** The location is deployed and this is its only access point: without it every guest would be
    * turned away.
    */
  case LastAccessPoint(mac: Mac)
end Error
