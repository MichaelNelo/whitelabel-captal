package whitelabel.captal.core.location

import java.time.Instant

/** Where the guests who join through an access point are sent: their location's portal, or the
  * address the access point names instead.
  */
enum Destination:
  case Portal(slug: Slug)
  case Elsewhere(target: RedirectTarget)

/** An address a load balancer's redirect action can send a guest to, in the parts the action takes:
  * the protocol, the host, the port, the path and the query it sends ahead of the guest's own.
  */
final case class RedirectTarget(
    protocol: String,
    host: String,
    port: Int,
    path: String,
    query: Option[String])

object RedirectTarget:
  /** The longest host, path or query a redirect action takes. */
  val MaxLength = 128

  /** What a redirect action appends of the request's own query: `#{query}`, after an `&` when the
    * address has a query of its own.
    */
  val QueryKeyword = "#{query}"

  /** The characters a redirect action takes in a path, beside letters and digits. Wildcards are
    * left out: in a path they would match rather than spell.
    */
  private val PathSymbols = "_-.$/~\"'@:+&"

  /** Why an address is not one a redirect action can spell. */
  enum Problem:
    case NotHttp,
      Fragment,
      BadHost,
      LongHost,
      BadPort,
      LongPath,
      PathCharacter,
      LongQuery,
      QueryCharacter

  /** `url` as a redirect action spells it, or why it cannot: not http or https, a fragment, a host,
    * path or query past [[MaxLength]], or a character the action refuses.
    */
  def of(url: String): Either[Problem, RedirectTarget] =
    val trimmed = url.trim
    val schemes = List("https://" -> 443, "http://" -> 80)
    schemes.find((scheme, _) => trimmed.toLowerCase.startsWith(scheme)) match
      case None =>
        Left(Problem.NotHttp)
      case Some((scheme, defaultPort)) =>
        val rest = trimmed.drop(scheme.length)
        if rest.contains("#") then
          Left(Problem.Fragment)
        else
          val (authority, pathAndQuery) = rest.span(c => c != '/' && c != '?')
          val (path, query) =
            pathAndQuery.span(_ != '?') match
              case (p, q) =>
                (
                  if p.isEmpty then
                    "/"
                  else
                    p
                  ,
                  q.drop(1))
          val (host, port) =
            authority.split(":", 2) match
              case Array(h, p) =>
                (h, p.toIntOption)
              case Array(h) =>
                (h, Some(defaultPort))
              case _ =>
                ("", None)
          val ownQuery = Option(query).filter(_.nonEmpty)
          val sentQuery = ownQuery.fold(QueryKeyword)(q => s"$q&$QueryKeyword")
          if host.isEmpty ||
            !host.forall(c => (c.isLetterOrDigit && c < 128) || c == '.' || c == '-') then
            Left(Problem.BadHost)
          else if host.length > MaxLength then
            Left(Problem.LongHost)
          else if !port.exists(p => p >= 1 && p <= 65535) then
            Left(Problem.BadPort)
          else if path.length > MaxLength then
            Left(Problem.LongPath)
          else if !path.forall(c => (c.isLetterOrDigit && c < 128) || PathSymbols.contains(c)) then
            Left(Problem.PathCharacter)
          else if sentQuery.length > MaxLength then
            Left(Problem.LongQuery)
          else if sentQuery.exists(c => c < '!' || c > '~') then
            Left(Problem.QueryCharacter)
          else
            Right(
              RedirectTarget(scheme.stripSuffix("://"), host.toLowerCase, port.get, path, ownQuery))
        end if
    end match
  end of
end RedirectTarget

/** Which location an access point's guests are sent to, of those that hold it. */
object Dispatch:
  /** A location holding the access point, as the choice reads it. */
  final case class Holder(
      slug: Slug,
      standing: Standing,
      createdAt: Instant,
      redirectUrl: Option[String])

  enum Standing:
    case Deployed,
      Paused,
      Other

  /** The newest deployed holder, else the newest paused: a paused location takes no guests while a
    * deployed one shares its access point, and its own guests reach its page, which the pause made
    * the maintenance page. Sent to the access point's own address unless that holder is paused —
    * and to its portal when the address is not one a redirect can carry. None when no holder is
    * deployed or paused: a location created and never deployed, or destroyed, has no portal.
    */
  def destination(holders: List[Holder]): Option[Destination] =
    val newest = holders.sortBy(_.createdAt).reverse
    newest
      .find(_.standing == Standing.Deployed)
      .orElse(newest.find(_.standing == Standing.Paused))
      .map: holder =>
        holder
          .redirectUrl
          .filter(_ => holder.standing != Standing.Paused)
          .flatMap(url => RedirectTarget.of(url).toOption)
          .fold(Destination.Portal(holder.slug))(Destination.Elsewhere(_))
end Dispatch
