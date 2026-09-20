package kyo

import scala.scalajs.js

object ContainerRuntime extends ContainerRuntimeBase:

    /** On Node.js, `user.home` Java system property is not set. Use Node's `os.homedir()` instead. */
    override private[kyo] def getHome(using AllowUnsafe): String =
        try PodNodeOs.module.fold("")(_.homedir())
        catch case _: Throwable => ""

    private[kyo] def cliExists(command: String): Boolean =
        try
            PodNodeChildProcess.module.fold(false) { childProcess =>
                discard(childProcess.execSync(s"$command version", js.Dynamic.literal(stdio = "pipe")))
                true
            }
        catch case _: Throwable => false

    /** `execSync` runs through a shell, so a missing binary and a failing one both surface as a non-zero exit and cannot be told apart from
      * the throw. `spawnSync` reports them apart: a binary that is not on PATH comes back carrying an `ENOENT` error and no status, one that
      * ran and failed carries a status and no error.
      *
      * It also asks no shell, which is what makes this answer the same on every host. Asking the shell with `command -v` answered false for
      * every runtime on Windows, where Node spawns `cmd.exe` and `command` is a POSIX shell builtin it does not have, so docker and podman
      * both read as not installed there.
      */
    private[kyo] def cliPresent(command: String): Boolean =
        try
            PodNodeChildProcess.module.fold(false) { childProcess =>
                val result = childProcess.spawnSync(command, js.Array("version"), js.Dynamic.literal(stdio = "pipe"))
                val error  = result.selectDynamic("error")
                js.isUndefined(error) || error == null
            }
        catch case _: Throwable => false

    private[kyo] def queryPodmanMachineSockets: Seq[String] =
        try
            PodNodeChildProcess.module.fold(Seq.empty[String]) { childProcess =>
                val output = childProcess.execSync(
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
            }
        catch case _: Throwable => Seq.empty

end ContainerRuntime
