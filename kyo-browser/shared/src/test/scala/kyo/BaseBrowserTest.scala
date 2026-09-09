package kyo

/** Shared base for kyo-browser suites: the per-suite leaf config and the CDP/decode helpers, and nothing that launches a
  * browser. Suites that actually drive a headless Chrome extend [[BaseChromeTest]] (which adds the Chrome pre-launch and
  * the unsupported-platform cancel); suites that live in kyo-browser but never open one (the launcher, selector, key,
  * image, exception-hierarchy, and downloader unit tests) extend this directly, so they neither start Chrome nor cancel
  * where Chrome is unavailable.
  */
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
    // The two opaque-inode descriptor categories are disabled (socket + non-socket fd), not the whole check. SharedChrome
    // deliberately holds the headless Chrome process, its CDP connection socket, and the process's stdio pipes open for the
    // WHOLE run (torn down at scheduler shutdown, see SharedChrome.ensureStarted). A CDP `socket:[inode]` and a stdio
    // `pipe:[inode]` are opaque with no stable identifier an allowlist could match, so the socket and file-descriptor
    // categories are the resources that cannot be expressed any finer. The other long-lived resource, the kyo-http
    // NioIoDriver event-loop fiber, is already covered by the built-in allowlist, so fiber and thread detection stay on.
    override def config =
        super.config.sequential.failOnNoAssertion(false).leakCheckSockets(false).leakCheckFileDescriptors(false)

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
