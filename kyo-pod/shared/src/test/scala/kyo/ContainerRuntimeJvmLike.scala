package kyo

/** JVM/Native implementation of the per-platform process bits in `ContainerRuntimeBase`.
  *
  * Uses `java.lang.ProcessBuilder` for synchronous process spawning + exit-code wait. The file-system and environment primitives are
  * inherited from [[ContainerRuntimeBase]], which delegates to kyo's portable sync `Unsafe` APIs.
  */
private[kyo] trait ContainerRuntimeJvmLike extends ContainerRuntimeBase:

    private[kyo] def cliExists(command: String): Boolean =
        val pb = new java.lang.ProcessBuilder(command, "version")
        pb.redirectErrorStream(true)
        scala.util.Try {
            val proc = pb.start()
            proc.getInputStream.readAllBytes()
            proc.waitFor() == 0
        }.getOrElse(false)
    end cliExists

    private[kyo] def queryPodmanMachineSockets: Seq[String] =
        try
            val pb = new java.lang.ProcessBuilder("podman", "machine", "inspect", "--format", "json")
            pb.redirectErrorStream(true)
            val proc        = pb.start()
            val output      = new String(proc.getInputStream.readAllBytes())
            val _           = proc.waitFor()
            val pathPattern = """"Path"\s*:\s*"([^"]+api\.sock[^"]*)"""".r
            pathPattern.findFirstMatchIn(output).map(_.group(1)).toSeq
        catch
            case _: java.io.IOException        => Seq.empty
            case _: java.lang.RuntimeException => Seq.empty

end ContainerRuntimeJvmLike
