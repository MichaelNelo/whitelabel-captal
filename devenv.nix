{ pkgs, lib, config, inputs, ... }:

{
  # https://devenv.sh/basics/
  # env.GREET = "devenv";

  # https://devenv.sh/packages/
  # `opentofu` alongside `terraform`, because `mcp.test` applies the configuration under
  # `terraform/` against LocalStack and that configuration is OpenTofu. The two cannot share a
  # working directory: a state file written by Terraform 1.15 is refused by OpenTofu 1.12 for being
  # from a newer version, and `.terraform.lock.hcl` records provider hashes from whichever registry
  # wrote it -- registry.terraform.io or registry.opentofu.org. The build has to run the same
  # binary an operator runs by hand.
  packages = [ pkgs.git pkgs.terraform pkgs.opentofu pkgs.scalafmt pkgs.scalafix pkgs.cloudflared ];

  # https://devenv.sh/languages/
  languages.scala.enable = true;
  languages.scala.mill.enable = true;

  # https://devenv.sh/processes/
  # processes.dev.exec = "${lib.getExe pkgs.watchexec} -n -- ls -la";

  # https://devenv.sh/services/
  # services.postgres.enable = true;

  # https://devenv.sh/scripts/
  # scripts.hello.exec = ''
  #   echo hello from $GREET
  # '';

  # https://devenv.sh/basics/
  # enterShell = ''
  #   hello         # Run scripts directly
  #   git --version # Use packages
  # '';

  # https://devenv.sh/tasks/
  # tasks = {
  #   "myproj:setup".exec = "mytool build";
  #   "devenv:enterShell".after = [ "myproj:setup" ];
  # };

  # https://devenv.sh/tests/
  # enterTest = ''
  #   echo "Running tests"
  #   git --version | grep --color=auto "${pkgs.git.version}"
  # '';

  # https://devenv.sh/git-hooks/
  # git-hooks.hooks.shellcheck.enable = true;

  # See full reference at https://devenv.sh/reference/options/
}
