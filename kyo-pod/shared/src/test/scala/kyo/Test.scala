package kyo

import kyo.internal.BaseKyoCoreTest
import kyo.internal.Platform
import org.scalatest.NonImplicitAssertions
import org.scalatest.Tag
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

abstract class Test extends AsyncFreeSpec with NonImplicitAssertions with BaseKyoCoreTest:

    /** Test infrastructure runs synchronously at ScalaTest discovery time (Tag init, `runBackends`/`runRuntimes` test-scope registration),
      * so we provide a single given `AllowUnsafe` here and inherit it in subclasses. This lets `ContainerRuntime`'s file/env primitives
      * delegate to kyo's portable sync `Unsafe` APIs without each call site repeating the import.
      */
    given AllowUnsafe = AllowUnsafe.embrace.danger

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
      *
      * The runtime scope is rendered as `[podman]` / `[docker]` in test names — bracketed so the build's testGrouping can detect, by
      * inspecting `Suite.testNames`, which suites need per-runtime forking (the bracketed form won't appear in unit-test descriptions that
      * happen to mention "podman" or "docker" as words). Each forked JVM is pinned to a single runtime via `KYO_POD_RUNTIME`, so this
      * method registers leaves only for the pinned runtime; combined with sequential leaves, ≤1 in-flight container op per daemon.
      */
    def runBackends(v: => Assertion < (Async & Abort[Any] & Scope))(using Frame): Unit =
        Seq("podman", "docker").filter(ContainerRuntime.isAvailable).foreach { runtime =>
            s"[$runtime]" - {
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

    /** Like [[runBackends]] but raises the HTTP client's per-request timeout to 5 minutes for the http arm. Use for integration tests that
      * pull or build large images (e.g. mongo:7, mysql:8, postgres) where the default 5-second `HttpClientConfig` timeout is too short for
      * streaming `/images/create` responses on a cold cache.
      *
      * The shell arm is unaffected — it delegates to the container CLI which uses its own process timeout.
      */
    def runBackendsLong(v: => Assertion < (Async & Abort[Any] & Scope))(using Frame): Unit =
        Seq("podman", "docker").filter(ContainerRuntime.isAvailable).foreach { runtime =>
            s"[$runtime]" - {
                ContainerRuntime.findSocket(runtime).foreach { path =>
                    "http" in run {
                        Container.withBackendConfig(_.UnixSocket(Path(path))) {
                            HttpClient.withConfig(_.timeout(5.minutes))(v)
                        }
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
            s"[$runtime]" in run {
                f(runtime)
            }
        }

    /** Register a single leaf using the HTTP backend over the auto-detected runtime socket. Use this when the test exercises kyo-pod's
      * higher-level Container API (predefs, demos, parser-specific stress) and the choice of backend (HTTP vs Shell) or runtime (Podman vs
      * Docker) does not add coverage. The test runs once: one leaf, one fork — no `[runtime]` marker is registered, so the build's
      * testGrouping does not fork the suite per runtime. For tests that need runtime variation use [[runBackends]] (both backends per
      * runtime) or [[runRuntimes]] (one leaf per runtime, body picks the backend).
      */
    def runBackend(v: => Assertion < (Async & Abort[Any] & Scope))(using Frame): Unit =
        val socket = Seq("podman", "docker")
            .filter(ContainerRuntime.isAvailable)
            .iterator
            .flatMap(rt => ContainerRuntime.findSocket(rt).iterator)
            .nextOption()
        socket.foreach { path =>
            "http" in run {
                Container.withBackendConfig(_.UnixSocket(Path(path)))(v)
            }
        }
    end runBackend

    /** Like [[runBackend]] but raises the HTTP client's per-request timeout to 5 minutes. Use for single-leaf integration tests that pull
      * or build large images on a cold cache (e.g. predef DB tests).
      */
    def runBackendLong(v: => Assertion < (Async & Abort[Any] & Scope))(using Frame): Unit =
        val socket = Seq("podman", "docker")
            .filter(ContainerRuntime.isAvailable)
            .iterator
            .flatMap(rt => ContainerRuntime.findSocket(rt).iterator)
            .nextOption()
        socket.foreach { path =>
            "http" in run {
                Container.withBackendConfig(_.UnixSocket(Path(path))) {
                    HttpClient.withConfig(_.timeout(5.minutes))(v)
                }
            }
        }
    end runBackendLong

    /** Returns `config` with `autoRemove = false` so tests can inspect container state after stopping.
      *
      * Use in place of `config.autoRemove(false)` to reduce boilerplate at integration-test call sites. For example:
      * {{{
      * Container.init(alpinePersistent(alpine)).map { c => ... }
      * }}}
      */
    private[kyo] def alpinePersistent(config: Container.Config): Container.Config =
        config.autoRemove(false)

    /** Asserts that `config` produces a container in the `Running` state.
      *
      * Equivalent to:
      * {{{
      * Container.init(config).map { c => c.state.map(s => assert(s == Container.State.Running)) }
      * }}}
      *
      * Use in tests that only need to verify the container starts up successfully.
      */
    private[kyo] def assertRuns(config: Container.Config)(using Frame): Assertion < (Async & Abort[Any] & Scope) =
        Container.init(config).map(c => c.state.map(s => assert(s == Container.State.Running)))

    /** Registers a `Scope.ensure` that removes `c` (force = true) on scope exit regardless of test outcome.
      *
      * Use inside `Scope.run { Container.initUnscoped(...).map { c => ... } }` blocks as a belt-and-suspenders cleanup for containers
      * created with `initUnscoped`. The explicit `remove` in the test body is what the test asserts; this ensure covers mid-test failure
      * paths.
      */
    private[kyo] def ensureCleanup(c: Container)(using Frame): Unit < (Async & Abort[Any] & Scope) =
        Scope.ensure(Abort.run[ContainerException](c.remove(force = true)).unit)
end Test
