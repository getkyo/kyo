package kyo

import scala.scalajs.js

object ContainerRuntime:

    private val fs           = js.Dynamic.global.require("node:fs")
    private val childProcess = js.Dynamic.global.require("node:child_process")
    private val os           = js.Dynamic.global.require("node:os")

    private def socketExists(path: String): Boolean =
        try fs.existsSync(path).asInstanceOf[Boolean]
        catch case _: Throwable => false

    private def cliExists(command: String): Boolean =
        try
            childProcess.execSync(s"$command version", js.Dynamic.literal(stdio = "pipe"))
            true
        catch case _: Throwable => false

    private def getEnv(name: String): String =
        val v = js.Dynamic.global.process.env.selectDynamic(name)
        if js.isUndefined(v) || v == null then null
        else v.asInstanceOf[String]
    end getEnv

    private def getHome: String =
        os.homedir().asInstanceOf[String]

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

    def findSocket(rt: String): Option[String] =
        val candidates = rt match
            case "docker" =>
                val home = getHome
                Seq(s"$home/.docker/run/docker.sock", "/var/run/docker.sock")
            case "podman" =>
                val xdg        = getEnv("XDG_RUNTIME_DIR")
                val xdgSockets = if xdg != null then Seq(s"$xdg/podman/podman.sock") else Seq.empty
                val macSockets = findPodmanMachineSocket()
                xdgSockets ++ macSockets ++ Seq("/run/podman/podman.sock")
            case _ => Seq("/var/run/docker.sock")
        candidates.find(socketExists)
    end findSocket

    private def findPodmanMachineSocket(): Seq[String] =
        try
            val output = childProcess.execSync(
                "podman machine inspect --format json",
                js.Dynamic.literal(stdio = js.Array("pipe", "pipe", "pipe"), encoding = "utf8")
            ).asInstanceOf[String]
            val parsed = js.JSON.parse(output)
            val arr    = parsed.asInstanceOf[js.Array[js.Dynamic]]
            if arr.length > 0 then
                val socketPath = arr(0).ConnectionInfo.PodmanSocket.Path
                if !js.isUndefined(socketPath) && socketPath != null then
                    Seq(socketPath.asInstanceOf[String])
                else Seq.empty
            else Seq.empty
            end if
        catch case _: Throwable => Seq.empty
    end findPodmanMachineSocket
end ContainerRuntime
