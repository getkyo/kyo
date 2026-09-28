package kyo

import kyo.internal.ChromeDownloader
import kyo.internal.SharedChrome

/** Base for kyo-browser suites that actually drive a headless Chrome.
  *
  * Adds the Chrome lifecycle on top of [[BaseBrowserTest]]'s config and helpers: a per-leaf pre-launch of the shared
  * Chrome, a clean cancel on platforms with no chrome-headless-shell artifact, and the transient-failure retry of
  * [[BaseChromeTest.retryTransient]]. Only suites that open a browser extend this; the launcher/selector/key/image/exception/downloader
  * unit tests extend [[BaseBrowserTest]] directly, so they never start a Chrome that would otherwise be launched for every leaf and torn
  * down at JVM exit.
  */
abstract class BaseChromeTest extends BaseBrowserTest:

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

    /** Runs before every retry attempt of a leaf, never before the first. A suite overrides it to reset per-suite state that assumes the
      * Chrome of the failed attempt, since the next attempt may run against a relaunched one.
      */
    def onRetry(using Frame): Unit < Sync = Kyo.unit

    // Cancel every leaf cleanly on platforms with no chrome-headless-shell artifact. The cancel is deferred
    // into a `Sync` so the runner discharges it as a Cancelled result rather than an eager throw.
    //
    // The retry wraps the whole leaf, not the browser scope inside it: a leaf that counts requests or binds a server outside
    // `withBrowser` must restart from that state too, or a transient failure on it becomes a deterministic one on the retry.
    override def aroundLeaf[A](body: A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        chromeUnsupportedReason match
            case Some(reason) => Sync.defer(cancel(reason))
            case None         =>
                BaseChromeTest.retryTransient(
                    getClass.getName,
                    BaseChromeTest.transientRetrySchedule,
                    BaseChromeTest.transientFailures,
                    onRetry
                ) {
                    // Pre-launch the shared Chrome here: the runner runs `aroundLeaf` outside the per-leaf timeout, so the
                    // first browser leaf never pays Chrome's first-call download+launch inside its own 60s budget (a cold
                    // download on a slow runner would blow a single leaf). The launch is CAS-gated, a cheap no-op after the
                    // first leaf. The 5-minute timeout is a backstop: a genuinely stuck launch fails the leaf instead of
                    // hanging the CI job, since this hook is not covered by the per-leaf timeout.
                    Async.timeout(5.minutes)(SharedChrome.withUrl((_, _) => Kyo.unit)).andThen(body)
                }

end BaseChromeTest

object BaseChromeTest:

    /** The Chrome-infrastructure failures a fresh attempt of the leaf rides out: a dropped CDP connection, a Chrome that failed to
      * launch, and a page request that failed below HTTP (on a Windows runner a WSAENOBUFS socket failure). Every other
      * `BrowserException` and every assertion failure propagates on the first attempt. `BrowserNavigationTransportFailedException` is
      * a separate type from `BrowserNavigationFailedException` so that naming it here cannot also retry an HTTP 404.
      */
    type TransientBrowserFailure =
        BrowserConnectionLostException | BrowserSetupFailedException | BrowserNavigationTransportFailedException

    /** Two retries, so three attempts. Backoff starts at 1s so the OS resources of the failed attempt settle before the next launch.
      *
      * The worst case per leaf is three attempts, each bounded by its own leaf timeout and, in kyo-browser, by its own five-minute
      * launch backstop, plus 3s of backoff.
      */
    val transientRetrySchedule: Schedule =
        Schedule.exponentialBackoff(initial = 1.second, factor = 2, maxBackoff = 8.seconds).take(2)

    /** Every transient failure this process has seen, including one on a leaf's last attempt. It is a process count: on the JVM the
      * build forks one process per suite, so it is the suite's count; on JS, Native and Wasm every suite shares it. Each failure is
      * also logged, so a job log carries the runner's transient rate whether or not a leaf ended red.
      */
    private[kyo] val transientFailures: AtomicInt =
        import AllowUnsafe.embrace.danger
        // Unsafe: a process-lifetime counter created at class load, outside any effect.
        AtomicInt.Unsafe.init(0).safe
    end transientFailures

    /** Runs `body` under the transient-failure retry: each attempt in its own `Scope`, so a server or tab the failed attempt acquired
      * is released before the next one starts, and each [[TransientBrowserFailure]] logged as one warn line and counted in `counter`
      * before it is retried or, once `schedule` is spent, propagated. `onRetry` runs before every attempt after the first.
      */
    def retryTransient[A](suite: String, schedule: Schedule, counter: AtomicInt, onRetry: Unit < Sync)(
        body: => A < (Async & Abort[Any] & Scope)
    )(using Frame): A < (Async & Abort[Any] & Scope) =
        AtomicInt.initWith(0) { attempts =>
            def logged(attempt: Int)(ex: TransientBrowserFailure): A < (Async & Abort[TransientBrowserFailure]) =
                counter.incrementAndGet.map { total =>
                    Log.warn(
                        s"transient browser failure ${ex.getClass.getSimpleName} at ${ex.frame.position.show} on attempt $attempt of $suite " +
                            s"(process total $total): ${detail(ex)}"
                    ).andThen(Abort.fail[TransientBrowserFailure](ex))
                }
            Retry[TransientBrowserFailure](schedule) {
                attempts.incrementAndGet.map { attempt =>
                    (if attempt > 1 then onRetry else Kyo.unit).andThen {
                        Abort.recover[TransientBrowserFailure](logged(attempt))(Scope.run(body))
                    }
                }
            }
        }
    end retryTransient

    // Not `getMessage`: in development mode a KyoException renders it as a multi-line highlighted frame snippet.
    private def detail(ex: TransientBrowserFailure): String =
        ex match
            case e: BrowserConnectionLostException            => withCause(e.message, e.cause)
            case e: BrowserSetupFailedException               => withCause(e.message, e.cause)
            case e: BrowserNavigationTransportFailedException => s"navigation failed below HTTP on ${e.url}"

    private def withCause(message: String, cause: Maybe[Throwable]): String =
        firstLine(message) + cause.fold("")(c => s" (${c.getClass.getSimpleName}: ${firstLine(c.getMessage)})")

    private def firstLine(text: String): String =
        val lines = Maybe(text).fold(Iterator.empty[String])(_.linesIterator).map(_.trim).filter(_.nonEmpty)
        if lines.hasNext then lines.next() else ""

end BaseChromeTest
