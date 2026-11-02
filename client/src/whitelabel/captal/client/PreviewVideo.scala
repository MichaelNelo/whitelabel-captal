package whitelabel.captal.client

import scala.scalajs.js

import org.scalajs.dom

/** A video for the preview's video screen, made where it plays: a generic video's icon, drawn on a
  * canvas and recorded as WebM. The location's own video is not needed, and nothing is fetched for
  * it. Its address is a `data:` URL, which a page's policy allows for media.
  */
private[client] object PreviewVideo:
  private val Width = 640
  private val Height = 360

  /** Records `seconds` of the icon. Fails where the browser cannot record a canvas. */
  def record(seconds: Double): js.Promise[String] =
    new js.Promise[String]((resolve, reject) =>
      val canvas = dom.document.createElement("canvas").asInstanceOf[dom.HTMLCanvasElement]
      canvas.width = Width
      canvas.height = Height
      val context = canvas.getContext("2d").asInstanceOf[dom.CanvasRenderingContext2D]
      val stream = canvas.asInstanceOf[js.Dynamic].captureStream(10)
      val recorder = js
        .Dynamic
        .newInstance(js.Dynamic.global.MediaRecorder)(
          stream,
          js.Dynamic.literal(mimeType = "video/webm"))
      val parts = js.Array[dom.BlobPart]()
      recorder.ondataavailable = (event: js.Dynamic) => parts.push(event.data.asInstanceOf[dom.Blob])
      recorder.onstop =
        (_: js.Any) =>
          val reader = new dom.FileReader()
          reader.onload = _ => resolve(reader.result.asInstanceOf[String])
          reader.onerror = _ => reject("The recorded video could not be read")
          reader.readAsDataURL(
            new dom.Blob(
              parts,
              new dom.BlobPropertyBag:
                `type` = "video/webm"))
      // A canvas left unpainted gives its stream no frames, so it is painted again as it records.
      draw(context)
      val painting = js.timers.setInterval(100.0)(draw(context))
      recorder.start()
      val _ = js
        .timers
        .setTimeout(seconds * 1000):
          js.timers.clearInterval(painting)
          recorder.stop()
    )

  /** A play button in a ring, on a dark ground, as a video's placeholder looks. */
  private def draw(context: dom.CanvasRenderingContext2D): Unit =
    val (x, y) = (Width / 2.0, Height / 2.0)
    context.fillStyle = "#1f2937"
    context.fillRect(0, 0, Width, Height)
    context.fillStyle = "rgba(255, 255, 255, 0.15)"
    context.beginPath()
    context.arc(x, y, 64, 0, 2 * math.Pi)
    context.fill()
    context.fillStyle = "#ffffff"
    context.beginPath()
    context.moveTo(x - 20, y - 32)
    context.lineTo(x - 20, y + 32)
    context.lineTo(x + 34, y)
    context.closePath()
    context.fill()
end PreviewVideo
