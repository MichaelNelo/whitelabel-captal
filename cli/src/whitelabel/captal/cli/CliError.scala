package whitelabel.captal.cli

/** Typed errors for the CLI. */
enum CliError(val message: String) extends Exception(message):
  case InvalidSlug(slug: String)   extends CliError(s"Invalid slug: $slug")
  case ConfigError(detail: String) extends CliError(s"Config error: $detail")
  case BuildFailed(step: String)   extends CliError(s"Build failed: $step")
  case AwsError(operation: String, cause: Throwable)
      extends CliError(s"AWS $operation failed: ${cause.getMessage}")
  case InvalidVideoPath(detail: String) extends CliError(s"Invalid video path: $detail")
