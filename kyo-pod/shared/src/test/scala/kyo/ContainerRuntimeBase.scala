package kyo

/** Shared logic for detecting whether docker/podman is available and resolving their socket paths. Each platform provides the
  * file-existence, env, and process-spawning primitives.
  */
private[kyo] trait ContainerRuntimeBase:

    private[kyo] def socketExists(path: String): Boolean
    private[kyo] def cliExists(command: String): Boolean
    private[kyo] def getEnv(name: String): String | Null
    private[kyo] def getHome: String

    /** Run `podman machine inspect --format json` and parse the resulting socket path(s). Returns Seq.empty on any failure (parse, spawn,
      * podman-not-installed, no machine configured).
      */
    private[kyo] def queryPodmanMachineSockets: Seq[String]

    lazy val hasPodman: Boolean =
        val xdg  = getEnv("XDG_RUNTIME_DIR")
        val path = if xdg != null then s"$xdg/podman/podman.sock" else "/run/podman/podman.sock"
        socketExists(path) || cliExists("podman")
    end hasPodman

    lazy val hasDocker: Boolean =
        val home = getHome
        socketExists(s"$home/.docker/run/docker.sock") || socketExists("/var/run/docker.sock") || cliExists("docker")

    lazy val available: Seq[String] =
        Seq("podman" -> hasPodman, "docker" -> hasDocker).collect { case (name, true) => name }

    def isPodman: Boolean = available.headOption.contains("podman")
    def isDocker: Boolean = available.headOption.contains("docker")

    def isAvailable(rt: String): Boolean = rt match
        case "podman" => hasPodman
        case "docker" => hasDocker
        case _        => false

    def findSocket(rt: String): Option[String] =
        val candidates = rt match
            case "docker" =>
                val home = getHome
                Seq(s"$home/.docker/run/docker.sock", "/var/run/docker.sock")
            case "podman" =>
                val xdg        = getEnv("XDG_RUNTIME_DIR")
                val xdgSockets = if xdg != null then Seq(s"$xdg/podman/podman.sock") else Seq.empty
                xdgSockets ++ podmanMachineSockets ++ Seq("/run/podman/podman.sock")
            case _ => Seq("/var/run/docker.sock")
        candidates.find(socketExists)
    end findSocket

    /** macOS Podman Machine sockets, lazily computed once. */
    private lazy val podmanMachineSockets: Seq[String] =
        if !cliExists("podman") then Seq.empty
        else queryPodmanMachineSockets

end ContainerRuntimeBase
