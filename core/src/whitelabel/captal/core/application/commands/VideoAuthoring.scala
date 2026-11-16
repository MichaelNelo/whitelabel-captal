package whitelabel.captal.core.application.commands

import java.time.Instant

import cats.Monad
import cats.data.NonEmptyChain
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import whitelabel.captal.core.Op as CoreOp
import whitelabel.captal.core.application.{Error, Event}
import whitelabel.captal.core.infrastructure.{
  AdvertiserRepository,
  LocationRepository,
  VideoAuthoringRepository,
  VideoInfo
}
import whitelabel.captal.core.location.Slug
import whitelabel.captal.core.survey.Id as SurveyId
import whitelabel.captal.core.survey.authoring.{Event as SurveyEvent, QuestionSpec, SurveyKind}
import whitelabel.captal.core.survey.question.{Id as QuestionId, LocalizedText}
import whitelabel.captal.core.video.Id as VideoId
import whitelabel.captal.core.video.authoring.{
  Error as VideoError,
  Event as VideoEvent,
  FileId,
  VideoFile,
  VideoKind,
  VideoRules,
  VideoStatus,
  VideoText
}

/** Registers the video `id` of `kind` at the location `slug`, waiting for its first file, with the
  * survey asked after it, `survey`, of `questions`: an advertiser's video has to have one, a promo
  * has one only when it is given questions. `minWatchSeconds` absent means the whole video.
  */
final case class RegisterVideoCommand(
    id: VideoId,
    slug: Slug,
    kind: VideoKind,
    title: List[LocalizedText],
    description: List[LocalizedText],
    minWatchSeconds: Option[Int],
    priority: Int,
    survey: SurveyId,
    questions: List[QuestionSpec],
    occurredAt: Instant)

/** The video registered, where its first file is to be uploaded, and its survey and questions when
  * it has them.
  */
final case class RegisteredVideo(
    video: VideoId,
    key: String,
    survey: Option[SurveyId],
    questions: List[QuestionId])

/** Refused for an unknown or retired advertiser, a video with no title or with a text that cannot
  * be shown, a minimum watch under a second, a negative priority or no-repeat, an advertiser's
  * video with no questions, and every reason one of its questions cannot be asked. Its survey is
  * named after whose video it is — the advertiser, or the venue for a promo — and its title.
  */
object RegisterVideoHandler:
  def apply[F[_]: Monad](
      locations: LocationRepository[F],
      advertisers: AdvertiserRepository[F]): Handler.Aux[F, RegisterVideoCommand, RegisteredVideo] =
    new Handler[F, RegisterVideoCommand]:
      type Result = RegisteredVideo

      def handle(cmd: RegisterVideoCommand) =
        Decided.located(locations, cmd.slug): location =>
          ownerOf(advertisers, cmd.kind, location.name).map: owner =>
            val (advertiser, noRepeat) =
              cmd.kind match
                case VideoKind.Advertiser(id, _) =>
                  (Some(id), None)
                case VideoKind.Promo(noRepeatSeconds) =>
                  (None, noRepeatSeconds)
            val surveyed = advertiser.isDefined || cmd.questions.nonEmpty
            val file = FileId.first(cmd.id)
            val key = VideoFile.keyOf(location.slug, file)
            val refusals =
              owner.left.toOption.toList ++
                List(
                  Option.when(cmd.title.isEmpty)(VideoError.NoTitle),
                  Option.when(advertiser.isDefined && cmd.questions.isEmpty)(
                    VideoError.NoQuestions),
                  cmd.minWatchSeconds.filter(_ <= 0).map(VideoError.MinWatchNotPositive(_)),
                  Option.when(cmd.priority < 0)(VideoError.NegativePriority(cmd.priority)),
                  noRepeat.filter(_ < 0).map(VideoError.NegativeNoRepeat(_))
                ).flatten ++ VideoRules.textProblems(VideoText.Title, cmd.title) ++
                VideoRules.textProblems(VideoText.Description, cmd.description) ++
                cmd
                  .questions
                  .zipWithIndex
                  .flatMap: (question, index) =>
                    QuestionSpec
                      .problems(question, SurveyKind.Advertiser)
                      .map(VideoError.QuestionRefused(index + 1, _))
            def survey =
              List(
                Event.SurveyAuthoring(
                  SurveyEvent.SurveyCreated(
                    cmd.survey,
                    SurveyKind.Advertiser,
                    Some(cmd.id.asString),
                    Some(location.id.asString),
                    advertiser.map(_.asString),
                    owner.toOption.map(name => s"$name — ${VideoRules.titleOf(cmd.title)}"),
                    cmd.occurredAt
                  ))) ++
                cmd
                  .questions
                  .zipWithIndex
                  .map: (question, index) =>
                    Event.SurveyAuthoring(
                      SurveyEvent.QuestionAdded(cmd.survey, question, index + 1, cmd.occurredAt))
            decidedVideo(
              refusals,
              Event.VideoAuthoring(
                VideoEvent.VideoRegistered(
                  cmd.id,
                  location.id,
                  cmd.kind,
                  cmd.title,
                  cmd.description,
                  cmd.minWatchSeconds.getOrElse(0),
                  cmd.priority,
                  file,
                  key,
                  cmd.occurredAt)) :: (
                if surveyed then
                  survey
                else
                  Nil
              ),
              RegisteredVideo(
                cmd.id,
                key,
                Option.when(surveyed)(cmd.survey),
                cmd.questions.map(_.id))
            )
