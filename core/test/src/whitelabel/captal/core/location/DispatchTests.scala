package whitelabel.captal.core.location

import java.time.Instant

import utest.*

object DispatchTests extends TestSuite:
  import Dispatch.{Holder, Standing}

  private def at(day: Int) = Instant.parse(f"2026-01-$day%02dT00:00:00Z")

  private def holder(
      slug: String,
      standing: Standing,
      day: Int,
      redirectUrl: Option[String] = None) = Holder(
    Slug.unsafe(slug),
    standing,
    at(day),
    redirectUrl)

  private val Landing = "https://venue.example/landing?src=wifi"

  val tests = Tests:
    test("destination"):
      test("the newest deployed holder, over a newer paused one and an older deployed one"):
        val holders = List(
          holder("old", Standing.Deployed, 1),
          holder("new", Standing.Deployed, 2),
          holder("resting", Standing.Paused, 3))
        assert(Dispatch.destination(holders) == Some(Destination.Portal(Slug.unsafe("new"))))
      test("the newest paused holder when none is deployed, to its page and not its own address"):
        val holders = List(
          holder("older", Standing.Paused, 1),
          holder("resting", Standing.Paused, 2, Some(Landing)),
          holder("draft", Standing.Other, 3))
        assert(Dispatch.destination(holders) == Some(Destination.Portal(Slug.unsafe("resting"))))
      test("a deployed holder's own address"):
        val sent = Dispatch.destination(List(holder("cafe", Standing.Deployed, 1, Some(Landing))))
        assert(
          sent ==
            Some(
              Destination.Elsewhere(
                RedirectTarget("https", "venue.example", 443, "/landing", Some("src=wifi")))))
      test("nothing when no holder is deployed or paused"):
        assert(Dispatch.destination(List(holder("draft", Standing.Other, 1))) == None)
        assert(Dispatch.destination(Nil) == None)

    test("RedirectTarget.of"):
      test("splits an address into what a redirect action takes"):
        assert(
          RedirectTarget.of("http://Venue.example:8080") ==
            Right(RedirectTarget("http", "venue.example", 8080, "/", None)))
      test("refuses what a redirect action cannot carry"):
        import RedirectTarget.Problem
        assert(RedirectTarget.of("ftp://venue.example") == Left(Problem.NotHttp))
        assert(RedirectTarget.of("https://venue.example/#top") == Left(Problem.Fragment))
        assert(RedirectTarget.of("https://venue.example/a%20b") == Left(Problem.PathCharacter))
        assert(RedirectTarget.of("https://venue_example/") == Left(Problem.BadHost))
        assert(RedirectTarget.of("https://venue.example:0/") == Left(Problem.BadPort))
        assert(RedirectTarget.of(s"https://venue.example/${"a" * 128}") == Left(Problem.LongPath))
        assert(
          RedirectTarget.of(s"https://venue.example/?q=${"a" * 120}") == Left(Problem.LongQuery))
        assert(RedirectTarget.of("https://venue.example/?q=a b") == Left(Problem.QueryCharacter))
end DispatchTests
