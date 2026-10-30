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
  sqliteRandom
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

  /** A location's promo videos in rotation order, every location's when `locationIdParam` is empty.
    * `promoParam` is always `VideoType.Promo`, passed as a parameter because an enum reaches a
    * query through `lift`, which an object-level quote cannot call.
    */
  inline def promosQuery = quote: (locationIdParam: Option[String], promoParam: VideoType) =>
    query[AdvertiserVideoRow]
      .filter(v =>
        v.status == VideoStatus.Active && v.videoType == promoParam &&
          locationIdParam.forall(lid => v.locationId.contains(lid)))
      .sortBy(v => (v.priority, v.createdAt))(Ord.asc)

  /** The videos a user has watched, once per view. */
  inline def viewsByUserQuery = quote: (userIdParam: user.Id) =>
    query[VideoViewRow].filter(_.userId.contains(userIdParam)).map(_.videoId)

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
        val program =
          for
            row  <- run(query[AdvertiserVideoRow].filter(_.id == lift(id))).map(_.headOption)
            file <- ZIO.foreach(row)(r => currentFile(r.id)).map(_.flatten)
          yield row.zip(file).map(toAdvertiserVideo)
        program.orDie

      def openPlaybacks(sessionId: user.SessionId): Task[List[video.PlaybackId]] =
        run(VideoFiles.openPlaybacksQuery(lift(sessionId)))
          .map(_.flatMap(p => video.PlaybackId.fromString(p.id)))
          .orDie

      private def currentFile(videoId: video.Id): Task[Option[VideoFileRow]] = run(
        VideoFiles.currentQuery(lift(videoId))).map(_.headOption)

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
                findNextPromo(userId, sessionData.locationId, locale)
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
                fetchLocalizedTexts(rows.head, locale)
        yield result

      /** The location's promo this user has watched the fewest times, the earliest in rotation
        * order among those: with each one watched in turn, they alternate.
        */
      private def findNextPromo(
          userId: user.Id,
          locationId: Option[String],
          locale: String): Task[Option[VideoToWatch]] =
        val program =
          for
            promos <- run(promosQuery(lift(locationId), lift(VideoType.Promo)))
            views  <-
              if promos.isEmpty then
                ZIO.succeed(Nil)
              else
                run(viewsByUserQuery(lift(userId)))
            watched = views.groupMapReduce(identity)(_ => 1)(_ + _)
            next    = promos.minByOption(promo => watched.getOrElse(promo.id, 0))
            result <- ZIO.foreach(next)(fetchLocalizedTexts(_, locale)).map(_.flatten)
          yield result
        program.orDie

      /** The video as the player gets it: its current file and its texts in the user's locale. None
        * when it has no confirmed file, which an active video always has.
        */
      private def fetchLocalizedTexts(
          videoRow: AdvertiserVideoRow,
          locale: String): Task[Option[VideoToWatch]] =
        val videoIdStr = videoRow.id.asString
        for
          file       <- currentFile(videoRow.id)
          titleTexts <- run(
            query[LocalizedTextRow]
              .filter(lt => lt.entityId == lift(videoIdStr) && lt.isActive == 1)
              .filter(lt => lt.locale == lift(locale) || lt.locale == "en"))
          descTexts <- run(
            query[LocalizedTextRow]
              .filter(lt => lt.entityId == lift(videoIdStr + "_desc") && lt.isActive == 1)
              .filter(lt => lt.locale == lift(locale) || lt.locale == "en"))
        yield file.map(
          toVideoToWatch(
            videoRow,
            _,
            selectPreferredText(titleTexts, locale),
            selectPreferredText(descTexts, locale)))
      end fetchLocalizedTexts

  private def toAdvertiserVideo(row: AdvertiserVideoRow, file: VideoFileRow): AdvertiserVideo =
    AdvertiserVideo(
      id = row.id,
      advertiserId = row.advertiserId.flatMap(AdvertiserId.fromString),
      videoType = row.videoType,
      videoUrl = file.url,
      durationSeconds = file.durationSeconds.getOrElse(0),
      minWatchSeconds = row.minWatchSeconds,
      showCountdown = row.showCountdown == 1,
      noRepeatSeconds = row.noRepeatSeconds,
      priority = row.priority
    )

  private def toVideoToWatch(
      row: AdvertiserVideoRow,
      file: VideoFileRow,
      title: Option[LocalizedText],
      description: Option[LocalizedText]): VideoToWatch = VideoToWatch(
    id = row.id,
    advertiserId = row.advertiserId.flatMap(AdvertiserId.fromString),
    videoType = row.videoType,
    videoUrl = file.url,
    durationSeconds = file.durationSeconds.getOrElse(0),
    title = title,
    description = description
  )

  val layer: ZLayer[QuillSqlite & SessionContext, Nothing, VideoRepository[Task]] = ZLayer
    .fromFunction(apply)
end VideoRepositoryQuill