end RegisterVideoHandler

/** Whose a video of `kind` is, by name: its advertiser's, refused when unknown or retired, or the
  * venue's, `venue`, for a promo.
  */
private def ownerOf[F[_]: Monad](
    advertisers: AdvertiserRepository[F],
    kind: VideoKind,
    venue: String): F[Either[VideoError, String]] =
  kind match
    case VideoKind.Advertiser(id, _) =>
      advertisers
        .find(id)
        .map:
          case None =>
            Left(VideoError.AdvertiserNotFound(id))
          case Some(found) if !found.active =>
            Left(VideoError.AdvertiserRetired(found.name))
          case Some(found) =>
            Right(found.name)
    case VideoKind.Promo(_) =>
      Monad[F].pure(Right(venue))

/** Makes `file`, uploaded and found `durationSeconds` long, the file the video `video` plays.
  * Whether it is there is the caller's to check, before, by its key: storage stays out of `core`.
  */
final case class ConfirmVideoFileCommand(
    video: VideoId,
    file: FileId,
    durationSeconds: Int,
    occurredAt: Instant)

/** Where the video stands after it: in rotation or not, with the survey unlocking at
  * `minWatchSeconds`, lowered from `minWatchAdjustedFrom` when that was past the end; whether the
  * file took over from one the video was playing; and the key the file is stored at.
  */
final case class FileConfirmation(
    key: String,
    active: Boolean,
    durationSeconds: Int,
    minWatchSeconds: Int,
    minWatchAdjustedFrom: Option[Int],
    replaced: Boolean)

/** A pending video enters rotation here; a retired one whose file was replaced stays retired.
  * Refused for a length under a second, and for a file the video no longer waits for.
  */
object ConfirmVideoFileHandler:
  def apply[F[_]: Monad](videos: VideoAuthoringRepository[F])
      : Handler.Aux[F, ConfirmVideoFileCommand, FileConfirmation] =
    new Handler[F, ConfirmVideoFileCommand]:
      type Result = FileConfirmation

      def handle(cmd: ConfirmVideoFileCommand) =
        existingVideo(videos, cmd.video): video =>
          val duration = cmd.durationSeconds
          val minWatch = VideoRules.minWatch(video.minWatchSeconds, duration)
          val status =
            if video.status == VideoStatus.Pending then
              VideoStatus.Active
            else
              video.status
          Monad[F].pure(
            decidedVideo(
              List(
                Option.when(duration <= 0)(VideoError.DurationNotPositive(duration)),
                video.waiting match
                  case None =>
                    Some(VideoError.NoFileWaiting(video.id))
                  case Some(waiting) if waiting.id != cmd.file =>
                    Some(VideoError.FileNoLongerWaiting(video.id, cmd.file))
                  case Some(_) =>
                    None
              ).flatten,
              List(
                Event.VideoAuthoring(
                  VideoEvent.FileConfirmed(
                    video.id,
                    cmd.file,
                    duration,
                    minWatch,
                    status,
                    cmd.occurredAt))),
              // Answered only when accepted, which needs the file waiting.
              FileConfirmation(
                video.waiting.fold("")(_.key),
                status == VideoStatus.Active,
                duration,
                minWatch,
                Option.when(video.minWatchSeconds > duration)(video.minWatchSeconds),
                video.current.isDefined
              )
            ))
