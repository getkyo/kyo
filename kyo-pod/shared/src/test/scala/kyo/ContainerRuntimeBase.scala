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

    /** The daemon socket the runtime's own variable names: `CONTAINER_HOST` for podman, `DOCKER_HOST` for docker.
      *
      * That variable is how a caller points kyo-pod at a daemon that sits at no standard path: a socket bind-mounted
      * into a container, or one reached across a machine boundary. `Container`'s own backend honours it, so a helper
      * that consults only the standard paths reports NO runtime available on a host where every operation in fact
      * works, and the suites that gate on availability then register no leaves while the run still reports success.
      */
    private[kyo] def envSocket(rt: String)(using AllowUnsafe): Maybe[String] =
        envSocketFrom(rt, getEnv("CONTAINER_HOST"), getEnv("DOCKER_HOST"))

    /** Each runtime takes the variable its CLI reads, as given. The shell arm runs that CLI, so the HTTP arm reaching the same socket is
      * what keeps one runtime's two arms on one daemon; inferring the runtime from how the path looks sends a socket to the wrong runtime
      * silently.
      */
    private[kyo] def envSocketFrom(rt: String, containerHost: Maybe[String], dockerHost: Maybe[String]): Maybe[String] =
        val named = rt match
            case "podman" => containerHost
            case "docker" => dockerHost
            case _        => Absent
        named.map(_.stripPrefix("unix://")).filter(_.nonEmpty)
    end envSocketFrom

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
        // A socket named by CONTAINER_HOST is an instruction rather than a leftover, and it answers for a daemon
        // this host's CLI may know nothing about, so it decides before the CLI is asked.
        envSocket("podman").exists(socketExists) ||
        runtimeAvailable(cliPresent("podman"), cliExists("podman"), socketExists(sock))
    end hasPodman

    lazy val hasDocker: Boolean =
        import AllowUnsafe.embrace.danger
        val home = getHome
        envSocket("docker").exists(socketExists) ||
        runtimeAvailable(
            cliPresent("docker"),
            cliExists("docker"),
            socketExists(s"$home/.docker/run/docker.sock") || socketExists("/var/run/docker.sock")
        )
    end hasDocker

    /** The runtimes this process answers for, each with the reason it cannot run here, or `Absent` when it can.
      *
      * A runtime that cannot run is kept with its reason rather than dropped, so its leaves are registered cancelled. The build forks the
      * container suites once per runtime, and a fork that registered nothing for its runtime runs 0 leaves under a filter that matches only
      * container leaves, which the runner fails as a selection that ran nothing.
      */
    lazy val assigned: Seq[(String, Maybe[String])] =
        import AllowUnsafe.embrace.danger
        val reachable = Seq("podman" -> hasPodman, "docker" -> hasDocker)
        assignment(
            kyo.internal.Platform.isWindows,
            reachable,
            distinctDaemons(reachable.collect { case (name, true) => name }),
            getEnv("KYO_POD_RUNTIME")
        )
    end assigned

    /** [[assigned]] from its inputs: whether the host is Windows, each runtime's reachability, the reachable runtimes that are distinct
      * daemons, and the runtime the build pinned this process to.
      */
    private[kyo] def assignment(
        windows: Boolean,
        reachable: Seq[(String, Boolean)],
        distinct: Seq[String],
        pin: Maybe[String]
    ): Seq[(String, Maybe[String])] =
        val responsible = pin match
            case Present(rt) => Seq(rt)
            case Absent      => reachable.map(_._1)
        responsible.map { rt =>
            val reason =
                // The windows-latest CI runner's Docker daemon runs in Windows-container mode and cannot pull or run Linux images,
                // so `docker` reports available while every operation fails at `docker pull`.
                if windows then Present("the host is Windows and these are Linux-container tests")
                else if !reachable.exists(_ == (rt, true)) then Present(s"$rt is not reachable on this host")
                // The pin is filtered by the same rule, not exempt from it. The build forks these suites once per runtime with the name
                // pinned here, so exempting the pin would leave both forks running against one daemon whenever the two names resolve to
                // it, which is the whole thing distinctDaemons exists to stop.
                else if !distinct.contains(rt) then Present(s"$rt reaches the same daemon as another runtime here, which runs these leaves")
                else Absent
            rt -> reason
        }
    end assignment

    lazy val available: Seq[String] = assigned.collect { case (rt, Absent) => rt }

    /** The socket a single-leg leaf runs against in this process, or why this process does not run it.
      *
      * The build puts every daemon-touching suite in both per-runtime forks, so a leaf registered against each fork's own runtime runs
      * once per fork. A single-leg leaf belongs to one runtime for the whole host: the first one that can run here unpinned and has a
      * socket. Only the fork pinned to it runs the leaf; the other registers it cancelled, so a filter that selects it never selects
      * nothing.
      */
    lazy val singleLeg: Either[String, String] =
        import AllowUnsafe.embrace.danger
        singleLegOwner(unpinnedAssignment, assigned, getEnv("KYO_POD_RUNTIME"), rt => findSocket(rt).isDefined)
            .flatMap(rt => findSocket(rt).toRight(s"$rt exposes no socket for the http backend"))
    end singleLeg

    /** Whether this process runs the leaves that belong to the host rather than to one runtime: the single-leg leaves, and every leaf the
      * runtime helpers did not register, which reaches no daemon or reaches whichever one auto-detection finds.
      *
      * The build puts every daemon-touching suite in both per-runtime forks, so those leaves would run once per fork. They run in the fork
      * pinned to the [[singleLeg]] owner, or in the podman fork when no runtime can run here, so that exactly one fork runs them.
      */
    lazy val runsHostLeaves: Boolean =
        import AllowUnsafe.embrace.danger
        hostLeavesHere(owner(unpinnedAssignment, rt => findSocket(rt).isDefined), getEnv("KYO_POD_RUNTIME"))
    end runsHostLeaves

    /** [[runsHostLeaves]] from the host's owner runtime and the pin. An unpinned process runs every leaf. */
    private[kyo] def hostLeavesHere(owner: Maybe[String], pin: Maybe[String]): Boolean =
        pin.forall(_ == owner.getOrElse("podman"))

    /** The assignment this host would get with no pin: every runtime, runnable or with its reason. */
    private lazy val unpinnedAssignment: Seq[(String, Maybe[String])] =
        import AllowUnsafe.embrace.danger
        val reachable = Seq("podman" -> hasPodman, "docker" -> hasDocker)
        assignment(kyo.internal.Platform.isWindows, reachable, distinctDaemons(reachable.collect { case (n, true) => n }), Absent)
    end unpinnedAssignment

    /** The first runtime of the unpinned assignment that can run here and exposes a socket. */
    private[kyo] def owner(unpinned: Seq[(String, Maybe[String])], hasSocket: String => Boolean): Maybe[String] =
        Maybe.fromOption(unpinned.collectFirst { case (rt, Absent) if hasSocket(rt) => rt })

    /** [[singleLeg]]'s runtime from its inputs: the host's unpinned assignment, this process's assignment, the pin, and which runtimes
      * expose a socket.
      */
    private[kyo] def singleLegOwner(
        unpinned: Seq[(String, Maybe[String])],
        assigned: Seq[(String, Maybe[String])],
        pin: Maybe[String],
        hasSocket: String => Boolean
    ): Either[String, String] =
        owner(unpinned, hasSocket) match
            case Present(rt) if pin.forall(_ == rt)      => Right(rt)
            case Present(rt)                             => Left(s"a single-leg leaf runs once per host, in the $rt fork")
            case Absent if unpinned.exists(_._2.isEmpty) => Left("no runtime that can run here exposes a socket for the http backend")
            case Absent                                  => Left(assigned.flatMap(_._2.toOption).mkString("; "))
    end singleLegOwner

    /** Drops a runtime whose socket is the same file as one already kept, keeping the first.
      *
      * `podman-docker` installs `/var/run/docker.sock` as a symlink to the podman socket, so both names resolve to ONE daemon. Registering
      * both then runs every leaf twice against it, and because the build forks the suite per runtime and those forks run concurrently, each
      * fork sees the other's containers appear inside its leaves: the per-leaf container-leak check has no way to tell them from a leak and
      * fails leaves that leaked nothing. The second registration also adds no coverage, the two legs being the same daemon reached the same
      * way. Comparing the resolved paths is what tells them apart, since the two names legitimately have different paths.
      */
    private[kyo] def distinctDaemons(runtimes: Seq[String])(using AllowUnsafe): Seq[String] =
        val seen = scala.collection.mutable.ListBuffer.empty[String]
        runtimes.filter { rt =>
            findSocket(rt).map(resolvedSocket) match
                // A runtime with no socket path (CLI-only) is kept: there is nothing to compare, and its leaves gate on their own probes.
                case None           => true
                case Some(resolved) =>
                    val duplicate = seen.contains(resolved)
                    if !duplicate then seen += resolved
                    !duplicate
            end match
        }
    end distinctDaemons

    /** The socket path with symlinks resolved, or the path itself when it cannot be resolved. */
    private[kyo] def resolvedSocket(path: String)(using AllowUnsafe): String =
        kyo.Path(path).unsafe.realPath()(using summon[AllowUnsafe], Frame.internal) match
            case Result.Success(p) => p.toString
            case _                 => path

    /** macOS Podman Machine sockets, lazily computed once. */
    private lazy val podmanMachineSockets: Seq[String] =
        if !cliExists("podman") then Seq.empty
        else queryPodmanMachineSockets

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
        (envSocket(rt).toOption.toSeq ++ candidates).find(socketExists)
    end findSocket

end ContainerRuntimeBase
