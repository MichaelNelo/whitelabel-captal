{ pkgs, lib, config, inputs, ... }:

let
  # The long-running processes the build starts, stops and restarts through `devenv processes`.
  # Each runs what the build wrote under `$DEVENV_STATE/processes/<name>/`:
  #
  #   command  the command line, one argument per line
  #   env      variables to add, one KEY=VALUE per line (optional)
  #   ready    a bash snippet that exits 0 once the process serves (optional)
  #
  # and records its pid there as `pid`, which the build checks rather than the phase devenv reports.
  #
  # None starts with `devenv up`, and none restarts on its own: the build decides when each one
  # runs, with what, and when it has to start over.
  managedProcesses = [
    "localstack"
    "api"
    "tunnel-mcp"
    "tunnel-localstack"
    "rqlite-test"
    "rqlite-mcp-test"
    "rqlite-mcp-e2e"
  ];

  launch = name: ''
    dir="$DEVENV_STATE/processes/${name}"
    if [ ! -f "$dir/command" ]; then
      echo "$dir/command is missing: ${name} is started by the build, through mill" >&2
      exit 1
    fi
    if [ -f "$dir/env" ]; then
      while IFS= read -r line; do export "$line"; done < "$dir/env"
    fi
    mapfile -t command < "$dir/command"
    echo $$ > "$dir/pid"
    exec "''${command[@]}"
  '';

  ready = name: ''
    script="$DEVENV_STATE/processes/${name}/ready"
    test -f "$script" && bash "$script"
  '';
in
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
  dotenv.enable = true;
  packages = [ pkgs.git pkgs.terraform pkgs.opentofu pkgs.scalafmt pkgs.scalafix pkgs.cloudflared pkgs.git-cliff ];

  # https://devenv.sh/languages/
  languages.scala.enable = true;
  languages.scala.mill.enable = true;
  # The version `.mill-version` names; the launcher runs its own version whatever that file says.
  languages.scala.mill.package =
    (import inputs.nixpkgs-unstable { system = pkgs.stdenv.system; }).mill;

  # https://devenv.sh/processes/
  processes = lib.genAttrs managedProcesses (name: {
    exec = launch name;
    start.enable = false;
    restart.on = "never";
    ready = {
      exec = ready name;
      period = 1;
    };
  });

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