end ConfirmVideoFileHandler

/** Gives the video `video` a new file, `file`, to be uploaded while it keeps playing the one it
  * has.
  */
final case class StartFileReplacementCommand(video: VideoId, file: FileId, occurredAt: Instant)

/** The file the video waits for, and where it is to be uploaded. */
final case class AwaitedFile(file: FileId, key: String)

/** A video already waiting for a new file waits for that one still: starting again reopens it
  * rather than reserving another. Refused for a video waiting for its first file, and for one of no
  * location, whose file would have no key.
  */
object StartFileReplacementHandler:
  def apply[F[_]: Monad](videos: VideoAuthoringRepository[F])
      : Handler.Aux[F, StartFileReplacementCommand, AwaitedFile] =
    new Handler[F, StartFileReplacementCommand]:
      type Result = AwaitedFile

      def handle(cmd: StartFileReplacementCommand) =
        existingVideo(videos, cmd.video): video =>
          Monad[F].pure(
            (video.slug, video.waiting) match
              case _ if video.status == VideoStatus.Pending =>
                refusedVideo(VideoError.StillPending(video.id))
              case (None, _) =>
                refusedVideo(VideoError.NoLocation(video.id))
              case (Some(_), Some(waiting)) =>
                decidedVideo(Nil, Nil, AwaitedFile(waiting.id, waiting.key))
              case (Some(slug), None) =>
                val key = VideoFile.keyOf(slug, cmd.file)
                decidedVideo(
                  Nil,
                  List(
                    Event.VideoAuthoring(
                      VideoEvent.FileReserved(video.id, cmd.file, key, cmd.occurredAt))),
                  AwaitedFile(cmd.file, key)))
end StartFileReplacementHandler

/** Corrects the video `video`: what is given replaces what it had, the texts in the locales given.
  * An empty `productCampaignId` clears it. Not its file, which is replaced, nor its location, which
  * its file's key and its survey hang off.
  */
final case class UpdateVideoCommand(
    video: VideoId,
    title: Option[List[LocalizedText]],
    description: Option[List[LocalizedText]],
    minWatchSeconds: Option[Int],
    priority: Option[Int],
    noRepeatSeconds: Option[Int],
    productCampaignId: Option[String],
    occurredAt: Instant)

/** The `minWatchSeconds` asked for, when it was past the end of the video and lowered to it. */
final case class VideoCorrection(minWatchAdjustedFrom: Option[Int])

/** The minimum watch is lowered to the length of the file the video plays, as at its confirmation;
  * a pending video's length is not known yet, so it is kept as given. Refused for a minimum watch
  * under a second, a negative priority or no-repeat, and a text that cannot be shown.
  */
object UpdateVideoHandler:
  def apply[F[_]: Monad](
      videos: VideoAuthoringRepository[F]): Handler.Aux[F, UpdateVideoCommand, VideoCorrection] =
    new Handler[F, UpdateVideoCommand]:
      type Result = VideoCorrection

      def handle(cmd: UpdateVideoCommand) =
        existingVideo(videos, cmd.video): video =>
          val duration = video.current.flatMap(_.durationSeconds).getOrElse(0)
          val minWatch = cmd
            .minWatchSeconds
            .map(requested =>
              if duration > 0 then
                VideoRules.minWatch(requested, duration)
              else
                requested)
          val asked =
            cmd.title.isDefined || cmd.description.isDefined || cmd.minWatchSeconds.isDefined ||
              cmd.priority.isDefined || cmd.noRepeatSeconds.isDefined ||
              cmd.productCampaignId.isDefined
          Monad[F].pure(
            decidedVideo(
              List(
                cmd.minWatchSeconds.filter(_ <= 0).map(VideoError.MinWatchNotPositive(_)),
                cmd.priority.filter(_ < 0).map(VideoError.NegativePriority(_)),
                cmd.noRepeatSeconds.filter(_ < 0).map(VideoError.NegativeNoRepeat(_))
              ).flatten ++ cmd.title.toList.flatMap(VideoRules.textProblems(VideoText.Title, _)) ++
                cmd.description.toList.flatMap(VideoRules.textProblems(VideoText.Description, _)),
              // Asked for nothing, it changes nothing.
              Option
                .when(asked)(
                  Event.VideoAuthoring(
                    VideoEvent.VideoUpdated(
                      video.id,
                      cmd.title,
                      cmd.description,
                      minWatch,
                      cmd.priority,
                      cmd.noRepeatSeconds,
                      cmd.productCampaignId.map(_.trim).map(id => Option.when(id.nonEmpty)(id)),
                      cmd.occurredAt
                    )))
                .toList,
              VideoCorrection(
                cmd
                  .minWatchSeconds
                  .zip(minWatch)
                  .collect {
                    case (requested, applied) if applied != requested =>
                      requested
                  })
            ))
