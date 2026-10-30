package millbuild

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

/** Renders an HTML template, replacing its `{{JS_PATH}}`, `{{CSS_PATH}}` and `{{PRELOADS}}` placeholders. */
def renderIndex(
    templatePath: os.Path,
    destPath: os.Path,
    jsPath: String,
    cssPath: String,
    preloads: String = ""): Unit =
  val template = os.read(templatePath)
  val rendered = template
    .replace("{{JS_PATH}}", jsPath)
    .replace("{{CSS_PATH}}", cssPath)
    .replace("{{PRELOADS}}", preloads)
  os.write.over(destPath, rendered)

/** `manifest.json`: the release's version and, per script, the length of each of its lines, which is
  * what the API checks a client error report against (`platform.BundleManifest`). Lengths count
  * UTF-16 code units, as the engines count columns.
  */
def writeManifest(dir: os.Path, version: String): Unit =
  val files = os
    .list(dir)
    .filter(_.ext == "js")
    .sortBy(_.last)
    .map(script =>
      script.last ->
        ujson.Arr.from(os.read(script).split("\n", -1).toSeq.map(line => ujson.Num(line.length))))
  os.write.over(
    dir / "manifest.json",
    ujson.Obj("version" -> ujson.Str(version), "files" -> ujson.Obj.from(files)).render())

/** A local static ES import, `from"./x"` or a bare `import"./x"`, as Rollup emits them. Dynamic
  * `import("./x")` does not match: its parenthesis is not a quote.
  */
val localImport = """(?:from|import)\s*"\./([^"]+)"""".r

/** `<link rel="modulepreload" fetchpriority="low">` for everything `entry` reaches through static imports,
  * transitively. Derived from the built output, so that no hash in it is stale.
  */
def preloadLinks(dir: os.Path, entry: String): String =
  def walk(name: String, seen: Seq[String]): Seq[String] =
    if seen.contains(name) || !os.exists(dir / name) then seen
    else
      localImport
        .findAllMatchIn(os.read(dir / name))
        .map(_.group(1))
        .foldLeft(seen :+ name)((acc, next) => walk(next, acc))
  walk(entry, Seq.empty)
    .filterNot(_ == entry)
    .map(name => s"""  <link rel="modulepreload" fetchpriority="low" href="./$name">""")
    .mkString("\n")

/** The prefix the MCP App pages are published under. Flat: chunk imports resolve against the entry's own URL.
  */
val uiPrefix = "ui"

/** The entries the MCP App pages load, and nothing else. */
val uiEntries = Seq("upload.js", "unifi.js", "run.js")

/** Every local module reachable from `roots` through the `import`s Rollup wrote. Modules are selected by
  * import graph, not by name: a chunk shared with the portal takes its name from whichever module pulled it in.
  */
def moduleClosure(dir: os.Path, roots: Seq[String]): Set[String] =
  def walk(name: String, seen: Set[String]): Set[String] =
    if seen.contains(name) || !os.exists(dir / name) then
      seen
    else
      localImport
        .findAllMatchIn(os.read(dir / name))
        .map(_.group(1))
        .foldLeft(seen + name)((acc, next) => walk(next, acc))
  roots.foldLeft(Set.empty[String])((acc, root) => walk(root, acc))

/** Publishes the MCP App pages' scripts from the bundle in `src` to `<bucket>/ui/`.
  *
  * Chunks are uploaded before entries, since an entry without its chunk is a silent 404. Entries
  * are revalidated, chunks are immutable. Old chunks are never deleted here.
  */
