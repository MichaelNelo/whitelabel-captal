package whitelabel.captal.infra.provision

import java.security.MessageDigest

/** Represents the diff between disk state and DB state for provisioning. */
object ProvisionPlan:

  enum Action:
    case Create(entityKey: String, contentHash: String)
    case Update(entityKey: String, contentHash: String)
    case Delete(entityKey: String)
    case Skip(entityKey: String)

  /** Compute SHA-256 hash of content bytes. */
  def sha256(content: Array[Byte]): String =
    val md = MessageDigest.getInstance("SHA-256")
    md.digest(content).map("%02x".format(_)).mkString

  /** Compute provisioning plan by comparing disk files to DB manifest entries.
    * @param diskEntries entities found on disk with their content hashes
    * @param dbManifest all manifest entries (this location + globals) with their content hashes
    * @param localKeys keys owned by this location — only these are eligible for deletion
    */
  def compute(
      diskEntries: Map[String, String],
      dbManifest: Map[String, String],
      localKeys: Set[String] = Set.empty): List[Action] =
    val creates = diskEntries.collect:
      case (key, hash) if !dbManifest.contains(key) =>
        Action.Create(key, hash)
    .toList

    val updatesOrSkips = diskEntries.collect:
      case (key, hash) if dbManifest.contains(key) =>
        if dbManifest(key) != hash then Action.Update(key, hash)
        else Action.Skip(key)
    .toList

    val deletes = localKeys.collect:
      case key if !diskEntries.contains(key) =>
        Action.Delete(key)
    .toList

    creates ++ updatesOrSkips ++ deletes
