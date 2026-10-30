package whitelabel.captal.api.suites

import whitelabel.captal.api.TestFixtures
import whitelabel.captal.api.TestHelpers.*
import whitelabel.captal.core.user
import zio.test.*

/** Devices are found again by their fingerprint, and users are linked to the devices they are
  * identified on. The suite's server runs the default policy, `ByClickId`.
  */
object DeviceSuite:
  val suite: Spec[TestEnv, Throwable] =
    zio
      .test
      .suite("Devices")(
        test("a click id seen before is the same device, whatever the MAC"):
          for
            backend <- testBackend
            first   <- createSessionFrom(backend, "click-a", "AA:AA:AA:AA:AA:01")
            again   <- createSessionFrom(backend, "click-a", "AA:AA:AA:AA:AA:02")
            other   <- createSessionFrom(backend, "click-b", "AA:AA:AA:AA:AA:01")
            state   <- TestFixtures.queryDbState
            devices <- TestFixtures.getDevices
            deviceOf =
              (cookie: String) =>
                state.sessions.find(_.id == user.SessionId.unsafe(cookie)).map(_.deviceId)
          yield assertTrue(
            deviceOf(first) == deviceOf(again),
            deviceOf(first) != deviceOf(other),
            devices.size == 2,
            // The field the policy did not compare follows the device.
            devices.exists(d => d.clickId == "click-a" && d.clientMac == "AA:AA:AA:AA:AA:02")
          )
        ,
        test("answering the email links the user to the device, and the answer records it"):
          for
            _       <- TestFixtures.seedEmailSurvey
            backend <- testBackend
            cookie  <- createSessionFrom(backend, "click-email", "BB:BB:BB:BB:BB:01")
            _       <- getNextSurvey(backend, cookie)
            _       <- postEmailAnswer(backend, cookie, "linked@example.com")
            state   <- TestFixtures.queryDbState
            links   <- TestFixtures.getDeviceLinks
            session = state.sessions.find(_.id == user.SessionId.unsafe(cookie)).get
          yield assertTrue(
            session.userId.isDefined,
            links.exists(l => l.userId == session.userId.get && l.deviceId == session.deviceId),
            state
              .answers
              .exists(a => a.userId == session.userId.get && a.deviceId.contains(session.deviceId))
          )
        ,
        test("an email answered before recognizes that user, on a new device too"):
          for
            _       <- TestFixtures.seedEmailSurvey
            backend <- testBackend
            first   <- createSessionFrom(backend, "click-phone", "CC:CC:CC:CC:CC:01")
            _       <- getNextSurvey(backend, first)
            _       <- postEmailAnswer(backend, first, "returning@example.com")
            second  <- createSessionFrom(backend, "click-laptop", "CC:CC:CC:CC:CC:02")
            _       <- getNextSurvey(backend, second)
            _       <- postEmailAnswer(backend, second, "returning@example.com")
            state   <- TestFixtures.queryDbState
            links   <- TestFixtures.getDeviceLinks
            sessionOf =
              (cookie: String) => state.sessions.find(_.id == user.SessionId.unsafe(cookie)).get
            owner = sessionOf(first).userId.get
          yield assertTrue(
            // One user, not a second one for the same email.
            state.users.count(_.email.exists(_.value == "returning@example.com")) == 1,
            sessionOf(second).userId.contains(owner),
            // Linked on both devices, each its own pair.
            links.count(_.userId == owner) == 2,
            // The email answered again is the same answer: kept once, under the recognized user,
            // and nothing left under a stray id.
            state.answers.count(_.userId == owner) == 1,
            state.answers.forall(a => state.users.exists(_.id == a.userId))
          )
      )
end DeviceSuite