def publishUiScripts(src: os.Path, bucket: String, region: String, local: Boolean): Unit =
  val Revalidate = "public, max-age=0, must-revalidate, s-maxage=86400"
  val Immutable = "public, max-age=31536000, immutable"
  val missing = uiEntries.filterNot(name => os.exists(src / name))
  if missing.nonEmpty then
    sys.error(s"[publish] missing from $src: ${missing.mkString(", ")}")
  val (entries, chunks) = moduleClosure(src, uiEntries).toSeq.sorted.partition(uiEntries.contains)

  def put(name: String, cache: String): Unit = s3Copy(
    src / name,
    s"s3://$bucket/$uiPrefix/$name",
    "application/javascript",
    cache,
    region,
    local)

  chunks.foreach(put(_, Immutable))
  entries.foreach(put(_, Revalidate))
  println(s"[publish] ${chunks.size} chunk(s) then ${entries.size} entr(ies)")
end publishUiScripts

/** One file to one S3 key, with its type and cache policy, against LocalStack or AWS. Locally the copy
  * runs inside LocalStack's container with `awslocal`, after a `docker cp`.
  */
def s3Copy(
    file: os.Path,
    target: String,
    contentType: String,
    cache: String,
    region: String,
    local: Boolean): Unit =
  val metadata = Seq(
    "--content-type",
    contentType,
    "--cache-control",
    cache,
    "--metadata-directive",
    "REPLACE")
  println(s"[publish] ${file.last} -> $target")
  // `aws` is not on the PATH Mill gives subprocesses, so a local copy goes through the container.
  val result =
    if local then
      val staged = s"/tmp/${file.last}"
      val copied = os
        .proc("docker", "cp", file.toString, s"$localStackContainer:$staged")
        .call(check = false, stderr = os.Pipe)
      if copied.exitCode != 0 then
        sys.error(s"[publish] docker cp failed for ${file.last}: ${copied.err.trim()}")
      os.proc(
          Seq("docker", "exec", localStackContainer, "awslocal", "s3", "cp", staged, target) ++
            metadata)
        .call(check = false, stderr = os.Pipe)
    else
      os.proc(Seq("aws", "s3", "cp", file.toString, target, "--region", region) ++ metadata)
        .call(check = false, stderr = os.Pipe)
  if result.exitCode != 0 then
    sys.error(s"[publish] s3 cp failed for ${file.last}: ${result.err.trim()}")
end s3Copy

/** The work behind [[publishS3]], for `mcp.dev` to do as well. */
def publishBundle(
    src: os.Path,
    bucket: String,
    prefix: String,
    region: String,
    local: Boolean): Unit =
  val normalizedPrefix = prefix.stripSuffix("/")
  val destBase = s"s3://$bucket/$normalizedPrefix"
  // Cache-Control is explicit: a day bounds browser staleness. HTML is revalidated per request.
  val Day = "public, max-age=86400"
  val Revalidate = "public, max-age=0, must-revalidate, s-maxage=86400"

  // Enumerated from the build output, since chunk names carry content hashes. Not pre-gzipped.
  def contentType(name: String): String =
    if name.endsWith(".html") then
      "text/html; charset=utf-8"
    else if name.endsWith(".js") then
      "application/javascript"
    else if name.endsWith(".css") then
      "text/css"
    else if name.endsWith(".svg") then
      "image/svg+xml"
    else if name.endsWith(".json") then
      "application/json"
    else
      "application/octet-stream"

  // The MCP App pages go to `ui/` through `client.publish`, not under a location's prefix. Chunks
  // they share with the portal stay, so only what the UI pages alone reach is excluded.
  val uiReaches = moduleClosure(src, uiEntries)
  val portalReaches = moduleClosure(src, Seq("main.js", "maintenance.js"))
  val uiOnly = uiReaches.diff(portalReaches)

  val files = os
    .list(src)
    .filter(os.isFile)
    .map(_.last)
    .filterNot(uiOnly.contains)
    .sorted
    .map(name =>
      (
        name,
        contentType(name),
        // The manifest describes the release, so it is stale exactly when the HTML is.
        if name.endsWith(".html") || name == "manifest.json" then
          Revalidate
        else
          Day
      ))

  for (name, ctype, cache) <- files do
    s3Copy(src / name, s"$destBase/$name", ctype, cache, region, local)
  println(s"[publish] uploaded bundle to $destBase")
end publishBundle
