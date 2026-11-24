package whitelabel.captal.platform

import whitelabel.captal.core.device.Mac

/** How `access_points` writes an access point's id: its MAC, as [[Mac.show]] writes it. */
object AccessPoints:
  def idOf(mac: Mac): String = mac.show

/** How `active_access_points.state` writes how far the location that took an access point is:
  * `Deploying` while the deploy or the resume that took it runs, `Deployed` once it succeeded.
  */
object ActiveAccessPoints:
  val Deploying = "deploying"
  val Deployed = "deployed"
