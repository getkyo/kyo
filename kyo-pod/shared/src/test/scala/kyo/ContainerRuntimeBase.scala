package kyo

/** Shared logic for detecting whether docker/podman is available and resolving their socket paths.
  *
  * File-system and environment primitives are implemented here via kyo's sync `Unsafe` APIs (`Path.unsafe.exists`,
  * `System.live.unsafe.env`/`property`), which work uniformly across JVM, Native, and JS. Process spawning stays per-platform because
  * `kyo.Command`/`Process` build on async I/O — there is no portable sync wait, so concrete implementations supply [[cliExists]] and
  * [[queryPodmanMachineSockets]] using each platform's native sync facilities (`java.lang.ProcessBuilder` on JVM/Native, Node's
  * `child_process.execSync` on JS).
  */
private[kyo] trait ContainerRuntimeBase:

    // --- Per-platform abstract bits ---

    /** Whether `<command> version` runs AND succeeds, which is the runtime answering for itself. */
    private[kyo] def cliExists(command: String): Boolean

    /** Whether the `command` binary is on PATH at all, whatever its exit code.
      *
      * Separate from [[cliExists]] because the two answer different questions, and only both together
      * distinguish "this runtime is not installed" from "this runtime is installed and its daemon is down".
      */
    private[kyo] def cliPresent(command: String): Boolean

    private[kyo] def queryPodmanMachineSockets: Seq[String]

    // --- Shared sync primitives via kyo.Path / kyo.System Unsafe APIs ---

    private[kyo] def socketExists(path: String)(using AllowUnsafe): Boolean =
        val p = kyo.Path(path)
        // Unsafe: synchronous runtime probing has no user call site from which to propagate a Frame.
        p.unsafe.exists()(using summon[AllowUnsafe], Frame.internal).getOrElse(false) ||
        p.unsafe.exists(followLinks = false)(using summon[AllowUnsafe], Frame.internal).getOrElse(false)
    end socketExists

    private[kyo] def getEnv(name: String)(using AllowUnsafe): Maybe[String] =
        kyo.System.live.unsafe.env(name)

    private[kyo] def getHome(using AllowUnsafe): String =
        kyo.System.live.unsafe.property("user.home").getOrElse("")

    // --- Memoized detection — lazy vals capture AllowUnsafe internally so they stay parameter-free ---

    /** Whether a runtime is usable, from the two signals available synchronously.
      *
      * When the CLI is installed it is the authority: `<cli> version` reaches the daemon, so a non-zero exit
      * means the runtime cannot do anything and its suites would fail on a host condition rather than on the
      * code. A socket FILE is not a daemon, and a stale one outlives the daemon that made it: on a Mac where
      * Docker Desktop is not running, `~/.docker/run/docker.sock` still exists and answers `_ping` with a 500,
      * which registered every `[docker]` leaf in the module and failed all of them.
      *
      * Only when no CLI is installed does the socket decide, which is the CLI-less container case (the suite
      * running with a socket mounted and no client binary). That direction never silently drops a runtime that
      * works: it keeps one that has no CLI to ask.
      */
    private[kyo] def runtimeAvailable(cliInstalled: Boolean, cliHealthy: Boolean, socketPresent: Boolean): Boolean =
        if cliInstalled then cliHealthy else socketPresent

    lazy val hasPodman: Boolean =
        import AllowUnsafe.embrace.danger
        val sock = getEnv("XDG_RUNTIME_DIR")
            .map(xdg => s"$xdg/podman/podman.sock")
            .getOrElse("/run/podman/podman.sock")
        runtimeAvailable(cliPresent("podman"), cliExists("podman"), socketExists(sock))
    end hasPodman

    lazy val hasDocker: Boolean =
        import AllowUnsafe.embrace.danger
        val home = getHome
        runtimeAvailable(
            cliPresent("docker"),
            cliExists("docker"),
            socketExists(s"$home/.docker/run/docker.sock") || socketExists("/var/run/docker.sock")
        )
    end hasDocker

    lazy val available: Seq[String] =
        import AllowUnsafe.embrace.danger
        // The windows-latest CI runner's Docker daemon runs in Windows-container mode and cannot pull or run Linux images,
        // so `docker` reports available while every operation fails at `docker pull`. These are Linux-container tests; skip on Windows.
        if kyo.internal.Platform.isWindows then Seq.empty
        else
            val all = Seq("podman" -> hasPodman, "docker" -> hasDocker).collect { case (name, true) => name }
            getEnv("KYO_POD_RUNTIME") match
                case Present(rt) => if all.contains(rt) then Seq(rt) else Seq.empty
                case Absent      => all
            end match
        end if
    end available

    /** macOS Podman Machine sockets, lazily computed once. */
    private lazy val podmanMachineSockets: Seq[String] =
        if !cliExists("podman") then Seq.empty
        else queryPodmanMachineSockets

    def isPodman: Boolean = available.headOption.contains("podman")
    def isDocker: Boolean = available.headOption.contains("docker")

    def isAvailable(rt: String): Boolean = available.contains(rt)

    /** Whether this runtime's CLI is on PATH, which is what the Shell backend drives.
      *
      * Availability and CLI presence are NOT the same question, and conflating them is what made the suite
      * unrunnable in a container. A runtime reached through a mounted socket with no CLI installed is
      * genuinely available: the HTTP backend talks to it. The Shell backend cannot, because there is no binary
      * to exec, so a leaf that drives it has to be gated on this rather than on `isAvailable`.
      */
    def hasCli(rt: String): Boolean = cliPresent(rt)

    /** Whether a path this process creates is the same path the daemon's containers see.
      *
      * False under docker-out-of-docker: when the tests themselves run inside a container that reaches a
      * SIBLING daemon through a mounted socket, a file written to `/tmp/x` here lives in this container's
      * filesystem, while a sibling container bind-mounting `/tmp/x` gets the daemon host's `/tmp/x`, which is
      * a different and usually empty directory. Any leaf that writes a file and then bind-mounts its
      * directory is therefore unrunnable in that topology, and no amount of retrying changes it.
      *
      * Detected from the container runtimes' own markers rather than guessed: podman writes
      * `/run/.containerenv` and Docker writes `/.dockerenv` inside every container they start.
      */
    lazy val daemonSharesFilesystem: Boolean =
        import AllowUnsafe.embrace.danger
        !(socketExists("/run/.containerenv") || socketExists("/.dockerenv"))
    end daemonSharesFilesystem

    def findSocket(rt: String)(using AllowUnsafe): Option[String] =
        val candidates = rt match
            case "docker" =>
                val home = getHome
                Seq(s"$home/.docker/run/docker.sock", "/var/run/docker.sock")
            case "podman" =>
                val xdgSockets = getEnv("XDG_RUNTIME_DIR")
                    .map(xdg => Seq(s"$xdg/podman/podman.sock"))
                    .getOrElse(Seq.empty)
                xdgSockets ++ podmanMachineSockets ++ Seq("/run/podman/podman.sock")
            case _ => Seq("/var/run/docker.sock")
        candidates.find(socketExists)
    end findSocket

end ContainerRuntimeBase
