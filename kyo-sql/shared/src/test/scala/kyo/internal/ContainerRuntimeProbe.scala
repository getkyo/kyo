package kyo.internal

import kyo.*

/** Whether a podman or docker runtime looks reachable, by probing for the daemon sockets (not a CLI on PATH).
  *
  * Read by the conformance backend registry [[SqlTestBackends]] to decide whether any backend can be exercised: an
  * unreachable runtime yields no backends, which `SqlBackendTest.forEachBackend` turns into a single RED "no SQL
  * backend available" leaf rather than a green run with no coverage. Skipping these Linux-only suites on Windows is a
  * separate concern, handled by [[kyo.SqlContainerTest]] via `Platform.isWindows`, not by this probe.
  *
  * This lives in kyo-sql's test tree, not kyo-pod's `ContainerRuntimeBase`, because that class is off this
  * classpath, and it checks sockets only: CLI detection needs per-platform process spawning. `KYO_POD_RUNTIME`, when
  * set, pins the accepted runtime, which also lets a run force "no runtime" with a bogus value.
  */
object ContainerRuntimeProbe:

    /** Computed once per JVM: true when a podman or docker daemon socket is present (and matches `KYO_POD_RUNTIME` if set). */
    lazy val reachable: Boolean =
        // Unsafe: filesystem and env probe before any live Frame; kyo's portable sync Unsafe APIs work on JVM, JS, Native, and Wasm.
        import AllowUnsafe.embrace.danger
        val runtimes = Seq("podman" -> hasPodman, "docker" -> hasDocker).collect { case (name, true) => name }
        getEnv("KYO_POD_RUNTIME") match
            case Present(rt) => runtimes.contains(rt)
            // An explicit unix daemon endpoint (podman CONTAINER_HOST, then docker DOCKER_HOST) means a runtime is
            // configured even when its socket is not at a probed default path, so honor it the way kyo-pod's backend
            // discovery does. This keeps a relocated-socket host from false-negatively reporting no backend and failing
            // the conformance suite when a runtime is in fact present.
            case Absent => hasExplicitUnixHost || runtimes.nonEmpty
        end match
    end reachable

    private def hasExplicitUnixHost(using AllowUnsafe): Boolean =
        def unixHost(name: String): Boolean =
            getEnv(name) match
                case Present(v) => v.startsWith("unix://") || v.startsWith("/")
                case Absent     => false
        unixHost("CONTAINER_HOST") || unixHost("DOCKER_HOST")
    end hasExplicitUnixHost

    private def hasPodman(using AllowUnsafe): Boolean =
        val sock = getEnv("XDG_RUNTIME_DIR")
            .map(xdg => s"$xdg/podman/podman.sock")
            .getOrElse("/run/podman/podman.sock")
        socketExists(sock)
    end hasPodman

    private def hasDocker(using AllowUnsafe): Boolean =
        socketExists(s"$getHome/.docker/run/docker.sock") || socketExists("/var/run/docker.sock")

    private def socketExists(path: String)(using AllowUnsafe): Boolean =
        val p = kyo.Path(path)
        p.unsafe.exists() || p.unsafe.exists(followLinks = false)

    private def getEnv(name: String)(using AllowUnsafe): Maybe[String] =
        kyo.System.live.unsafe.env(name)

    private def getHome(using AllowUnsafe): String =
        kyo.System.live.unsafe.property("user.home").getOrElse("")

end ContainerRuntimeProbe
