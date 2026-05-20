package dev

import kyo.*

/** Reproduce kyo-pod's CI test environment locally — rootless podman inside a podman container — using kyo-pod's own API to drive it.
  *
  * Why: most of the kyo-pod integration test failures we see in CI are rootless-podman-on-Linux specific. Locally, podman-machine and
  * Docker Desktop both run rootful podman/docker inside a Linux VM with systemd as PID 1, so the failures don't reproduce. This launcher
  * spawns a `quay.io/podman/stable`-derived dev container (built from `kyo-pod/dev/Containerfile.dev`), mounts the kyo workspace at
  * `/work`, and runs sbt inside. The sbt build talks to the *inner* rootless podman daemon — same shape as CI. Persistent volumes for sbt
  * and coursier caches make iteration fast after the first run.
  *
  * Usage:
  * {{{
  *   sbt 'kyo-pod / Test / runMain dev.KyoPodDevRunner'
  *   sbt -Drepro.cmd='sbt "kyo-pod / testOnly kyo.ContainerItTest"' \
  *       'kyo-pod / Test / runMain dev.KyoPodDevRunner'
  * }}}
  *
  * System properties:
  *   - `repro.cmd` — sbt command to run inside the container (default: `kyo-pod / testOnly kyo.ContainerItTest`)
  *   - `repro.workspace` — host path to mount as `/work` (default: `user.dir` walked up to the build root)
  *   - `repro.image` — image tag for the dev image (default: `kyo-pod-dev:latest`)
  *   - `repro.rebuild` — `true` to force a rebuild even when the tag exists (default: `false`)
  */
