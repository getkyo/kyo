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

    /** POSIX's "command not found" exit code, which a shell uses for a binary that is not on PATH. */
    private val CommandNotFound = 127

    /** Whether the `command` binary is on PATH, however it exits.
      *
      * The two platforms report a missing binary differently, and only one of them raises. On the JVM
      * `ProcessBuilder.start` throws `IOException`, so the spawn answers the question. Scala Native runs the
      * command through `/bin/sh`, so the spawn SUCCEEDS, the shell prints `command not found` and exits 127,
      * which is what both platforms can agree on: a spawn that raises, or an exit of 127, means absent.
      * Anything else means the binary ran, whatever it thought of its daemon, which is the distinction the
      * availability decision rests on. Measured on Scala Native, not assumed: `exit=127, output=[/bin/sh:
      * kyo-pod-no-such-binary: command not found]`.
      */
    private[kyo] def cliPresent(command: String): Boolean =
        val pb = new java.lang.ProcessBuilder(command, "version")
        pb.redirectErrorStream(true)
        scala.util.Try {
            val proc = pb.start()
            discard(proc.getInputStream.readAllBytes())
            proc.waitFor() != CommandNotFound
        }.getOrElse(false)
    end cliPresent

    private[kyo] def queryPodmanMachineSockets: Seq[String] =
        try
            // `--format` is a Go template, so the JSON form is `{{json .}}`; a bare `json` is emitted literally (podman
            // prints "json"), leaving nothing for the api.sock scrape below and, on macOS, falling back to Docker Desktop.
            val pb = new java.lang.ProcessBuilder("podman", "machine", "inspect", "--format", "{{json .}}")
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
