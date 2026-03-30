package whitelabel.captal.cli

/** Typed errors for the CLI. */
enum CliError(val message: String) extends Exception(message):
  case InvalidSlug(slug: String) extends CliError(s"Invalid slug: $slug")
  case MissingEnvVars(vars: List[String])
      extends CliError(s"Missing required env vars: ${vars.mkString(", ")}")
  case BuildFailed(step: String) extends CliError(s"Build failed: $step")
  case AwsError(operation: String, cause: Throwable)
      extends CliError(s"AWS $operation failed: ${cause.getMessage}")
  case DockerError(step: String) extends CliError(s"Docker $step failed")
  case InvalidVideoPath(detail: String) extends CliError(s"Invalid video path: $detail")
