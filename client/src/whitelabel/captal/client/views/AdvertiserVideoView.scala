package whitelabel.captal.client.views
import scala.concurrent.duration.DurationInt
import scala.scalajs.js

import com.raquo.laminar.api.L.*
import com.raquo.laminar.codecs.StringAsIsCodec
import org.scalajs.dom
import org.scalajs.dom.html.{Div, Video}
import org.scalajs.dom.svg.Circle
import whitelabel.captal.client.Promises.*
import whitelabel.captal.client.i18n.I18nClient
import whitelabel.captal.client.{AppState, ErrorHandler, Router, Runtime, VideoApi}
import whitelabel.captal.core.application.Phase
import whitelabel.captal.core.application.commands.NextVideo
import whitelabel.captal.messages.VideoResponse

object AdvertiserVideoView:
  // A cap on the intro: if `canplaythrough` has not arrived within this window, playback starts
  // anyway.
  private val IntroTimeoutMs = 3000

  /** How long the intro takes to dissolve, and how long it stays mounted after being told to go.
    * Must match `--video-intro-out` in styles.css, which runs the fade.
    */
  private val IntroFadeMs = 400

  def render: HtmlElement =
    val videoData: Var[Option[NextVideo]] = Var(None)
    val showIntro: Var[Boolean] = Var(true)
    val videoCompleted: Var[Boolean] = Var(false)
    val isPlaying: Var[Boolean] = Var(false)
    val videoRef: Var[Option[Video]] = Var(None)
    val isFullscreen: Var[Boolean] = Var(false)
    val containerRef: Var[Option[Div]] = Var(None)
    // What unmounts the intro: `showIntro=false` once canPlay && minTimeReached.
    val canPlay: Var[Boolean] = Var(false)
    val minTimeReached: Var[Boolean] = Var(false)
    // Set first, and `showIntro` only once the fade has run: an element cannot transition and be
    // unmounted in the same frame.
    val introLeaving: Var[Boolean] = Var(false)

    def onMount(el: Div): Unit =
      containerRef.set(Some(el))
      loadNextVideo(videoData, showIntro)
      js.timers
        .setTimeout(IntroTimeoutMs.millis):
          minTimeReached.set(true)
      dom
        .document
        .addEventListener(
          "fullscreenchange",
          (_: dom.Event) =>
            val fs = dom.document.fullscreenElement != null
            isFullscreen.set(fs)
            if !fs then
              el.classList.remove("controls-visible"))

    // The player and the intro overlay coexist. The `<video>` mounts as soon as there is data and
    // must NEVER change parent — one mount, one network request. The intro is an overlay on top
    // that unmounts when `showIntro=false`.
    div(
      onMountCallback(ctx => onMount(ctx.thisNode.ref.asInstanceOf[Div])),
      // canPlay + minTimeReached → the intro dissolves and the video starts. Both have to be true.
      //
      // Playback begins here, not at the player's mount: the element mounts behind the intro only
      // to buffer. It starts as the fade starts, so the film shows through the dissolve, and it is
      // muted, so no user gesture is needed.
      canPlay
        .signal
        .combineWith(minTimeReached.signal)
        .changes
        .filter { case (cp, mt) =>
          cp && mt
        } -->
        Observer[(Boolean, Boolean)]: _ =>
          introLeaving.set(true)
          videoRef
            .now()
            .foreach: v =>
              val _ = v.play()
          js.timers
            .setTimeout(IntroFadeMs.millis):
              showIntro.set(false)
      ,
      child <--
        videoData
          .signal
          .map:
            case Some(v) =>
              renderVideoHero(
                v,
                isFullscreen,
                videoCompleted,
                isPlaying,
                videoRef,
                containerRef,
                canPlay)
            case None =>
              emptyNode
      ,
      child <--
        showIntro
          .signal
          .map:
            case true =>
              renderIntroCard(videoData.signal, introLeaving.signal)
            case false =>
              emptyNode
    )
  end render

  private def renderVideoHero(
      video: NextVideo,
      isFullscreen: Var[Boolean],
      videoCompleted: Var[Boolean],
      isPlaying: Var[Boolean],
      videoRef: Var[Option[Video]],
      containerRef: Var[Option[Div]],
      canPlay: Var[Boolean]): HtmlElement = div(
    cls <--
      isFullscreen
        .signal
        .map: fs =>
          if fs then
            "video-hero fullscreen"
          else
            "video-hero"
    ,
    renderVideoContent(
      video,
      videoCompleted,
      isPlaying,
      videoRef,
      containerRef,
      isFullscreen,
      canPlay)
  )

  /** The intro, wrapped in `Layout` so it inherits the same loading→loaded machinery as every other
    * view: the ring adopts from the nav-loader, runs `ringOut` on `.loaded-state`, and the content
    * fades in staggered through `.view-content.visible > :nth-child`.
    *
    * `.video-intro` is what makes it a layer over the player: the player is `position: fixed;
    * inset: 0`, so the intro needs a fixed, opaque sibling above it to cover the video.
    */
  private def renderIntroCard(
      videoDataS: Signal[Option[NextVideo]],
      leaving: Signal[Boolean]): HtmlElement = div(
    cls := "video-intro",
    cls.toggle("leaving") <-- leaving,
    Layout(
      isLoading = videoDataS.map(_.isEmpty),
      footer = div(),
      content = div(
        child <--
          videoDataS.map:
            case Some(video) =>
              h1(cls := "video-intro-title", video.title.getOrElse("Video"))
            case None =>
              emptyNode
        ,
        child <--
          videoDataS.map:
            case Some(_) =>
              p(
                cls := "video-intro-warning",
                child.text <-- I18nClient.i18n.map(_.video.payAttention))
            case None =>
              emptyNode
        ,
        child <--
          videoDataS.map:
            // The bar is always indeterminate, a sweeping loop. The intro unmounts when
            // `loadNextVideo` settles `max(IntroTimeoutMs, canplaythrough)`.
            case Some(_) =>
              div(cls := "video-intro-pulse")
            case None =>
              emptyNode
      )
    )
  )

  private def renderVideoContent(
      video: NextVideo,
      videoCompleted: Var[Boolean],
      isPlaying: Var[Boolean],
      videoRef: Var[Option[Video]],
      containerRef: Var[Option[Div]],
      isFullscreen: Var[Boolean],
      canPlay: Var[Boolean]): HtmlElement = div(
    cls := "video-hero-content",
    div(
      cls := "video-hero-player",
      onClick --> { e =>
        if isFullscreen.now() then
          e.stopPropagation()
          containerRef.now().foreach(_.classList.toggle("controls-visible"))
      },
      // A Laminar-managed `<video>`: it mounts exactly once, and the mount itself starts the
      // download (`preload=auto`). `canplaythrough` sets `canPlay`, which releases the intro.
      videoTag(
        cls                                      := "video-hero-video",
        src                                      := video.videoUrl,
        htmlAttr("playsinline", StringAsIsCodec) := "",
        onMountCallback { ctx =>
          val v = ctx.thisNode.ref
          videoRef.set(Some(v))
          v.muted = true
          v.preload = "auto"
          v.addEventListener("play", (_: dom.Event) => isPlaying.set(true))
          v.addEventListener("pause", (_: dom.Event) => isPlaying.set(false))
          v.addEventListener(
            "ended",
            (_: dom.Event) =>
              videoCompleted.set(true)
              isPlaying.set(false))
          v.addEventListener("canplaythrough", (_: dom.Event) => canPlay.set(true))
          v.addEventListener(
            "error",
            (_: dom.Event) => ErrorHandler.escalateMessage(s"Video load failed: ${v.currentSrc}"))
          // Deliberately does not play here: `preload = "auto"` fills the buffer while the intro
          // covers the element, and playback starts in `render`, when the intro goes.
        }
      ),
      renderControls(isPlaying, videoRef, containerRef, isFullscreen, videoCompleted)
    )
  )

  private def renderControls(
      isPlaying: Var[Boolean],
      videoRef: Var[Option[Video]],
      containerRef: Var[Option[Div]],
      isFullscreen: Var[Boolean],
      videoCompleted: Var[Boolean]): HtmlElement = div(
    cls := "video-controls",
    // Play/Pause button
    button(
      cls := "video-control-btn",
      child <--
        isPlaying
          .signal
          .map:
            case true =>
              pauseIcon
            case false =>
              playIcon
      ,
      onClick --> { _ =>
        videoRef
          .now()
          .foreach { v =>
            if v.paused then
              v.play()
            else
              v.pause()
          }
      }
    ),
    // Fullscreen button (native fullscreen on container + lock to landscape)
    button(
      cls := "video-control-btn",
      child <--
        isFullscreen
          .signal
          .map:
            case true =>
              exitFullscreenIcon
            case false =>
              fullscreenIcon
      ,
      onClick --> { _ =>
        containerRef
          .now()
          .foreach { container =>
            if dom.document.fullscreenElement != null then
              dom.document.exitFullscreen()
            else
              container.requestFullscreen()
          }
      }
    ),
    // Progress/Complete button
    renderProgressButton(videoRef, videoCompleted)
  )

  private def playIcon: HtmlElement = span(
    svg.svg(
      svg.cls     := "control-icon",
      svg.viewBox := "0 0 24 24",
      svg.fill    := "currentColor",
      svg.path(svg.d := "M8 5v14l11-7z")))

  private def pauseIcon: HtmlElement = span(
    svg.svg(
      svg.cls     := "control-icon",
      svg.viewBox := "0 0 24 24",
      svg.fill    := "currentColor",
      svg.path(svg.d := "M6 19h4V5H6v14zm8-14v14h4V5h-4z")))

  private def fullscreenIcon: HtmlElement = span(
    svg.svg(
      svg.cls     := "control-icon",
      svg.viewBox := "0 0 24 24",
      svg.fill    := "currentColor",
      svg.path(
        svg.d := "M7 14H5v5h5v-2H7v-3zm-2-4h2V7h3V5H5v5zm12 7h-3v2h5v-5h-2v3zM14 5v2h3v3h2V5h-5z")
    ))

  private def exitFullscreenIcon: HtmlElement = span(
    svg.svg(
      svg.cls     := "control-icon",
      svg.viewBox := "0 0 24 24",
      svg.fill    := "currentColor",
      svg.path(
        svg.d := "M5 16h3v3h2v-5H5v2zm3-8H5v2h5V5H8v3zm6 11h2v-3h3v-2h-5v5zm2-11V5h-2v5h5V8h-3z")
    ))

  private def renderNoVideo(): HtmlElement = div(
    cls := "video-hero-empty",
    p(child.text <-- I18nClient.i18n.map(_.video.noVideoAvailable)))

  private def renderProgressButton(
      videoRef: Var[Option[Video]],
      videoCompleted: Var[Boolean]): HtmlElement =

    button(
      cls <--
        videoCompleted
          .signal
          .map: completed =>
            if completed then
              "video-progress-btn active"
            else
              "video-progress-btn"
      ,
      disabled <-- videoCompleted.signal.map(!_),
      onClick --> { _ =>
        videoRef
          .now()
          .foreach { v =>
            if videoCompleted.now() then
              markAsWatched(v.currentTime.toInt, completed = true)
          }
      },
      // SVG with progress ring
      svg.svg(
        svg.cls     := "progress-ring",
        svg.viewBox := "0 0 36 36",
        // Background ring
        svg.circle(svg.cls := "progress-ring-bg", svg.cx := "18", svg.cy := "18", svg.r := "14"),
        // Progress ring - uses CSS variable --progress set by onMountCallback
        svg.circle(
          svg.cls        := "progress-ring-progress",
          svg.cx         := "18",
          svg.cy         := "18",
          svg.r          := "14",
          svg.pathLength := "100",
          onMountCallback { ctx =>
            val circleEl = ctx.thisNode.ref.asInstanceOf[Circle]
            // Update progress using requestAnimationFrame for smooth animation
            def updateProgress(): Unit =
              videoRef
                .now()
                .foreach { v =>
                  val progress =
                    if v.duration > 0 then
                      (v.currentTime / v.duration).min(1.0)
                    else
                      0.0
                  val offset = ((100 * (1 - progress)) * 1000).toInt /
                    1000.0 // Truncate to 3 decimals
                  circleEl.style.setProperty("--offset", offset.toString)
                }
              dom.window.requestAnimationFrame(_ => updateProgress())
            updateProgress()
          }
        )
      ),
      // Checkmark icon
      span(cls := "progress-checkmark", checkmarkIcon)
    )

  private def checkmarkIcon: SvgElement = svg.svg(
    svg.viewBox        := "0 0 24 24",
    svg.fill           := "none",
    svg.stroke         := "currentColor",
    svg.strokeWidth    := "3",
    svg.strokeLineCap  := "round",
    svg.strokeLineJoin := "round",
    svg.path(svg.d := "M20 6L9 17l-5-5")
  )

  /** The metadata request to the backend, and nothing else. Once `videoData` is set, Laminar's
    * `<video>` element mounts and starts the preload on its own.
    */
  private def loadNextVideo(videoData: Var[Option[NextVideo]], showIntro: Var[Boolean]): Unit =
    Runtime.run:
      VideoApi
        .getNextVideo()
        .map:
          case Right(VideoResponse.Video(data)) =>
            videoData.set(Some(data))
          case Right(VideoResponse.Step(nextStep)) =>
            Router.syncWithPhase(nextStep.phase)
          case Left(err) =>
            showIntro.set(false)
            ErrorHandler.escalate(err)

  private def markAsWatched(duration: Int, completed: Boolean): Unit =
    AppState.setNavigating(true)
    Runtime.run:
      VideoApi
        .markVideoWatched(duration, completed)
        .map:
          case Right(resp) =>
            val nextPhase = Phase.fromDbString(resp.nextPhase)
            Router.syncWithPhase(nextPhase)
          case Left(err) =>
            ErrorHandler.escalate(err)

end AdvertiserVideoView
