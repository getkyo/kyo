package demo

import kyo.*

/** A self-contained demo of a configuration deployment workspace: staging app settings on disk,
  * promoting a release through an overlay transaction, and probing changes in a sandbox before
  * they touch production files.
  *
  * Stands in for a deploy tool that writes TOML configs into a workspace directory. The demo
  * drives the real Path capability through `Path.run`: scoped temp dir, read/write lifecycle,
  * `Path.transaction` (commit on success), and `Path.sandbox` (discard always). A naive deploy
  * script writes directly to the live config path; this session stages under an overlay first so
  * a failed deploy never leaves half-written files on disk.
  *
  * Dual-purpose: run it to print the workspace trace (`runMain`), and [[DemoValidationTest]]
  * exercises the same `flow` plus `validate` as a CI guard.
  *
  * Run: `sbt 'kyo-systemJVM/testOnly demo.DemoValidationTest'`
  * Run: `sbt 'kyo-systemJVM/Test/runMain demo.ConfigWorkspaceDemo'`
  *
  * Demonstrates: Path.run, Path.tempDir, Path.read, Path.write, Path.exists, Path.remove,
  * Path.transaction, Path.sandbox.
  */
object ConfigWorkspaceDemo extends KyoApp:

    final case class WorkspaceSnapshot(
        committedConfig: String,
        deployConfig: String,
        scratchExists: Boolean,
        deployExists: Boolean,
        appRemoved: Boolean
    ) derives CanEqual

    def flow(using Frame): WorkspaceSnapshot < (Async & Abort[FileException | CommitConflict]) =
        Scope.run {
            Path.run {
                for
                    workspace <- Path.tempDir("config-workspace-")
                    appConfig    = workspace / "app.toml"
                    deployConfig = workspace / "deploy.toml"
                    scratch      = workspace / "scratch.toml"
                    baseline     = "[app]\nname = demo\nversion = 1"
                    _             <- appConfig.write(baseline)
                    _             <- Path.sandbox(scratch.write("[scratch]\nprobe = true"))
                    scratchExists <- scratch.exists
                    _             <- Path.transaction(deployConfig.write(baseline))
                    deployContent <- deployConfig.read
                    deployExists  <- deployConfig.exists
                    _             <- appConfig.remove
                    appRemoved    <- appConfig.exists
                    committed     <- deployConfig.read
                    _             <- Console.printLine(s"workspace: ${workspace.toString}")
                    _             <- Console.printLine(s"deploy committed: $deployContent")
                    _             <- Console.printLine(s"sandbox scratch persisted on lower: $scratchExists")
                yield WorkspaceSnapshot(
                    committedConfig = committed,
                    deployConfig = deployContent,
                    scratchExists = scratchExists,
                    deployExists = deployExists,
                    appRemoved = !appRemoved
                )
            }
        }
    end flow

    def validate(result: WorkspaceSnapshot): Maybe[String] =
        val expected = "[app]\nname = demo\nversion = 1"
        val checks = Seq(
            (result.committedConfig == expected, s"committed config must be '$expected', got '${result.committedConfig}'"),
            (result.deployConfig == expected, s"deploy config must match committed baseline, got '${result.deployConfig}'"),
            (result.deployExists, "deploy.toml must exist after transaction commit"),
            (!result.scratchExists, "sandbox scratch.toml must not persist after sandbox scope exits"),
            (result.appRemoved, "app.toml must be removed from lower after delete")
        )
        checks.collectFirst { case (false, msg) => msg }.map(Present(_)).getOrElse(Absent)
    end validate

    run {
        for
            snapshot <- Abort.run[FileException | CommitConflict](flow)
            verdict = snapshot match
                case Result.Success(value) => validate(value)
                case Result.Failure(err)   => Present(s"flow aborted: $err")
                case Result.Panic(ex)      => Present(s"flow panicked: $ex")
            _ <- verdict match
                case Absent =>
                    Console.printLine("\n[OK] validation passed")
                case Present(msg) =>
                    Console.printLineErr(s"\n[FAIL] validation: $msg")
                        .andThen(Abort.fail(new RuntimeException(s"demo validation failed: $msg")))
        yield ()
    }

end ConfigWorkspaceDemo
