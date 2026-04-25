package kyo

import kyo.internal.BaseKyoCoreTest
import kyo.internal.Platform
import org.scalatest.NonImplicitAssertions
import org.scalatest.Tag
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

abstract class Test extends AsyncFreeSpec with NonImplicitAssertions with BaseKyoCoreTest:

    override def timeout = 60.seconds

    private def runWhen(cond: => Boolean) = if cond then "" else "org.scalatest.Ignore"
    object jvmOnly         extends Tag(runWhen(kyo.internal.Platform.isJVM))
    object jsOnly          extends Tag(runWhen(kyo.internal.Platform.isJS))
    object containerOnly   extends Tag(runWhen(ContainerRuntime.available.nonEmpty))
    object httpBackendOnly extends Tag(runWhen(true))

    type Assertion = org.scalatest.Assertion

    def assertionSuccess = succeed

    def assertionFailure(msg: String) = fail(msg)

    override given executionContext: ExecutionContext = Platform.executionContext

    /** Register one leaf test per available `(runtime, backend)` combination. Each registered test runs `v` with the appropriate
      * `Container.withBackendConfig` wrapper. Use as the body of `String -` in test declarations:
      * {{{"my container test" - runBackends { Container.init(image).map(_ => ()) }}}}
      */
    def runBackends(v: => Assertion < (Async & Abort[Any] & Scope))(using Frame): Unit =
        Seq("podman", "docker").filter(ContainerRuntime.isAvailable).foreach { runtime =>
            runtime - {
                ContainerRuntime.findSocket(runtime).foreach { path =>
                    "http" in run {
                        Container.withBackendConfig(_.UnixSocket(Path(path)))(v)
                    }
                }

                "shell" in run {
                    Container.withBackendConfig(_.Shell(runtime))(v)
                }
            }
        }

    /** Register one leaf test per available container runtime (docker, podman). The test body receives the runtime name as a parameter —
      * use this when the test logic needs to construct a backend config explicitly or branch on runtime identity. The body picks its own
      * backend (HTTP or Shell); no outer `withBackendConfig` is applied.
      *
      * Use as the body of `String -` in test declarations: {{{"auto-detect prefers HTTP" - runRuntimes { runtime => ... }}}}
      */
    def runRuntimes(f: String => Assertion < (Async & Abort[Any] & Scope))(using Frame): Unit =
        Seq("podman", "docker").filter(ContainerRuntime.isAvailable).foreach { runtime =>
            runtime in run {
                f(runtime)
            }
        }
end Test
