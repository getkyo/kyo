package kyo

import scala.scalajs.js

object ContainerRuntime extends ContainerRuntimeBase:

    /** On Node.js, `user.home` Java system property is not set. Use Node's `os.homedir()` instead. */
    override private[kyo] def getHome(using AllowUnsafe): String =
        try PodNodeOs.homedir()
        catch case _: Throwable => ""

    private[kyo] def cliExists(command: String): Boolean =
        try
            PodNodeChildProcess.execSync(s"$command version", js.Dynamic.literal(stdio = "pipe"))
            true
        catch case _: Throwable => false

    /** `execSync` runs through a shell, so a missing binary and a failing one both surface as a non-zero exit
      * and cannot be told apart from the throw. `command -v` asks the shell the presence question directly.
      * Where it is unavailable (a non-POSIX shell) this answers false, which falls the decision back to the
      * socket check, the behaviour before this distinction existed.
      */
    private[kyo] def cliPresent(command: String): Boolean =
        try
            PodNodeChildProcess.execSync(s"command -v $command", js.Dynamic.literal(stdio = "pipe"))
            true
        catch case _: Throwable => false

    private[kyo] def queryPodmanMachineSockets: Seq[String] =
        try
            val output = PodNodeChildProcess.execSync(
                // No --format: `podman machine inspect` already prints JSON, and `--format` takes a Go
                // template, so `--format json` printed the literal string "json" and JSON.parse threw.
                "podman machine inspect",
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

end ContainerRuntime
