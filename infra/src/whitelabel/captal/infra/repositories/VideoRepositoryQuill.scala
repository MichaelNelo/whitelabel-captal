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
import whitelabel.captal.infra.schema.{QuillSqlite, strGt}
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
          result <- ad match
            case Some(v) =>
              ZIO.some(v)
            case None =>
              findNextPromo(lastPromoVideoId, locale)
        yield result

      private def findNextAd(userId: Option[user.Id], locale: String): Task[Option[VideoToWatch]] =
        // Weighted random: ORDER BY -LOG(ABS(RANDOM()) + 0.0001) / combined_weight
        // combined_weight = (adv.priority / sum_adv) * (vid.priority / sum_vid_of_adv)
        // NOTE: This query uses raw SQL because Quill DSL doesn't support LOG(ABS(RANDOM()))
        val queryResult = userId match
          case Some(uid) =>
            // Filtrar videos ya vistos por el usuario dentro del cooldown
            run(sql"""
              SELECT v.id, v.advertiser_id, v.video_type, v.video_url, v.duration_seconds,
                     v.min_watch_seconds, v.show_countdown, v.no_repeat_seconds, v.is_active,
                     v.priority, v.created_at, v.updated_at
              FROM advertiser_videos v
              JOIN advertisers a ON v.advertiser_id = a.id
              WHERE v.is_active = 1
                AND a.is_active = 1
                AND v.video_type = 'publicidad'
                AND NOT EXISTS (
                  SELECT 1 FROM video_views vv
                  WHERE vv.video_id = v.id
                    AND vv.user_id = ${lift(uid.asString)}
                    AND v.no_repeat_seconds IS NOT NULL
                    AND vv.viewed_at > datetime('now', '-' || v.no_repeat_seconds || ' seconds')
                )
              ORDER BY -LOG(ABS(RANDOM()) + 0.0001) / (
                (CAST(a.priority AS REAL) / NULLIF((SELECT SUM(priority) FROM advertisers WHERE is_active = 1), 0)) *
                (CAST(v.priority AS REAL) / NULLIF((SELECT SUM(priority) FROM advertiser_videos WHERE advertiser_id = v.advertiser_id AND is_active = 1), 0))
              )
              LIMIT 1
            """.as[Query[AdvertiserVideoRow]])
          case None =>
            // Usuario anónimo: cualquier video de publicidad activo
            run(sql"""
              SELECT v.id, v.advertiser_id, v.video_type, v.video_url, v.duration_seconds,
                     v.min_watch_seconds, v.show_countdown, v.no_repeat_seconds, v.is_active,
                     v.priority, v.created_at, v.updated_at
              FROM advertiser_videos v
              JOIN advertisers a ON v.advertiser_id = a.id
              WHERE v.is_active = 1
                AND a.is_active = 1
                AND v.video_type = 'publicidad'
              ORDER BY -LOG(ABS(RANDOM()) + 0.0001) / (
                (CAST(a.priority AS REAL) / NULLIF((SELECT SUM(priority) FROM advertisers WHERE is_active = 1), 0)) *
                (CAST(v.priority AS REAL) / NULLIF((SELECT SUM(priority) FROM advertiser_videos WHERE advertiser_id = v.advertiser_id AND is_active = 1), 0))
              )
              LIMIT 1
            """.as[Query[AdvertiserVideoRow]])

        queryResult.flatMap:
          case Nil => ZIO.none
          case rows =>
            val videoRow = rows.head
            fetchLocalizedTexts(videoRow, locale).map(Some(_))
        .orDie

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
