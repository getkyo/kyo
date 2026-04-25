package kyo

object ContainerRuntime extends ContainerRuntimeBase:

    protected def socketExists(path: String): Boolean =
        val p = java.nio.file.Path.of(path)
        java.nio.file.Files.exists(p) || java.nio.file.Files.exists(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)

    protected def cliExists(command: String): Boolean =
        val pb = new java.lang.ProcessBuilder(command, "version")
        pb.redirectErrorStream(true)
        scala.util.Try {
            val proc = pb.start()
            proc.getInputStream.readAllBytes()
            proc.waitFor() == 0
        }.getOrElse(false)
    end cliExists

    protected def getEnv(name: String): String | Null = java.lang.System.getenv(name)

    protected def getHome: String = java.lang.System.getProperty("user.home", "")

    protected def queryPodmanMachineSockets: Seq[String] =
        try
            val pb = new java.lang.ProcessBuilder("podman", "machine", "inspect", "--format", "json")
            pb.redirectErrorStream(true)
            val proc   = pb.start()
            val output = new String(proc.getInputStream.readAllBytes())
            proc.waitFor()
            val pathPattern = """"Path"\s*:\s*"([^"]+api\.sock[^"]*)"""".r
            pathPattern.findFirstMatchIn(output).map(_.group(1)).toSeq
        catch
            case _: Throwable => Seq.empty

end ContainerRuntime
