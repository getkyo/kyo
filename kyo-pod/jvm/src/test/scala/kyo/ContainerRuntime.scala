package kyo

object ContainerRuntime:
    private def socketExists(path: String): Boolean =
        val p = java.nio.file.Path.of(path)
        java.nio.file.Files.exists(p) || java.nio.file.Files.exists(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)

    private def cliExists(command: String): Boolean =
        val pb = new java.lang.ProcessBuilder(command, "version")
        pb.redirectErrorStream(true)
        val result = scala.util.Try {
            val proc = pb.start()
            proc.getInputStream.readAllBytes()
            proc.waitFor() == 0
        }
        result.getOrElse(false)
    end cliExists

    lazy val hasPodman: Boolean =
        val xdg  = java.lang.System.getenv("XDG_RUNTIME_DIR")
        val path = if xdg != null then s"$xdg/podman/podman.sock" else "/run/podman/podman.sock"
        socketExists(path) || cliExists("podman")
    end hasPodman

    lazy val hasDocker: Boolean =
        val home = java.lang.System.getProperty("user.home", "")
        socketExists(s"$home/.docker/run/docker.sock") || socketExists("/var/run/docker.sock") || cliExists("docker")

    lazy val available: Seq[String] =
        Seq("podman" -> hasPodman, "docker" -> hasDocker).collect { case (name, true) => name }

    def isPodman: Boolean = available.headOption.contains("podman")
    def isDocker: Boolean = available.headOption.contains("docker")

    def findSocket(rt: String): Option[String] =
        val candidates = rt match
            case "docker" =>
                val home = java.lang.System.getProperty("user.home", "")
                Seq(s"$home/.docker/run/docker.sock", "/var/run/docker.sock")
            case "podman" =>
                val xdg        = java.lang.System.getenv("XDG_RUNTIME_DIR")
                val xdgSockets = if xdg != null then Seq(s"$xdg/podman/podman.sock") else Seq.empty
                // macOS Podman Machine socket paths
                val macSockets = findPodmanMachineSocket()
                xdgSockets ++ macSockets ++ Seq("/run/podman/podman.sock")
            case _ => Seq("/var/run/docker.sock")
        candidates.find(p => java.nio.file.Files.exists(java.nio.file.Path.of(p)))
    end findSocket

    private def findPodmanMachineSocket(): Seq[String] =
        try
            val pb = new java.lang.ProcessBuilder("podman", "machine", "inspect", "--format", "json")
            pb.redirectErrorStream(true)
            val proc   = pb.start()
            val output = new String(proc.getInputStream.readAllBytes())
            proc.waitFor()
            // Parse JSON to extract ConnectionInfo.PodmanSocket.Path
            val pathPattern = """"Path"\s*:\s*"([^"]+api\.sock[^"]*)"""".r
            pathPattern.findFirstMatchIn(output).map(_.group(1)).toSeq
        catch case _: Throwable => Seq.empty
end ContainerRuntime