end UpdateVideoHandler

/** Takes the video `video` out of the rotation, keeping its file and the answers to its survey. */
final case class RetireVideoCommand(video: VideoId, occurredAt: Instant)

/** Returns the video `video` to the rotation. */
final case class ReinstateVideoCommand(video: VideoId, occurredAt: Instant)

/** The videos in rotation at the video's location after the change: none means a guest gets past
  * identification and is shown nothing.
  */
final case class VideosInRotation(atLocation: Int)

/** Refused for a video still waiting for its first file. */
object RetireVideoHandler:
  def apply[F[_]: Monad](
      videos: VideoAuthoringRepository[F]): Handler.Aux[F, RetireVideoCommand, VideosInRotation] =
    new Handler[F, RetireVideoCommand]:
      type Result = VideosInRotation

      def handle(cmd: RetireVideoCommand) =
        rotation(videos, cmd.video, inRotation = false)(VideoEvent.VideoRetired(_, cmd.occurredAt))

/** Refused for a video still waiting for its first file: only its confirmation, which checks the
  * file is there, puts it into rotation.
  */
object ReinstateVideoHandler:
  def apply[F[_]: Monad](videos: VideoAuthoringRepository[F])
      : Handler.Aux[F, ReinstateVideoCommand, VideosInRotation] =
    new Handler[F, ReinstateVideoCommand]:
      type Result = VideosInRotation

      def handle(cmd: ReinstateVideoCommand) =
        rotation(videos, cmd.video, inRotation = true)(
          VideoEvent.VideoReinstated(_, cmd.occurredAt))

/** The video `id` left in or out of the rotation, as `event` says, with what that leaves at its
  * location.
  */
private def rotation[F[_]: Monad](
    videos: VideoAuthoringRepository[F],
    id: VideoId,
    inRotation: Boolean)(event: VideoId => VideoEvent): F[Op[VideosInRotation]] =
  existingVideo(videos, id): video =>
    if video.status == VideoStatus.Pending then
      Monad[F].pure(refusedVideo(VideoError.StillPending(video.id)))
    else
      videos
        .activeAt(video.location)
        .map: active =>
          val others = active.count(_ != video.id)
          decidedVideo(
            Nil,
            List(Event.VideoAuthoring(event(video.id))),
            VideosInRotation(
              others + (
                if inRotation then
                  1
                else
                  0
              )))

/** The video `id`, or refused when there is none. */
private def existingVideo[F[_]: Monad, A](videos: VideoAuthoringRepository[F], id: VideoId)(
    decide: VideoInfo => F[Op[A]]): F[Op[A]] = videos
  .video(id)
  .flatMap:
    case None =>
      Monad[F].pure(refusedVideo(VideoError.VideoNotFound(id.asString)))
    case Some(found) =>
      decide(found)

/** Refused with `errors` when there are any; otherwise `events`, answering `result`. */
private def decidedVideo[A](errors: List[VideoError], events: => List[Event], result: => A): Op[A] =
  NonEmptyChain.fromSeq(errors) match
    case Some(found) =>
      CoreOp.fail[Event, Error, A](Error.VideoAuthoring(found))
    case None =>
      CoreOp.emitAll[Event, Error, A](events, result)

private def refusedVideo[A](error: VideoError): Op[A] = CoreOp.fail[Event, Error, A](
  Error.VideoAuthoring(NonEmptyChain.one(error)))
