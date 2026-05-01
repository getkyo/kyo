package kyo

/** JVM/Native implementation of `ContainerRuntimeBase`.
  *
  * Uses `java.lang.ProcessBuilder` and `java.nio.file.Files`, both available on JVM and Scala Native (via sn-java). The JS platform
  * provides its own implementation via Node.js `child_process` and `fs` modules.
  */
private[kyo] trait ContainerRuntimeJvmLike extends ContainerRuntimeBase:

    private[kyo] def socketExists(path: String): Boolean =
        val p = java.nio.file.Path.of(path)
        java.nio.file.Files.exists(p) || java.nio.file.Files.exists(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)

    private[kyo] def cliExists(command: String): Boolean =
        val pb = new java.lang.ProcessBuilder(command, "version")
        pb.redirectErrorStream(true)
        scala.util.Try {
            val proc = pb.start()
            proc.getInputStream.readAllBytes()
            proc.waitFor() == 0
        }.getOrElse(false)
    end cliExists

    private[kyo] def getEnv(name: String): String | Null = java.lang.System.getenv(name)

    private[kyo] def getHome: String = java.lang.System.getProperty("user.home", "")

    private[kyo] def queryPodmanMachineSockets: Seq[String] =
        try
            val pb = new java.lang.ProcessBuilder("podman", "machine", "inspect", "--format", "json")
            pb.redirectErrorStream(true)
            val proc   = pb.start()
            val output = new String(proc.getInputStream.readAllBytes())
            proc.waitFor()
            val pathPattern = """"Path"\s*:\s*"([^"]+api\.sock[^"]*)"""".r
            pathPattern.findFirstMatchIn(output).map(_.group(1)).toSeq
        catch
            case _: java.io.IOException        => Seq.empty
            case _: java.lang.RuntimeException => Seq.empty

end ContainerRuntimeJvmLike
