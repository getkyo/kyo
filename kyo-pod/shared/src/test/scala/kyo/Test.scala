package kyo

import kyo.internal.BaseKyoCoreTest
import kyo.internal.Platform
import org.scalatest.NonImplicitAssertions
import org.scalatest.Tag
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext

object ContainerRuntime:
    private def socketExists(path: String): Boolean =
        val p = java.nio.file.Path.of(path)
        java.nio.file.Files.exists(p) || java.nio.file.Files.exists(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)

    private def cliExists(command: String): Boolean =
        val pb = new java.lang.ProcessBuilder(command, "version")
        pb.redirectErrorStream(true)
        val result = scala.util.Try {
            val proc = pb.start()
            proc.getInputStream.readAllBytes()
            proc.waitFor() == 0
        }
        result.getOrElse(false)
    end cliExists

    lazy val hasPodman: Boolean =
        val xdg  = java.lang.System.getenv("XDG_RUNTIME_DIR")
        val path = if xdg != null then s"$xdg/podman/podman.sock" else "/run/podman/podman.sock"
        socketExists(path) || cliExists("podman")
    end hasPodman

    lazy val hasDocker: Boolean =
        val home = java.lang.System.getProperty("user.home", "")
        socketExists(s"$home/.docker/run/docker.sock") || socketExists("/var/run/docker.sock") || cliExists("docker")

    lazy val available: Seq[String] =
        Seq("podman" -> hasPodman, "docker" -> hasDocker).collect { case (name, true) => name }

    // Convenience — true when the primary (first-detected) runtime is podman
    def isPodman: Boolean = available.headOption.contains("podman")
    def isDocker: Boolean = available.headOption.contains("docker")
end ContainerRuntime

abstract class Test extends AsyncFreeSpec with NonImplicitAssertions with BaseKyoCoreTest:

    override def timeout = 60.seconds

    private def runWhen(cond: => Boolean) = if cond then "" else "org.scalatest.Ignore"
    object jvmOnly         extends Tag(runWhen(kyo.internal.Platform.isJVM))
    object jsOnly          extends Tag(runWhen(kyo.internal.Platform.isJS))
    object containerOnly   extends Tag(runWhen(ContainerRuntime.available.nonEmpty))
    object httpBackendOnly extends Tag(runWhen(true))

    type Assertion = org.scalatest.Assertion
    def assertionSuccess              = succeed
    def assertionFailure(msg: String) = fail(msg)

    override given executionContext: ExecutionContext = Platform.executionContext
end Test
