package whitelabel.captal.cli.migrations

/** A schema migration tied to a CLI version. Each release that breaks the provision schema appends
  * a new entry to [[Migrations.all]] (never modify or remove existing entries — they stay
  * idempotent and harmless on already-migrated files).
  */
final case class Migration(
    version: SemVer,
    description: String,
    fileGlob: String,
    ops: List[YamlOp])

object Migrations:
  /** All known migrations sorted by version ASC. The order is load-bearing: [[pendingSince]] uses
    * binary search on this sequence, so any new entry must be APPENDED with a strictly greater
    * version than the previous tail.
    */
  val all: IndexedSeq[Migration] = Vector(
    Migration(
      version = SemVer(2, 0, 0),
      description = "UniFi Integration v1 API: rename site → siteId, drop unifiOs",
      fileGlob = "locations/*/location.yaml",
      ops = List(
        YamlOp.Rename(YamlPath("unifi.site"), YamlPath("unifi.siteId")),
        YamlOp.Delete(YamlPath("unifi.unifiOs"))
      )
    ),
    Migration(
      version = SemVer(2, 1, 0),
      description = "Move ap_mac into unifi block + add optional redirectUrl",
      fileGlob = "locations/*/location.yaml",
      ops = List(YamlOp.Rename(YamlPath("ap_mac"), YamlPath("unifi.apMac")))
    ),
    Migration(
      version = SemVer(2, 2, 0),
      description = "Move AWS-specific config blocks (images/s3/ecs/alb/cloudfront) under aws:",
      fileGlob = "shared/captal.yaml",
      ops = List(
        YamlOp.Rename(YamlPath("images"), YamlPath("aws.images")),
        YamlOp.Rename(YamlPath("s3"), YamlPath("aws.s3")),
        YamlOp.Rename(YamlPath("ecs"), YamlPath("aws.ecs")),
        YamlOp.Rename(YamlPath("alb"), YamlPath("aws.alb")),
        YamlOp.Rename(YamlPath("cloudfront"), YamlPath("aws.cloudfront"))
      )
    )
  )

  /** Migrations whose version is strictly greater than `lastSeen` AND less-than-or-equal to
    * `currentCli`. Computed via binary search on the versions vector — O(log N).
    *
    * Semantics:
    *   - When `lastSeen == SemVer.zero` (missing state file = legacy install), returns everything
    *     up to `currentCli`.
    *   - When `lastSeen == currentCli`, returns empty.
    *   - When `lastSeen > currentCli` (operator downgraded the CLI), returns empty.
    */
  def pendingSince(lastSeen: SemVer, currentCli: SemVer): IndexedSeq[Migration] =
    import scala.collection.Searching.*
    val ord = summon[Ordering[SemVer]]
    val versions = all.map(_.version)
    // Insertion point of lastSeen — first migration strictly greater starts here.
    val startIdx = versions.search(lastSeen) match
      case Found(i)          => i + 1
      case InsertionPoint(i) => i
    all.slice(startIdx, all.length).takeWhile(m => ord.lteq(m.version, currentCli))
end Migrations
