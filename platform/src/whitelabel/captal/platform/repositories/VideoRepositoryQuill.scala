package whitelabel.captal.platform.repositories

import io.getquill.*
import whitelabel.captal.core.infrastructure.VideoRepository
import whitelabel.captal.core.survey.AdvertiserId
import whitelabel.captal.core.survey.question.LocalizedText
import whitelabel.captal.core.video.{AdvertiserVideo, VideoToWatch, VideoType}
import whitelabel.captal.core.{user, video}
import whitelabel.captal.platform.*
import whitelabel.captal.platform.schema.core.given
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.schema.{
  QuillSqlite,
  castAsReal,
  castAsRealOpt,
  nullif,
  sqliteAbs,
  sqliteLog,
  sqliteRandom,
  strGt
}
import whitelabel.captal.platform.session.SessionContext
import zio.*

object VideoRepositoryQuill:

  // Picks the preferred text: the user's locale, falling back to English.
  private def selectPreferredText(
      texts: List[LocalizedTextRow],
      preferredLocale: String): Option[LocalizedText] = texts
    .sortBy(t =>
      if t.locale == preferredLocale then
        0
      else
        1)
    .headOption
    .map(t => LocalizedText(t.value, t.locale))

  /** The first promo video in rotation order. `promoParam` is always `VideoType.Promo`, passed as a
    * parameter because an enum reaches a query through `lift`, which an object-level quote cannot
    * call.
    */
  inline def firstPromoQuery = quote: (promoParam: VideoType) =>
    query[AdvertiserVideoRow]
      .filter(v => v.status == VideoStatus.Active && v.videoType == promoParam)
      .sortBy(v => (v.priority, v.createdAt))(Ord.asc)
      .take(1)

  // Weighted random ad selection — authenticated user (with EXISTS for unanswered surveys)
  inline def nextAdQueryAuthenticated = quote:
    (userIdParam: user.Id, locationIdParam: Option[String], adParam: VideoType) =>
      val answeredByUser = query[AnswerRow].filter(_.userId == userIdParam).map(_.questionId)
      val advertiserPrioritySum = query[AdvertiserRow].filter(_.isActive == 1).map(_.priority).sum
      query[AdvertiserVideoRow]
        .join(query[AdvertiserRow])
        .on((v, a) => v.advertiserId.contains(a.id) && a.isActive == 1)
        .filter((v, _) =>
          v.status == VideoStatus.Active && v.videoType == adParam &&
            locationIdParam.forall(lid => v.locationId.contains(lid)) &&
            query[SurveyRow]
              .filter(s =>
                s.videoId.exists(_ == sql"${v.id}".as[String]) && s.isActive == 1 &&
                  s.category == SurveyCategory.Advertiser)
              .flatMap(s =>
                query[QuestionRow].filter(q =>
                  q.surveyId == s.id && q.isActive == 1 && !answeredByUser.contains(q.id)))
              .nonEmpty)
        .sortBy((v, a) =>
          -sqliteLog(sqliteAbs(sqliteRandom) + 0.0001) / (
            (castAsReal(a.priority) / nullif(castAsRealOpt(advertiserPrioritySum), 0.0)) * (
              castAsReal(v.priority) /
                nullif(
                  castAsRealOpt(
                    query[AdvertiserVideoRow]
                      .filter(av =>
                        av.advertiserId == v.advertiserId && av.status == VideoStatus.Active)
                      .map(_.priority)
                      .sum),
                  0.0)
            )
          ))(using Ord.asc)
        .take(1)
        .map((v, _) => v)

  def apply(quill: QuillSqlite, ctx: SessionContext): VideoRepository[Task] =
    new VideoRepository[Task]:
      import quill.*

      def findById(id: video.Id): Task[Option[AdvertiserVideo]] =
        run(query[AdvertiserVideoRow].filter(_.id == lift(id)))
          .map(_.headOption.map(toAdvertiserVideo))
          .orDie

      /** Advertiser video first, then the venue's own promo when no advertiser video qualifies. */
      def findNextForUser(userId: user.Id): Task[Option[VideoToWatch]] =
        for
          sessionData <- ctx.getOrFail
          locale = sessionData.locale
          ad     <- findNextAd(userId, locale)
          result <-
            ad match
              case Some(_) =>
                ZIO.succeed(ad)
              case None =>
                // The promo cursor lives on the session row; SessionVideoHandler advances it
                // whenever a promo is watched.
                findNextPromo(sessionData.lastPromoVideoId, locale)
        yield result

      private def findNextAd(userId: user.Id, locale: String): Task[Option[VideoToWatch]] =
        for
          sessionData <- ctx.getOrFail
          locationId = sessionData.locationId
          queryResult <- run(
            nextAdQueryAuthenticated(lift(userId), lift(locationId), lift(VideoType.Ad)))
          result <-
            queryResult match
              case Nil =>
                ZIO.none
              case rows =>
                fetchLocalizedTexts(rows.head, locale).map(Some(_))
        yield result

      private def findNextPromo(
          lastPromoVideoId: Option[video.Id],
          locale: String): Task[Option[VideoToWatch]] =
        lastPromoVideoId match
          case Some(lastId) =>
            val program =
              for
                currentOpt <- run(query[AdvertiserVideoRow].filter(_.id == lift(lastId))).map(
                  _.headOption)
                candidates <-
                  currentOpt match
                    case Some(currentVideo) =>
                      findNextPromoAfter(currentVideo.priority, currentVideo.createdAt)
                    case None =>
                      run(firstPromoQuery(lift(VideoType.Promo)))
                // Fallback to first promo when the cursor walked off the end
                finalRows <-
                  if candidates.isEmpty then
                    run(firstPromoQuery(lift(VideoType.Promo)))
                  else
                    ZIO.succeed(candidates)
                result <-
                  finalRows.headOption match
                    case Some(row) =>
                      fetchLocalizedTexts(row, locale).map(Some(_))
                    case None =>
                      ZIO.none
              yield result
            program.orDie

          case None =>
            run(firstPromoQuery(lift(VideoType.Promo)))
              .flatMap:
                case Nil =>
                  ZIO.none
                case rows =>
                  fetchLocalizedTexts(rows.head, locale).map(Some(_))
              .orDie

      // Plain parameters, so Quill can parse the lift() calls.
      private def findNextPromoAfter(
          priority: Int,
          createdAt: String): Task[List[AdvertiserVideoRow]] = run(
        query[AdvertiserVideoRow]
          // `lift` on the status, unlike the quotes above: Quill folds an `inline val` inside a
          // quoted `inline def` but not inside a `run` written out here.
          .filter(v => v.status == lift(VideoStatus.Active) && v.videoType == lift(VideoType.Promo))
          .filter(v =>
            v.priority > lift(priority) ||
              (v.priority == lift(priority) && strGt(v.createdAt, lift(createdAt))))
          .sortBy(v => (v.priority, v.createdAt))(using Ord.asc)
          .take(1))

      // Fetch localized texts for a video (title and description)
      private def fetchLocalizedTexts(
          videoRow: AdvertiserVideoRow,
          locale: String): Task[VideoToWatch] =
        val videoIdStr = videoRow.id.asString
        for
          titleTexts <- run(
            query[LocalizedTextRow]
              .filter(lt => lt.entityId == lift(videoIdStr) && lt.isActive == 1)
              .filter(lt => lt.locale == lift(locale) || lt.locale == "en"))
          descTexts <- run(
            query[LocalizedTextRow]
              .filter(lt => lt.entityId == lift(videoIdStr + "_desc") && lt.isActive == 1)
              .filter(lt => lt.locale == lift(locale) || lt.locale == "en"))
        yield toVideoToWatch(
          videoRow,
          selectPreferredText(titleTexts, locale),
          selectPreferredText(descTexts, locale))

  private def toAdvertiserVideo(row: AdvertiserVideoRow): AdvertiserVideo = AdvertiserVideo(
    id = row.id,
    advertiserId = row.advertiserId.flatMap(AdvertiserId.fromString),
    videoType = row.videoType,
    videoUrl = row.videoUrl,
    durationSeconds = row.durationSeconds,
    minWatchSeconds = row.minWatchSeconds,
    showCountdown = row.showCountdown == 1,
    noRepeatSeconds = row.noRepeatSeconds,
    priority = row.priority
  )

  private def toVideoToWatch(
      row: AdvertiserVideoRow,
      title: Option[LocalizedText],
      description: Option[LocalizedText]): VideoToWatch = VideoToWatch(
    id = row.id,
    advertiserId = row.advertiserId.flatMap(AdvertiserId.fromString),
    videoType = row.videoType,
    videoUrl = row.videoUrl,
    durationSeconds = row.durationSeconds,
    title = title,
    description = description
  )

  val layer: ZLayer[QuillSqlite & SessionContext, Nothing, VideoRepository[Task]] = ZLayer
    .fromFunction(apply)
end VideoRepositoryQuill
