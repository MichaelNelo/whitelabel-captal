package whitelabel.captal.infra.repositories

import io.getquill.*
import whitelabel.captal.core.infrastructure.VideoRepository
import whitelabel.captal.core.survey.AdvertiserId
import whitelabel.captal.core.survey.question.LocalizedText
import whitelabel.captal.core.video.{AdvertiserVideo, VideoToWatch, VideoType}
import whitelabel.captal.core.{user, video}
import whitelabel.captal.infra.*
import whitelabel.captal.infra.schema.core.given
import whitelabel.captal.infra.schema.given
import whitelabel.captal.infra.schema.{QuillSqlite, castAsReal, castAsRealOpt, nullif, sqliteAbs, sqliteLog, sqliteRandom, strGt}
import whitelabel.captal.infra.session.SessionContext
import zio.*

object VideoRepositoryQuill:

  // Helper para seleccionar el texto preferido (locale del usuario > inglés)
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

  // Query DSL para obtener el primer video de propaganda
  inline def firstPromoQuery = quote:
    query[AdvertiserVideoRow]
      .filter(v => v.isActive == 1 && v.videoType == "propaganda")
      .sortBy(v => (v.priority, v.createdAt))(Ord.asc)
      .take(1)

  // Weighted random ad selection — anonymous user (no EXISTS filter)
  inline def nextAdQueryAnonymous = quote: (locationIdParam: Option[String]) =>
    val advertiserPrioritySum = query[AdvertiserRow].filter(_.isActive == 1).map(_.priority).sum
    query[AdvertiserVideoRow]
      .join(query[AdvertiserRow])
      .on((v, a) => v.advertiserId.contains(a.id) && a.isActive == 1)
      .filter((v, _) =>
        v.isActive == 1 && v.videoType == "publicidad" &&
          locationIdParam.forall(lid => v.locationId.contains(lid)))
      .sortBy((v, a) =>
        -sqliteLog(sqliteAbs(sqliteRandom) + 0.0001) / (
          (castAsReal(a.priority) / nullif(castAsRealOpt(advertiserPrioritySum), 0.0)) *
            (castAsReal(v.priority) / nullif(
              castAsRealOpt(
                query[AdvertiserVideoRow]
                  .filter(av => av.advertiserId == v.advertiserId && av.isActive == 1)
                  .map(_.priority)
                  .sum),
              0.0))
        )
      )(using Ord.asc)
      .take(1)
      .map((v, _) => v)

  // Weighted random ad selection — authenticated user (with EXISTS for unanswered surveys)
  inline def nextAdQueryAuthenticated = quote:
    (userIdParam: user.Id, locationIdParam: Option[String]) =>
      val answeredByUser = query[AnswerRow].filter(_.userId == userIdParam).map(_.questionId)
      val advertiserPrioritySum = query[AdvertiserRow].filter(_.isActive == 1).map(_.priority).sum
      query[AdvertiserVideoRow]
        .join(query[AdvertiserRow])
        .on((v, a) => v.advertiserId.contains(a.id) && a.isActive == 1)
        .filter((v, _) =>
          v.isActive == 1 && v.videoType == "publicidad" &&
            locationIdParam.forall(lid => v.locationId.contains(lid)) &&
            query[SurveyRow]
              .filter(s =>
                s.videoId
                  .exists(_ == sql"${v.id}".as[String]) && s.isActive == 1 && s.category == "advertiser")
              .flatMap(s =>
                query[QuestionRow].filter(q => q.surveyId == s.id && !answeredByUser.contains(q.id)))
              .nonEmpty
        )
        .sortBy((v, a) =>
          -sqliteLog(sqliteAbs(sqliteRandom) + 0.0001) / (
            (castAsReal(a.priority) / nullif(castAsRealOpt(advertiserPrioritySum), 0.0)) *
              (castAsReal(v.priority) / nullif(
                castAsRealOpt(
                  query[AdvertiserVideoRow]
                    .filter(av => av.advertiserId == v.advertiserId && av.isActive == 1)
                    .map(_.priority)
                    .sum),
                0.0))
          )
        )(using Ord.asc)
        .take(1)
        .map((v, _) => v)

  def apply(quill: QuillSqlite, ctx: SessionContext): VideoRepository[Task] =
    new VideoRepository[Task]:
      import quill.*

      def findById(id: video.Id): Task[Option[AdvertiserVideo]] =
        run(
          query[AdvertiserVideoRow]
            .filter(_.id == lift(id))
        ).map(_.headOption.map(toAdvertiserVideo)).orDie

      def findNextForUser(
          userId: Option[user.Id],
          lastPromoVideoId: Option[video.Id]): Task[Option[VideoToWatch]] =
        for
          sessionData <- ctx.getOrFail
          locale = sessionData.locale
          ad <- findNextAd(userId, locale)
        yield ad

      private def findNextAd(userId: Option[user.Id], locale: String): Task[Option[VideoToWatch]] =
        for
          sessionData <- ctx.getOrFail
          locationId = sessionData.locationId
          queryResult <- userId match
            case Some(uid) =>
              run(nextAdQueryAuthenticated(lift(uid), lift(locationId)))
            case None =>
              run(nextAdQueryAnonymous(lift(locationId)))
          result <- queryResult match
            case Nil => ZIO.none
            case rows =>
              fetchLocalizedTexts(rows.head, locale).map(Some(_))
        yield result

      private def findNextPromo(
          lastPromoVideoId: Option[video.Id],
          locale: String): Task[Option[VideoToWatch]] =
        lastPromoVideoId match
          case Some(lastId) =>
            run(query[AdvertiserVideoRow].filter(_.id == lift(lastId)))
              .map(_.headOption)
              .flatMap:
                case Some(currentVideo) =>
                  findNextPromoAfter(currentVideo.priority, currentVideo.createdAt)
                case None =>
                  run(firstPromoQuery)
              .flatMap: result =>
                if result.isEmpty then run(firstPromoQuery) else ZIO.succeed(result)
              .flatMap: finalResult =>
                finalResult.headOption match
                  case Some(row) => fetchLocalizedTexts(row, locale).map(Some(_))
                  case None      => ZIO.none
              .orDie

          case None =>
            run(firstPromoQuery)
              .flatMap:
                case Nil => ZIO.none
                case rows =>
                  fetchLocalizedTexts(rows.head, locale).map(Some(_))
              .orDie

      // Helper method con parámetros simples para que Quill pueda parsear lift()
      private def findNextPromoAfter(priority: Int, createdAt: String): Task[List[AdvertiserVideoRow]] =
        run(
          query[AdvertiserVideoRow]
            .filter(v => v.isActive == 1 && v.videoType == "propaganda")
            .filter(v =>
              v.priority > lift(priority) ||
                (v.priority == lift(priority) && strGt(v.createdAt, lift(createdAt))))
            .sortBy(v => (v.priority, v.createdAt))(using Ord.asc)
            .take(1)
        )

      // Fetch localized texts for a video (title and description)
      private def fetchLocalizedTexts(
          videoRow: AdvertiserVideoRow,
          locale: String): Task[VideoToWatch] =
        val videoIdStr = videoRow.id.asString
        for
          titleTexts <- run(
            query[LocalizedTextRow]
              .filter(lt => lt.entityId == lift(videoIdStr))
              .filter(lt => lt.locale == lift(locale) || lt.locale == "en")
          )
          descTexts <- run(
            query[LocalizedTextRow]
              .filter(lt => lt.entityId == lift(videoIdStr + "_desc"))
              .filter(lt => lt.locale == lift(locale) || lt.locale == "en")
          )
        yield toVideoToWatch(
          videoRow,
          selectPreferredText(titleTexts, locale),
          selectPreferredText(descTexts, locale))

  private def toAdvertiserVideo(row: AdvertiserVideoRow): AdvertiserVideo =
    AdvertiserVideo(
      id = row.id,
      advertiserId = row.advertiserId.flatMap(AdvertiserId.fromString),
      videoType = VideoType.fromDbString(row.videoType),
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
      description: Option[LocalizedText]): VideoToWatch =
    VideoToWatch(
      id = row.id,
      advertiserId = row.advertiserId.flatMap(AdvertiserId.fromString),
      videoType = VideoType.fromDbString(row.videoType),
      videoUrl = row.videoUrl,
      durationSeconds = row.durationSeconds,
      title = title,
      description = description
    )

  val layer: ZLayer[QuillSqlite & SessionContext, Nothing, VideoRepository[Task]] = ZLayer
    .fromFunction(apply)
end VideoRepositoryQuill
