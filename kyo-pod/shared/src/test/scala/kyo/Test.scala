package kyo

import kyo.internal.BaseKyoCoreTest
import kyo.internal.Platform
import org.scalatest.NonImplicitAssertions
import org.scalatest.Tag
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext

object ContainerRuntime:
    import scala.sys.process.*
    private def isAvailable(cmd: String): Boolean =
        try Seq(cmd, "version").!(ProcessLogger(_ => (), _ => ())) == 0
        catch case _: Exception => false

    lazy val hasPodman: Boolean = isAvailable("podman")
    lazy val hasDocker: Boolean = isAvailable("docker")
    lazy val available: Seq[String] =
        Seq("podman" -> hasPodman, "docker" -> hasDocker).collect { case (name, true) => name }

    // Convenience — true when the primary (first-detected) runtime is podman
    def isPodman: Boolean = available.headOption.contains("podman")
    def isDocker: Boolean = available.headOption.contains("docker")
end ContainerRuntime

abstract class Test extends AsyncFreeSpec with NonImplicitAssertions with BaseKyoCoreTest:

    override def timeout = 60.seconds

    private def runWhen(cond: => Boolean) = if cond then "" else "org.scalatest.Ignore"
    object jvmOnly          extends Tag(runWhen(kyo.internal.Platform.isJVM))
    object jsOnly           extends Tag(runWhen(kyo.internal.Platform.isJS))
    object containerOnly    extends Tag(runWhen(ContainerRuntime.available.nonEmpty))
    object shellBackendOnly extends Tag(runWhen(true)) // future: will be false when HTTP backend is default

    type Assertion = org.scalatest.Assertion
    def assertionSuccess              = succeed
    def assertionFailure(msg: String) = fail(msg)

    override given executionContext: ExecutionContext = Platform.executionContext
end Test
