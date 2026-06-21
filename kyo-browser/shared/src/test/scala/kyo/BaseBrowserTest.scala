package kyo

import kyo.internal.ChromeDownloader

abstract class BaseBrowserTest extends kyo.test.Test[Any]:

    // Browser suites drive a single per-suite Chrome (the sbt build forks one JVM + one SharedChrome per suite
    // and runs suites serially). Run each suite's leaves sequentially too: under kyo-test's default leaf
    // parallelism dozens of leaves hammer that one Chrome at once, producing BrowserProtocolErrorExceptions and
    // timeouts. ScalaTest's AsyncFreeSpec ran leaves sequentially within a suite; this restores that.
    //
    // failOnNoAssertion is disabled for the same reason kyo-ui's UITest disables it: browser suites verify
    // through Browser.assert* domain helpers and expected-exception fail-paths (Abort.run(...) { case Failure(_: X)
    // => () ; case _ => fail(...) }) that do not flow through the kyo.test assert macros, so the per-leaf
    // evaluation counter sees zero even though the leaf does verify behavior. The check is a false positive here.
    //
    // Only the socket category of leak detection is disabled, not the whole check. SharedChrome deliberately holds its
    // CDP connection socket open for the WHOLE run (torn down at scheduler shutdown, see SharedChrome.ensureStarted),
    // and a CDP socket is an opaque `socket:[inode]` with no stable identifier an allowlist could match, so the socket
    // category is the one resource that cannot be expressed any finer. The other long-lived resource, the kyo-http
    // NioIoDriver event-loop fiber, is already covered by the built-in allowlist, so fiber, thread, and file-descriptor
    // detection stay on.
    override def config = super.config.sequential.failOnNoAssertion(false).leakCheckSockets(false)

    // Pre-flight: check whether the current (OS, arch) tuple has a chrome-headless-shell artifact
    // (mac-arm64 / mac-x64 / linux64 / win64 / win32). Linux/Aarch64 and Windows/ARM have no published
    // artifact, so any test that needs Chrome cannot run; cancel the leaf cleanly with the install
    // instructions instead of letting the BrowserSetupException leak as a red failure. Reuses
    // `ChromeDownloader.resolvePlatform` as the single source of truth for which tuples are supported.
    private lazy val chromeUnsupportedReason: Option[String] =
        import AllowUnsafe.embrace.danger
        // Unsafe: tests are off the main effect stack; evaluating the platform check synchronously is the
        // cleanest way to make the verdict available to the `aroundLeaf` hook below.
        Sync.Unsafe.evalOrThrow {
            for
                os      <- System.operatingSystem
                arch    <- System.architecture
                outcome <- Abort.run[BrowserSetupException](ChromeDownloader.resolvePlatform(os, arch))
            yield outcome match
                case Result.Success(_)  => None
                case Result.Failure(ex) => Option(ex.getMessage)
                case Result.Panic(ex)   => Option(ex.getMessage)
        }
    end chromeUnsupportedReason

    // Cancel every leaf cleanly on platforms with no chrome-headless-shell artifact. Ported from the ScalaTest
    // base's `run` override to the kyo-test `aroundLeaf` hook; the cancel is deferred into a `Sync` so the runner
    // discharges it as a Cancelled result rather than an eager throw.
    override def aroundLeaf[A](body: A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        chromeUnsupportedReason match
            case Some(reason) => Sync.defer(cancel(reason))
            case None         => body

    /** JSON decode helper used across CDP tests. */
    def decode[A: Schema](json: String)(using Frame, kyo.test.AssertScope): A =
        Json.decode[A](json) match
            case Result.Success(v) => v
            case other             => fail(s"decode failed: $other")

    /** Decodes a CDP wire response (the whole `{id, result, error}` envelope) into the typed result `A`. The
      * dispatcher carrier is the entire wire frame, so any test that takes a `client.send(...)` reply and wants
      * the typed result calls this helper instead of `decode[A]` directly.
      */
    def decodeCdpResult[A: Schema](wire: String)(using Frame, kyo.test.AssertScope): A =
        Json.decode[kyo.internal.CdpReply[A]](wire) match
            case Result.Success(reply) =>
                reply.result match
                    case Present(v) => v
                    case Absent =>
                        reply.error match
                            case Present(err) => fail(s"expected CdpReply.result but got error: $err")
                            case Absent       => fail(s"CdpReply has neither result nor error: $reply")
            case other => fail(s"CdpReply decode failed: $other")

    /** Measures the elapsed monotonic duration of a computation. */
    def timed[A, S](v: A < (Async & S))(using Frame): (Duration, A) < (Async & S) =
        Clock.nowMonotonic.map { t0 =>
            v.map { a => Clock.nowMonotonic.map { t1 => (t1 - t0, a) } }
        }

    /** Outer `Result.{Success,Failure,Panic}` fold helper that fails the test on `Failure`/`Panic`.
      *
      * The `PANIC: ` prefix on the panic path makes it visually obvious in test output that an unexpected programming bug surfaced (as
      * opposed to a typed-failure path that the test ought to handle). Without the prefix, `Failure` and `Panic` would collapse into the
      * same shape of fail message and a panic could be mistaken for an expected typed-error path that was caught too loosely.
      */
    extension [E <: Throwable, A, S](v: Result[E, A] < (Async & S))
        def orFail(label: String)(using Frame, kyo.test.AssertScope): A < (Async & S) =
            v.map {
                case Result.Success(a)   => a
                case Result.Failure(err) => fail(s"$label failed: ${err.getMessage}")
                case Result.Panic(ex)    => fail(s"PANIC: $label panic: ${ex.getMessage}")
            }
    end extension
end BaseBrowserTest
