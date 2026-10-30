package whitelabel.captal.client.views

import scala.scalajs.js

import org.scalajs.dom
import whitelabel.captal.client.Promises.*
import whitelabel.captal.client.VideoApi
import whitelabel.captal.messages.ApiError

/** The playback this tab holds open on the server, which is what keeps a video's file from being
  * deleted while the player is still fetching it.
  *
  * Kept in `sessionStorage`, which a reload keeps and a new tab does not. A page leaving ends its
  * playback from `pagehide` — it fires on a reload as well as on a close, and the leaving page
  * cannot tell which — and marks it released. So a reload finds it released and starts a new one;
  * one that finds it still held is a page that died without `pagehide`, whose playback the server
  * still counts, and plays without starting another.
  *
  * Starts are recorded only once the server has answered: a playback held here but never counted
  * would let the file go while it plays. The other way round costs only a file kept too long.
  */
object VideoPlayback:
  private val Key = "captal.playback"

  final private case class Held(id: String, url: String, released: Boolean)

  /** Counts this page as playing `url` before the player starts fetching it. Fails only with
    * `StaleVideoFile`: the video was given a new file after this page was given `url`, and the
    * caller should ask for the video again. Anything else is logged and lets the video play.
    */
  def begin(url: String): js.Promise[Either[ApiError, Unit]] =
    held() match
      case Some(h) if h.url == url && !h.released =>
        js.Promise.resolve(Right(()))
      case other =>
        other.filterNot(_.released).foreach(h => VideoApi.endPlayback(h.id))
        val id = newId()
        VideoApi
          .startPlayback(id, url)
          .map[Either[ApiError, Unit]]:
            case Right(_) =>
              hold(Held(id, url, released = false))
              Right(())
            case Left(stale: ApiError.StaleVideoFile) =>
              Left(stale)
            case Left(other) =>
              unrecorded(url, other)
          .`catch`[Either[ApiError, Unit]](error =>
            unrecorded(url, error): Either[ApiError, Unit] | js.Thenable[Either[ApiError, Unit]])
    end match
  end begin

  /** A version-4 UUID from the browser's own generator. `java.util.UUID.randomUUID` would pull in
    * `SecureRandom`, which Scala.js does not ship; `crypto.randomUUID` exists only in a secure
    * context, so a page served over plain HTTP builds one from `getRandomValues`, which exists in
    * every context.
    */
  private def newId(): String =
    val crypto = js.Dynamic.global.crypto
    if js.typeOf(crypto.randomUUID) == "function" then
      crypto.randomUUID().asInstanceOf[String]
    else
      val bytes = new js.typedarray.Uint8Array(16)
      crypto.getRandomValues(bytes)
      bytes(6) = ((bytes(6) & 0x0f) | 0x40).toShort
      bytes(8) = ((bytes(8) & 0x3f) | 0x80).toShort
      val hex = bytes.toList.map(b => (b + 0x100).toHexString.drop(1)).mkString
      List(hex.take(8), hex.slice(8, 12), hex.slice(12, 16), hex.slice(16, 20), hex.drop(20))
        .mkString("-")

  /** The guest still gets the video; only the guarantee that its file outlives them is lost. */
  private def unrecorded(url: String, why: Any): Either[ApiError, Unit] =
    dom.console.warn(s"Playback of $url was not recorded: $why")
    Right(())

  /** The page is going away: ends the playback with a beacon and keeps it, released, for the next
    * load to find.
    */
  def release(): Unit = held()
    .filterNot(_.released)
    .foreach: h =>
      VideoApi.endPlayback(h.id)
      hold(h.copy(released = true))

  /** The server ended it, as reporting the video watched does. */
  def forget(): Unit = attempt(dom.window.sessionStorage.removeItem(Key))

  /** Back from the back-forward cache: the page `pagehide` released is showing the video again. */
  def resume(): Unit = held()
    .filter(_.released)
    .foreach: h =>
      val _ = begin(h.url)

  private def held(): Option[Held] = attempt(Option(dom.window.sessionStorage.getItem(Key)))
    .flatten
    .flatMap: raw =>
      val stored = js.JSON.parse(raw).asInstanceOf[js.Dynamic]
      for
        id       <- stored.id.asInstanceOf[js.UndefOr[String]].toOption
        url      <- stored.url.asInstanceOf[js.UndefOr[String]].toOption
        released <- stored.released.asInstanceOf[js.UndefOr[Boolean]].toOption
      yield Held(id, url, released)

  private def hold(h: Held): Unit = attempt(
    dom
      .window
      .sessionStorage
      .setItem(
        Key,
        js.JSON.stringify(js.Dynamic.literal(id = h.id, url = h.url, released = h.released))))

  /** Storage can be missing or refuse, in a private window or a captive portal's own browser. The
    * playback is then this page's alone, as if every load were the first.
    */
  private def attempt[A](body: => A): Option[A] =
    try
      Some(body)
    catch
      case _: Throwable =>
        None
end VideoPlayback
