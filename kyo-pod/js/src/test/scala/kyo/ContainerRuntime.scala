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

    private[kyo] def queryPodmanMachineSockets: Seq[String] =
        try
            val output = PodNodeChildProcess.execSync(
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

end ContainerRuntime
