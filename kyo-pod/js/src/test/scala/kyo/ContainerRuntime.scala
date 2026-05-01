package kyo

import scala.scalajs.js

object ContainerRuntime extends ContainerRuntimeBase:

    private val fs           = js.Dynamic.global.require("node:fs")
    private val childProcess = js.Dynamic.global.require("node:child_process")
    private val os           = js.Dynamic.global.require("node:os")

    private[kyo] def socketExists(path: String): Boolean =
        try fs.existsSync(path).asInstanceOf[Boolean]
        catch case _: Throwable => false

    private[kyo] def cliExists(command: String): Boolean =
        try
            childProcess.execSync(s"$command version", js.Dynamic.literal(stdio = "pipe"))
            true
        catch case _: Throwable => false

    private[kyo] def getEnv(name: String): String | Null =
        val v = js.Dynamic.global.process.env.selectDynamic(name)
        if js.isUndefined(v) || v == null then null
        else v.asInstanceOf[String]
    end getEnv

    private[kyo] def getHome: String = os.homedir().asInstanceOf[String]

    private[kyo] def queryPodmanMachineSockets: Seq[String] =
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

end ContainerRuntime
