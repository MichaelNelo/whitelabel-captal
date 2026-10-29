package whitelabel.captal.api

import zio.*

/** Test stand-in for the location's `unifi_is_enabled` flag, which the tests' location-less
  * [[whitelabel.captal.api.session.CurrentLocation]] has no row to read from. Starts enabled.
  */
final class TestUnifiSwitch(private val state: Ref[Boolean]):
  val enabled: Task[Boolean] = state.get
  def set(value: Boolean): UIO[Unit] = state.set(value)

object TestUnifiSwitch:
  val layer: ULayer[TestUnifiSwitch] = ZLayer.fromZIO(Ref.make(true).map(TestUnifiSwitch(_)))

  def set(value: Boolean): URIO[TestUnifiSwitch, Unit] = ZIO.serviceWithZIO[TestUnifiSwitch](
    _.set(value))
