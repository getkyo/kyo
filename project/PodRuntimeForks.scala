import java.util.concurrent.TimeUnit
import sbt.util.Logger
import scala.io.Source
import scala.util.Try

/** Decides which container runtimes kyo-pod's daemon suites fork for.
  *
  * Each daemon suite runs once per runtime, in a fork pinned to it. A fork whose runtime does not answer registers no runtime leaves, and
  * kyo-test fails a fork whose selection ran nothing, so an idle fork turns a filtered `testOnly` red on any host that lacks one of the
  * runtimes. The probe therefore runs here, when the grouping task is evaluated, and never while settings load: sbt must start on a host
  * whose daemons are down.
  *
  * `KYO_POD_EXPECTED_RUNTIMES` is what separates a CI runner from a workstation. The setup action sets it on every runner, to the runtimes
  * it provisions (possibly none), and `scripts/container-check.sh` gates exactly that list. A runtime the variable names that does not
  * answer fails the task; with the variable unset, a runtime that does not answer is dropped with a logged line.
  */
object PodRuntimeForks {

    val Runtimes: Seq[String] = Seq("podman", "docker")

    /** The runtimes to fork for, after reporting every one that is dropped. `pinned` is an outer `KYO_POD_RUNTIME`, which restricts the
      * candidates to itself (`none` restricts them to nothing).
      */
    def select(expected: Option[String], pinned: Option[String], log: Logger): Seq[String] = {
        val expectedSet = expected.map(_.split("[\\s,]+").iterator.filter(_.nonEmpty).toSet)
        val candidates = pinned.filter(_.nonEmpty) match {
            case Some(rt) =>
                Runtimes.filterNot(_ == rt).foreach(other => log.warn(s"kyo-pod: no $other fork for the container suites: KYO_POD_RUNTIME=$rt"))
                Runtimes.filter(_ == rt)
            case None => Runtimes
        }
        val unknown = expectedSet.getOrElse(Set.empty) -- Runtimes
        if (unknown.nonEmpty)
            sys.error(s"KYO_POD_EXPECTED_RUNTIMES names ${unknown.toSeq.sorted.mkString(", ")}; kyo-pod knows ${Runtimes.mkString(", ")}")
        candidates.filter { runtime =>
            probe(runtime) match {
                case None => true
                case Some(reason) =>
                    if (expectedSet.exists(_.contains(runtime)))
                        sys.error(s"kyo-pod: $runtime is expected on this runner (KYO_POD_EXPECTED_RUNTIMES) but $reason")
                    log.warn(s"kyo-pod: no $runtime fork for the container suites: $reason")
                    false
            }
        }
    }

    // `version` reports the server only when the daemon answers; the CLI alone prints a client section and exits non-zero. docker's CLI
    // pointed at podman's socket (podman-docker) answers with podman's engine, which is the podman fork's daemon a second time.
    // podman's version template has no `Components` field and rejects one, so only docker's lists the engine.
    private def probe(runtime: String): Option[String] = {
        val format =
            if (runtime == "docker") "{{.Server.Version}} {{range .Server.Components}}{{.Name}};{{end}}"
            else "{{.Server.Version}}"
        run(Seq(runtime, "version", "--format", format)) match {
            case Left(reason)                                         => Some(reason)
            case Right(out) if runtime == "docker" && out.contains("Podman") => Some("its daemon is podman's, which the podman fork covers")
            case Right(_)                                             => None
        }
    }

    // A daemon that hangs instead of refusing is as absent as one that refuses, and must not hang the build.
    private val ProbeTimeoutSeconds = 30L

    // The output goes to a file, not a pipe: reading a pipe blocks until the CLI exits, which would defeat the timeout.
    private def run(command: Seq[String]): Either[String, String] = {
        val out = java.io.File.createTempFile("kyo-pod-probe", ".out")
        try
            Try(new ProcessBuilder(command: _*).redirectErrorStream(true).redirectOutput(out).start()).toEither.left
                .map(_ => "its CLI is not installed")
                .flatMap { process =>
                    if (!process.waitFor(ProbeTimeoutSeconds, TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                        Left(s"`${command.mkString(" ")}` did not finish within ${ProbeTimeoutSeconds}s")
                    } else if (process.exitValue() != 0)
                        Left(s"its daemon does not answer (`${command.head} version` exited ${process.exitValue()})")
                    else Right(Try(Source.fromFile(out).mkString).getOrElse(""))
                }
        finally {
            out.delete()
            ()
        }
    }
}