object KyoPodDevRunner extends KyoApp:

    /** Read configuration from env first (propagates through sbt's forked JVM), then system properties as a fallback. Env wins because
      * `Test / fork := true` means `-D…` on the outer sbt doesn't reach the runMain JVM unless explicitly forwarded.
      */
    private def prop(envName: String, sysName: String, default: => String): String =
        sys.env.get(envName).orElse(sys.props.get(sysName)).getOrElse(default)

    /** Walk up from the given path until a `build.sbt` is found. Falls back to the starting path. */
    private def findBuildRoot(start: java.nio.file.Path): java.nio.file.Path =
        var p: java.nio.file.Path = start
        while p != null && !java.nio.file.Files.exists(p.resolve("build.sbt")) do
            p = p.getParent
        if p == null then start else p
    end findBuildRoot

    /** When `workspace` is a git worktree, its `.git` is a file pointing at `<main-repo>/.git/worktrees/<name>` (an absolute path on the
      * host). For sbt-git to load the project inside the container, that absolute path must resolve to the same content — so we bind the
      * main repo's `.git` directory at its original host path. Returns `None` for a regular (non-worktree) checkout where `.git` is a
      * directory and no extra mount is needed.
      */
    private def detectWorktreeGitMount(workspace: java.nio.file.Path): Option[java.nio.file.Path] =
        val gitEntry = workspace.resolve(".git")
        if !java.nio.file.Files.isRegularFile(gitEntry) then None
        else
            val content = java.nio.file.Files.readString(gitEntry).trim
            val prefix  = "gitdir:"
            if !content.startsWith(prefix) then None
            else
                val raw    = content.substring(prefix.length).trim
                val gitdir = java.nio.file.Paths.get(raw).toAbsolutePath.normalize() // .../<main>/.git/worktrees/<name>
                // Walk up until we find a directory called `.git` — that's the main repo's git dir.
                var p: java.nio.file.Path = gitdir
                while p != null && p.getFileName != null && p.getFileName.toString != ".git" do
                    p = p.getParent
                Option(p)
            end if
        end if
    end detectWorktreeGitMount

    run {
        val workspaceStr =
            prop("KYO_REPRO_WORKSPACE", "repro.workspace", findBuildRoot(java.nio.file.Path.of(sys.props("user.dir"))).toString)
        val workspace = Path(workspaceStr)
        val testCmd   = prop("KYO_REPRO_CMD", "repro.cmd", "sbt 'kyo-pod / testOnly kyo.ContainerItTest'")
        val imageRef  = prop("KYO_REPRO_IMAGE", "repro.image", "localhost/kyo-pod-dev:latest")
        val rebuild   = prop("KYO_REPRO_REBUILD", "repro.rebuild", "false") == "true"
        val image     = ContainerImage(imageRef)

        // Inner script: start a rootless podman socket (kyo-pod's HTTP backend probes
        // $XDG_RUNTIME_DIR/podman/podman.sock first), then run the requested sbt command.
        // `$$` escapes the dollar so Scala leaves it for bash to expand at run time.
        val innerScript =
            s"""set -e
               |# Allow git to operate on bind-mounted paths owned by a different host UID
               |git config --global --add safe.directory '*'
               |# Rsync the workspace to a container-local writable path. /work is bind-mounted
               |# from the host and its target/ trees contain artifacts compiled by the host's
               |# JDK; sharing those breaks the inner sbt's incremental compiler. /tmp/kyo-work
               |# is ephemeral to the container so the inner build stays clean.
               |rsync -a --delete \\
               |  --exclude '.bloop' --exclude '.metals' --exclude '.idea' --exclude '.vscode' \\
               |  --exclude 'target' --exclude '.bsp' --exclude '*.log' \\
               |  /work/ /tmp/kyo-work/
               |export XDG_RUNTIME_DIR=/tmp/runtime-$$(id -u)
               |mkdir -p $$XDG_RUNTIME_DIR/podman
               |podman system service --time=0 unix://$$XDG_RUNTIME_DIR/podman/podman.sock &
               |for _ in $$(seq 1 10); do
               |  [ -S $$XDG_RUNTIME_DIR/podman/podman.sock ] && break
               |  sleep 1
               |done
               |podman info | grep -iE 'cgroup|rootless' || true
               |podman version
               |cd /tmp/kyo-work
               |$testCmd""".stripMargin

        // Build the dev image if it doesn't already exist (or if rebuild was forced).
        val ensureImage: Unit < (Async & Abort[ContainerException]) =
            if rebuild then buildDevImage(workspace, image)
            else
                Abort.runWith[ContainerException](ContainerImage.inspect(image)) {
                    case Result.Success(_) =>
                        Console.printLine(s"[kyo-pod-dev] image $imageRef already present — skipping build")
                    case Result.Failure(_: ContainerImageMissingException) =>
                        Console.printLine(s"[kyo-pod-dev] image $imageRef not found locally — building")
                            .andThen(buildDevImage(workspace, image))
                    case Result.Failure(e) => Abort.fail(e)
                    case Result.Panic(t)   => Abort.panic(t)
                }

        // Container.Config: privileged + workspace bind. Caches (sbt, coursier, podman
        // storage) intentionally NOT in named volumes here — fresh podman volumes are
        // created root-owned and the rootless `podman` user inside can't write to them.
        // To make caches persistent we'd need either the `:U` mount flag (not surfaced
        // by Container.Mount yet) or an entrypoint that chowns. First run is slow; that
        // tradeoff is acceptable for a repro tool.
        val baseConfig = Container.Config(image)
            .command("bash", "-lc", innerScript)
            .privileged(true) // nested userns + fuse-overlayfs
            .stopTimeout(0.seconds)
            .bind(workspace, Path("/work"))

        // Worktree support: bind the main repo's `.git` at the same host path so the
        // worktree's `.git` file (which contains an absolute `gitdir:` reference) resolves.
        val config = detectWorktreeGitMount(java.nio.file.Path.of(workspaceStr)) match
            case Some(mainGit) => baseConfig.bind(Path(mainGit.toString), Path(mainGit.toString), readOnly = true)
            case None          => baseConfig

        for
            _ <- ensureImage
            _ <- Console.printLine(s"[kyo-pod-dev] launching: $testCmd")
            exit <- Container.initWith(config) { c =>
                for
                    _    <- c.logStream.foreach(e => Console.printLine(s"[${e.source}] ${e.content}"))
                    code <- c.waitForExit
                    _    <- Console.printLine(s"[kyo-pod-dev] inner sbt exited with $code")
                yield code
            }
        yield exit
        end for
    }

    private def buildDevImage(workspace: Path, image: ContainerImage)(using Frame): Unit < (Async & Abort[ContainerException]) =
        val containerfileDir = workspace / "kyo-pod" / "dev"
        ContainerImage.buildFromPath(
            path = containerfileDir,
            dockerfile = "Containerfile.dev",
            tags = Chunk(image.reference)
        ).foreach { progress =>
            Console.printLine(s"[kyo-pod-dev build] ${progress.stream.getOrElse("")}")
        }
    end buildDevImage

end KyoPodDevRunner
