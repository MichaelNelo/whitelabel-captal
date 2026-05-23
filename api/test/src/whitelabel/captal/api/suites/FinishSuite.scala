package whitelabel.captal.api.suites

import io.circe.Json
import io.circe.parser.parse
import whitelabel.captal.api.TestFixtures
import whitelabel.captal.api.TestHelpers.*
import zio.test.*

object FinishSuite:
  private val FinishedProcessEventType = "user.finished_process"

  private def findFinishedEvent(rows: List[whitelabel.captal.infra.EventLogRow]) = rows
    .filter(_.eventType == FinishedProcessEventType)

  private def parseEventData(row: whitelabel.captal.infra.EventLogRow): Json = parse(row.eventData)
    .toOption
    .get

  val suite: Spec[TestEnv, Throwable] =
    zio
      .test
      .suite("Finish Endpoint")(
        test("Happy path — identification + video + advertiser survey → finish emits full event"):
          for
            _       <- TestFixtures.seedEmailSurvey
            fixture <- TestFixtures.seedAdvertiserWithVideoAndSurvey(questionCount = 1)
            backend <- testBackend
            cookie  <- createSession(backend)
            _       <- getNextSurvey(backend, cookie)
            _       <- postEmailAnswer(backend, cookie, "finish-happy@example.com")
            _       <- getNextVideo(backend, cookie)
            _       <- postMarkVideoWatched(backend, cookie, 15, completed = true)
            _       <- getNextAdvertiserSurvey(backend, cookie)
            _       <- postAdvertiserAnswer(backend, cookie, fixture.optionIds.head.head.asString)
            finishResp <- postFinish(backend, cookie)
            eventLog   <- TestFixtures.queryEventLog
          yield
            val finishedRows = findFinishedEvent(eventLog)
            val data         = parseEventData(finishedRows.head)
            val videoIdJson  = data.hcursor.downField("videoId").focus.getOrElse(Json.Null)
            val questionIds = data
              .hcursor
              .downField("answeredQuestionIds")
              .as[List[Json]]
              .toOption
              .getOrElse(Nil)
            assertTrue(
              finishResp.code.isSuccess,
              finishedRows.size == 1,
              !videoIdJson.isNull,
              videoIdJson.asString.contains(fixture.videoId.asString),
              questionIds.size == 2
            )
        ,
        test("Reject when in Welcome phase"):
          for
            backend    <- testBackend
            cookie     <- createSession(backend)
            finishResp <- postFinish(backend, cookie)
            eventLog   <- TestFixtures.queryEventLog
          yield assertTrue(
            !finishResp.code.isSuccess,
            finishResp.body.contains("wrong_phase"),
            findFinishedEvent(eventLog).isEmpty
          )
        ,
        test("Reject when in IdentificationQuestion phase"):
          for
            _          <- TestFixtures.seedEmailSurvey
            backend    <- testBackend
            cookie     <- createSession(backend)
            _          <- getNextSurvey(backend, cookie)
            finishResp <- postFinish(backend, cookie)
            eventLog   <- TestFixtures.queryEventLog
          yield assertTrue(
            !finishResp.code.isSuccess,
            finishResp.body.contains("wrong_phase"),
            findFinishedEvent(eventLog).isEmpty
          )
        ,
        test("Identification only — no video, no survey → finish has only email answer, no video"):
          for
            _          <- TestFixtures.seedEmailSurvey
            backend    <- testBackend
            cookie     <- createSession(backend)
            _          <- getNextSurvey(backend, cookie)
            _          <- postEmailAnswer(backend, cookie, "finish-id-only@example.com")
            // No videos seeded → /api/video/next cascades to Phase.Ready via fallbackFromVideo
            _          <- getNextVideo(backend, cookie)
            finishResp <- postFinish(backend, cookie)
            eventLog   <- TestFixtures.queryEventLog
          yield
            val finishedRows = findFinishedEvent(eventLog)
            val data         = parseEventData(finishedRows.head)
            val videoIdJson  = data.hcursor.downField("videoId").focus.getOrElse(Json.Null)
            val questionIds = data
              .hcursor
              .downField("answeredQuestionIds")
              .as[List[Json]]
              .toOption
              .getOrElse(Nil)
            assertTrue(
              finishResp.code.isSuccess,
              finishedRows.size == 1,
              videoIdJson.isNull,
              questionIds.size == 1
            )
        ,
      )
end FinishSuite
