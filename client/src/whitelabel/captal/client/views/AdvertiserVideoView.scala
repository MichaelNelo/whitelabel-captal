package whitelabel.captal.client.views

import com.raquo.laminar.api.L.*
import com.raquo.laminar.codecs.BooleanAsAttrPresenceCodec
import org.scalajs.dom
import org.scalajs.dom.html.{Div, Video}
import org.scalajs.dom.svg.Circle
import whitelabel.captal.client.i18n.I18nClient
import whitelabel.captal.client.{ApiClient, Router, Runtime}
import whitelabel.captal.core.application.Phase
import whitelabel.captal.core.application.commands.NextVideo
import whitelabel.captal.endpoints.VideoResponse
import zio.ZIO

object AdvertiserVideoView:
  // Max wait time for video preload (in milliseconds)
  private val PreloadTimeout = 10000

  def render: HtmlElement =
    val videoData: Var[Option[NextVideo]] = Var(None)
    val showIntro: Var[Boolean] = Var(true)
    val videoCompleted: Var[Boolean] = Var(false)
    val isPlaying: Var[Boolean] = Var(false)
    val videoRef: Var[Option[Video]] = Var(None)
    val isFullscreen: Var[Boolean] = Var(false)
    val containerRef: Var[Option[Div]] = Var(None)

    def onMount(el: Div): Unit =
      containerRef.set(Some(el))
      loadNextVideo(videoData, showIntro, videoRef)
      // Listen for fullscreen changes (exit via Escape, etc.)
      dom.document.addEventListener(
        "fullscreenchange",
        (_: dom.Event) =>
          val fs = dom.document.fullscreenElement != null
          isFullscreen.set(fs)
          // Remove controls-visible class when exiting fullscreen
          if !fs then el.classList.remove("controls-visible")
      )

    div(
      cls <-- isFullscreen.signal.map: fs =>
        if fs then "video-hero fullscreen" else "video-hero"
      ,
      onMountCallback(ctx => onMount(ctx.thisNode.ref.asInstanceOf[Div])),
      // Intro card (shown after data loads, during intro phase)
      // While videoData is None, the static HTML loader remains visible
      child <-- videoData.signal
        .combineWith(showIntro.signal)
        .map:
          case (Some(video), true) => renderIntroCard(video, showIntro)
          case _                   => emptyNode
      ,
      // Video content (shown after intro)
      child <-- videoData.signal
        .combineWith(showIntro.signal)
        .map:
          case (Some(video), false) =>
            renderVideoContent(
              video,
              videoCompleted,
              isPlaying,
              videoRef,
              containerRef,
              isFullscreen
            )
          case (None, false) =>
            renderNoVideo()
          case _ =>
            emptyNode
    )

  private def renderIntroCard(video: NextVideo, showIntro: Var[Boolean]): HtmlElement =
    div(
      cls := "video-intro",
      div(
        cls := "video-intro-card",
        // Icon
        div(cls := "video-intro-icon", brandIcon),
        // Title
        video.title
          .map(t => h1(cls := "video-intro-title", t))
          .getOrElse(h1(cls := "video-intro-title", "Video")),
        // Warning message
        p(
          cls := "video-intro-warning",
          child.text <-- I18nClient.i18n.map(_.video.payAttention)
        ),
        // Progress bar — fadeout when animation completes
        div(
          cls := "video-intro-pulse",
          onMountCallback { ctx =>
            val el = ctx.thisNode.ref
            el.addEventListener(
              "animationend",
              (e: dom.AnimationEvent) =>
                if e.animationName == "introProgress" then showIntro.set(false)
            )
          }
        )
      )
    )

  private def renderVideoContent(
      video: NextVideo,
      videoCompleted: Var[Boolean],
      isPlaying: Var[Boolean],
      videoRef: Var[Option[Video]],
      containerRef: Var[Option[Div]],
      isFullscreen: Var[Boolean]): HtmlElement =
    div(
      cls := "video-hero-content",
      // Video player
      div(
        cls := "video-hero-player",
        // Toggle controls visibility on tap (only in fullscreen)
        onClick --> { e =>
          if isFullscreen.now() then
            e.stopPropagation()
            containerRef.now().foreach(_.classList.toggle("controls-visible"))
        },
        // Mount the preloaded video element directly
        div(
          cls := "video-hero-video-wrapper",
          onMountCallback { ctx =>
            videoRef.now().foreach { videoEl =>
              videoEl.addEventListener("play", (_: dom.Event) => isPlaying.set(true))
              videoEl.addEventListener("pause", (_: dom.Event) => isPlaying.set(false))
              videoEl.addEventListener(
                "ended",
                (_: dom.Event) => {
                  videoCompleted.set(true)
                  isPlaying.set(false)
                })
              ctx.thisNode.ref.appendChild(videoEl)
              videoEl.muted = true
              videoEl.play()
            }
          }
        ),
        // Custom controls (includes progress button)
        renderControls(isPlaying, videoRef, containerRef, isFullscreen, videoCompleted)
      )
    )

  private def renderControls(
      isPlaying: Var[Boolean],
      videoRef: Var[Option[Video]],
      containerRef: Var[Option[Div]],
      isFullscreen: Var[Boolean],
      videoCompleted: Var[Boolean]): HtmlElement =
    div(
      cls := "video-controls",
      // Play/Pause button
      button(
        cls := "video-control-btn",
        child <-- isPlaying.signal.map:
          case true  => pauseIcon
          case false => playIcon
        ,
        onClick --> { _ =>
          videoRef.now().foreach { v =>
            if v.paused then v.play() else v.pause()
          }
        }
      ),
      // Fullscreen button (native fullscreen on container + lock to landscape)
      button(
        cls := "video-control-btn",
        child <-- isFullscreen.signal.map:
          case true  => exitFullscreenIcon
          case false => fullscreenIcon
        ,
        onClick --> { _ =>
          containerRef.now().foreach { container =>
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

  private def playIcon: HtmlElement =
    span(
      svg.svg(
        svg.cls := "control-icon",
        svg.viewBox := "0 0 24 24",
        svg.fill := "currentColor",
        svg.path(svg.d := "M8 5v14l11-7z")
      )
    )

  private def pauseIcon: HtmlElement =
    span(
      svg.svg(
        svg.cls := "control-icon",
        svg.viewBox := "0 0 24 24",
        svg.fill := "currentColor",
        svg.path(svg.d := "M6 19h4V5H6v14zm8-14v14h4V5h-4z")
      )
    )

  private def fullscreenIcon: HtmlElement =
    span(
      svg.svg(
        svg.cls := "control-icon",
        svg.viewBox := "0 0 24 24",
        svg.fill := "currentColor",
        svg.path(
          svg.d := "M7 14H5v5h5v-2H7v-3zm-2-4h2V7h3V5H5v5zm12 7h-3v2h5v-5h-2v3zM14 5v2h3v3h2V5h-5z")
      )
    )

  private def exitFullscreenIcon: HtmlElement =
    span(
      svg.svg(
        svg.cls := "control-icon",
        svg.viewBox := "0 0 24 24",
        svg.fill := "currentColor",
        svg.path(
          svg.d := "M5 16h3v3h2v-5H5v2zm3-8H5v2h5V5H8v3zm6 11h2v-3h3v-2h-5v5zm2-11V5h-2v5h5V8h-3z")
      )
    )

  private def renderNoVideo(): HtmlElement =
    div(
      cls := "video-hero-empty",
      p(child.text <-- I18nClient.i18n.map(_.video.noVideoAvailable))
    )

  private def renderProgressButton(
      videoRef: Var[Option[Video]],
      videoCompleted: Var[Boolean]): HtmlElement =

    button(
      cls <-- videoCompleted.signal.map: completed =>
        if completed then "video-progress-btn active" else "video-progress-btn"
      ,
      disabled <-- videoCompleted.signal.map(!_),
      onClick --> { _ =>
        videoRef.now().foreach { v =>
          if videoCompleted.now() then markAsWatched(v.currentTime.toInt, completed = true)
        }
      },
      // SVG with progress ring
      svg.svg(
        svg.cls := "progress-ring",
        svg.viewBox := "0 0 36 36",
        // Background ring
        svg.circle(
          svg.cls := "progress-ring-bg",
          svg.cx := "18",
          svg.cy := "18",
          svg.r := "14"
        ),
        // Progress ring - uses CSS variable --progress set by onMountCallback
        svg.circle(
          svg.cls := "progress-ring-progress",
          svg.cx := "18",
          svg.cy := "18",
          svg.r := "14",
          svg.pathLength := "100",
          onMountCallback { ctx =>
            val circleEl = ctx.thisNode.ref.asInstanceOf[Circle]
            // Update progress using requestAnimationFrame for smooth animation
            def updateProgress(): Unit =
              videoRef.now().foreach { v =>
                val progress = if v.duration > 0 then (v.currentTime / v.duration).min(1.0) else 0.0
                val offset = ((100 * (1 - progress)) * 1000).toInt / 1000.0 // Truncate to 3 decimals
                circleEl.style.setProperty("--offset", offset.toString)
              }
              dom.window.requestAnimationFrame(_ => updateProgress())
            updateProgress()
          }
        )
      ),
      // Checkmark icon
      span(
        cls := "progress-checkmark",
        checkmarkIcon
      )
    )

  private def checkmarkIcon: SvgElement =
    svg.svg(
      svg.viewBox := "0 0 24 24",
      svg.fill := "none",
      svg.stroke := "currentColor",
      svg.strokeWidth := "3",
      svg.strokeLineCap := "round",
      svg.strokeLineJoin := "round",
      svg.path(svg.d := "M20 6L9 17l-5-5")
    )

  private def loadNextVideo(
      videoData: Var[Option[NextVideo]],
      showIntro: Var[Boolean],
      videoRef: Var[Option[Video]]): Unit =
    Runtime.run:
      ApiClient
        .getNextVideo()
        .tap:
          case Right(VideoResponse.Video(data)) =>
            ZIO.succeed:
              // Create the actual video element and start preloading
              val videoEl = dom.document.createElement("video").asInstanceOf[Video]
              videoEl.className = "video-hero-video"
              videoEl.setAttribute("playsinline", "")
              videoEl.preload = "auto"
              videoEl.src = data.videoUrl
              videoEl.load()
              videoRef.set(Some(videoEl))
              videoData.set(Some(data))
              // showIntro will be set to false by animationend on the progress bar
          case Right(VideoResponse.Step(nextStep)) =>
            ZIO.succeed:
              Router.syncWithPhase(nextStep.phase)
          case Left(_) =>
            ZIO.succeed:
              showIntro.set(false)

  private def markAsWatched(duration: Int, completed: Boolean): Unit =
    Runtime.run:
      ApiClient
        .markVideoWatched(duration, completed)
        .tap:
          case Right(_) =>
            ZIO.succeed:
              Router.syncWithPhase(Phase.Ready)
          case Left(_) =>
            ZIO.unit

  private def brandIcon: HtmlElement = img(
    src := "/brand-icon.svg",
    cls := "brand-icon",
    alt := "Captal")
end AdvertiserVideoView
