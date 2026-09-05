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

    private[kyo] def cliExists(command: String): Boolean
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

    /** The daemon socket named by `CONTAINER_HOST`, classified to a runtime by its path.
      *
      * That variable is how a caller points kyo-pod at a daemon that sits at no standard path: a socket bind-mounted
      * into a container, or one reached across a machine boundary. `Container`'s own backend honours it, so a helper
      * that consults only the standard paths reports NO runtime available on a host where every operation in fact
      * works, and the suites that gate on availability then register no leaves while the run still reports success.
      * The path decides which runtime it is, the same way `HttpContainerBackend` names its own.
      */
    private[kyo] def envSocket(rt: String)(using AllowUnsafe): Maybe[String] =
        getEnv("CONTAINER_HOST")
            .map(_.stripPrefix("unix://"))
            .filter(_.nonEmpty)
            .filter(path => if rt == "podman" then path.contains("podman") else !path.contains("podman"))

    // --- Memoized detection — lazy vals capture AllowUnsafe internally so they stay parameter-free ---

    lazy val hasPodman: Boolean =
        import AllowUnsafe.embrace.danger
        val sock = getEnv("XDG_RUNTIME_DIR")
            .map(xdg => s"$xdg/podman/podman.sock")
            .getOrElse("/run/podman/podman.sock")
        envSocket("podman").exists(socketExists) || socketExists(sock) || cliExists("podman")
    end hasPodman

    lazy val hasDocker: Boolean =
        import AllowUnsafe.embrace.danger
        val home = getHome
        envSocket("docker").exists(socketExists) ||
        socketExists(s"$home/.docker/run/docker.sock") || socketExists("/var/run/docker.sock") || cliExists("docker")
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
        (envSocket(rt).toOption.toSeq ++ candidates).find(socketExists)
    end findSocket

end ContainerRuntimeBase
