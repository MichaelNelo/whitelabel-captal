package whitelabel.captal.cli.docker

import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.Base64

import software.amazon.awssdk.services.ecr.EcrClient
import whitelabel.captal.cli.{CliError, Output}
import zio.*

/** Builds a Docker image FROM a base image + COPY contents of a context directory, pushes to ECR.
  *
  * The Dockerfile is read from the CLI assembly's classpath (no inline generation), so the build
  * process is reproducible and self-contained.
  */
object DockerImageBuilder:

  /** Build, tag, and push an image to ECR.
    *
    * @param base
    *   ECR URI of the base image WITH tag (e.g.
    *   `123.dkr.ecr.us-east-1.amazonaws.com/captal-api:v1.0`)
    * @param repo
    *   ECR repo URI of the destination, WITHOUT tag (e.g.
    *   `123.dkr.ecr.us-east-1.amazonaws.com/captal-shared`)
    * @param tag
    *   The tag to apply (e.g. `20260502T143045` or `cafe-centro-20260502T143045`)
    * @param contextDir
    *   Directory whose contents are COPYed into the image
    * @param dockerfileResource
    *   Classpath path to the Dockerfile template (e.g. `templates/dockerfiles/Dockerfile.shared`)
    * @return
    *   Full image URI with tag applied
    */
  def buildAndPush(
      base: String,
      repo: String,
      tag: String,
      contextDir: Path,
      dockerfileResource: String): ZIO[EcrClient, CliError, String] =
    val fullTag = s"$repo:$tag"
    ZIO.scoped:
      for
        _          <- ensureContextExists(contextDir)
        dockerfile <- extractDockerfile(dockerfileResource)
        _          <- Output.detail(s"Authenticating to ECR...")
        _          <- ecrLogin(repo)
        _          <- Output.detail(s"Building image $fullTag from $base...")
        _          <- runDockerBuild(base, fullTag, dockerfile, contextDir)
        _          <- Output.detail(s"Pushing $fullTag...")
        _          <- runDockerPush(fullTag)
      yield fullTag

  private def ensureContextExists(dir: Path): IO[CliError, Unit] =
    ZIO
      .attemptBlocking:
        if !Files.exists(dir) then
          throw new RuntimeException(s"Context directory not found: $dir")
      .mapError(e => CliError.ConfigError(e.getMessage))
      .unit

  private def extractDockerfile(resource: String): ZIO[Scope, CliError, Path] = ZIO
    .acquireRelease(
      ZIO.attemptBlocking:
        val tempDir = Files.createTempDirectory("captal-build-")
        val dockerfile = tempDir.resolve("Dockerfile")
        val stream = getClass.getClassLoader.getResourceAsStream(resource)
        if stream == null then
          throw new RuntimeException(s"Dockerfile resource not found in classpath: $resource")
        try Files.copy(stream, dockerfile, StandardCopyOption.REPLACE_EXISTING)
        finally stream.close()
        dockerfile)(p =>
      ZIO
        .attemptBlocking:
          val parent = p.getParent
          if Files.exists(parent) then
            Files
              .walk(parent)
              .sorted(java.util.Comparator.reverseOrder[Path]())
              .forEach(Files.delete)
        .ignore)
    .mapError(e => CliError.BuildFailed(s"extract Dockerfile: ${e.getMessage}"))

  private def runDockerBuild(
      base: String,
      tag: String,
      dockerfile: Path,
      contextDir: Path): IO[CliError, Unit] = exec(
    List(
      "docker",
      "build",
      "--build-arg",
      s"BASE=$base",
      "-t",
      tag,
      "-f",
      dockerfile.toString,
      contextDir.toString),
    s"docker build $tag")

  private def runDockerPush(tag: String): IO[CliError, Unit] = exec(
    List("docker", "push", tag),
    s"docker push $tag")

  private def ecrLogin(repo: String): ZIO[EcrClient, CliError, Unit] =
    for
      (registry, user, password) <- ZIO.serviceWithZIO[EcrClient]: ecr =>
        ZIO
          .attemptBlocking:
            val token = ecr.getAuthorizationToken().authorizationData().get(0)
            val decoded = new String(Base64.getDecoder.decode(token.authorizationToken()))
            val parts = decoded.split(":", 2)
            if parts.length != 2 then
              throw new RuntimeException("Malformed ECR authorization token")
            val registry = repo.split("/").head
            (registry, parts(0), parts(1))
          .mapError(e => CliError.AwsError("ECR getAuthorizationToken", e))
      _ <- execWithStdin(
        List("docker", "login", "--username", user, "--password-stdin", registry),
        password,
        "docker login")
    yield ()

  private def exec(cmd: List[String], operation: String): IO[CliError, Unit] =
    ZIO
      .attemptBlocking:
        val pb = new ProcessBuilder(cmd*).inheritIO()
        val exitCode = pb.start().waitFor()
        if exitCode != 0 then
          throw new RuntimeException(s"$operation failed (exit code $exitCode)")
      .mapError(e => CliError.BuildFailed(e.getMessage))
      .unit

  private def execWithStdin(
      cmd: List[String],
      stdin: String,
      operation: String): IO[CliError, Unit] =
    ZIO
      .attemptBlocking:
        val pb = new ProcessBuilder(cmd*).redirectErrorStream(true)
        val process = pb.start()
        val out = process.getOutputStream
        out.write(stdin.getBytes("UTF-8"))
        out.close()
        val output = new String(process.getInputStream.readAllBytes())
        val exitCode = process.waitFor()
        if exitCode != 0 then
          throw new RuntimeException(s"$operation failed (exit code $exitCode):\n$output")
      .mapError(e => CliError.BuildFailed(e.getMessage))
      .unit

end DockerImageBuilder
