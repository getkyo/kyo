package kyo

import kyo.internal.ChromeDownloader
import kyo.internal.SharedChrome

/** Base for kyo-browser suites that actually drive a headless Chrome.
  *
  * Adds the Chrome lifecycle on top of [[BaseBrowserTest]]'s config and helpers: a per-leaf pre-launch of the shared
  * Chrome and a clean cancel on platforms with no chrome-headless-shell artifact. Only suites that open a browser extend
  * this; the launcher/selector/key/image/exception/downloader unit tests extend [[BaseBrowserTest]] directly, so they
  * never start a Chrome that would otherwise be launched for every leaf and torn down at JVM exit.
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

    // Cancel every leaf cleanly on platforms with no chrome-headless-shell artifact. The cancel is deferred
    // into a `Sync` so the runner discharges it as a Cancelled result rather than an eager throw.
    override def aroundLeaf[A](body: A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        chromeUnsupportedReason match
            case Some(reason) => Sync.defer(cancel(reason))
            case None         =>
                // Pre-launch the shared Chrome here: the runner runs `aroundLeaf` outside the per-leaf timeout, so the
                // first browser leaf never pays Chrome's first-call download+launch inside its own 60s budget (a cold
                // download on a slow runner would blow a single leaf). The launch is CAS-gated, a cheap no-op after the
                // first leaf. The 5-minute timeout is a backstop: a genuinely stuck launch fails the leaf instead of
                // hanging the CI job, since this hook is not covered by the per-leaf timeout.
                Async.timeout(5.minutes)(SharedChrome.init).andThen(body)

end BaseChromeTest
