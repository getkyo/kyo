package kyo.internal

import kyo.*

/** A Chrome instance shared across all callers in a JVM/Node/Native run.
  *
  * Only the WebSocket URL is shared. Each caller creates its own `CdpClient` (or uses `Browser.run(url)`); this avoids resource-lifecycle
  * issues that arise when a single `CdpClient` is shared across many scopes.
  *
  * Chrome is launched inside a long-lived background fiber that holds the scope open with `Async.never`. When the kyo scheduler shuts down
  * (JVM exit, Node exit, Native exit), the fiber is interrupted and the scope's finalizers run, destroying Chrome and cleaning up its temp
  * user-data directory.
  *
  * **Unsafe boundary.** The `private var cachedUrl` and `private val initStarted` below initialise a `Promise.Unsafe` and
  * `AtomicBoolean.Unsafe` at static (val) init time, before any kyo `Sync` / `Async` handler is on the call stack to embrace the unsafe
  * primitive. All subsequent reads/writes go through the safe wrappers (`cachedUrl.safe.get`, `initStarted.compareAndSet` inside
  * `Sync.Unsafe.defer`), confining the unsafe construction to the val-init seam. `cachedUrl` is a `@volatile var` so `invalidate` can swap
  * the Promise atomically; the `@volatile` annotation guarantees visibility of the new Promise to all subsequent readers.
  */
private[kyo] object SharedChrome:

    // Static-init boundary; see the `Unsafe boundary` paragraph on the enclosing object.
    // `@volatile var` (not `val`) so `invalidate` can swap the Promise when Chrome dies.
    @volatile private var cachedUrl: Promise.Unsafe[String, Abort[BrowserSetupException]] =
        // Unsafe: static var init runs before any Sync handler is on the stack; subsequent reads/writes go through .safe.
        import AllowUnsafe.embrace.danger
        Promise.Unsafe.init[String, Abort[BrowserSetupException]]()
    end cachedUrl

    private val initStarted =
        // Unsafe: static val init runs before any Sync handler is on the stack; subsequent reads/writes go through .safe.
        import AllowUnsafe.embrace.danger
        AtomicBoolean.Unsafe.init(false)
    end initStarted

    /** Returns the WebSocket debug URL of the shared Chrome process, launching it on first call. */
    def init(using Frame): String < (Async & Abort[BrowserSetupException]) =
        ensureStarted.andThen(cachedUrl.safe.get)

    /** Run `f` against the shared Chrome's WS URL with failure-driven retry.
      *
      * If `f` fails because Chrome is dead ([[BrowserSetupFailedException]] from the Q-002 probe gate, or
      * [[BrowserConnectionLostException]] from transport-setup failure), the cache is invalidated, Chrome is relaunched, and `f` is retried
      * once with the fresh URL. Any other failure — or a second-level failure after relaunch — propagates to the caller unchanged.
      *
      * This is the preferred entry point over calling [[init]] directly: callers get automatic recovery from mid-suite Chrome crashes
      * without having to implement their own retry logic.
      *
      * Uses `kyo.Retry` with a one-shot immediate schedule (no added delay; the relaunch itself takes ~2-3 s wall-clock).
      */
    def withUrl[A, S](
        f: String => A < (Async & Abort[BrowserReadException | BrowserSetupException] & S)
    )(using Frame): A < (Async & Abort[BrowserReadException | BrowserSetupException] & S) =
        // One retry, no extra delay. Schedule.repeat(1) = Schedule.immediate.repeat(1):
        // yields exactly one Step (Duration.Zero) then Done, so Retry fires exactly once on failure.
        val schedule = Schedule.repeat(1)
        Retry[BrowserSetupFailedException | BrowserConnectionLostException](schedule) {
            init.map { url =>
                // Intercept only the two "Chrome is dead" error types: invalidate the cache so
                // the retry's `init` call sees an empty cache and re-launches, then re-raise so
                // Retry can schedule the next attempt.  All other failures (navigation, script,
                // etc.) are not handled here and remain in the outer error channel.
                Abort.recover[BrowserSetupFailedException | BrowserConnectionLostException] {
                    e => invalidate.andThen(Abort.fail(e))
                } {
                    f(url)
                }
            }
        }
    end withUrl

    /** Atomically reset the cache so the next [[init]] call re-launches Chrome.
      *
      * Thundering-herd safe: if N concurrent callers invalidate simultaneously, only the first `initStarted` CAS wins the relaunch; the
      * rest spin into the same new Promise and await its completion. Replace `cachedUrl` before clearing `initStarted` so any reader that
      * already holds the stale (failed) Promise continues to see it, while readers entering after the swap see the new pending Promise.
      */
    private[kyo] def invalidate(using Frame): Unit < Sync =
        // AllowUnsafe is provided by Sync.Unsafe.defer; no explicit import needed here.
        Sync.Unsafe.defer {
            cachedUrl = Promise.Unsafe.init[String, Abort[BrowserSetupException]]()
            initStarted.set(false)
        }

    private def ensureStarted(using Frame): Unit < Async =
        Sync.Unsafe.defer {
            if initStarted.compareAndSet(false, true) then
                // Sweep prior-run orphans BEFORE our own Chrome exists. The pgrep pattern matches any process
                // whose argv contains `user-data-dir=...kyo-browser-...`, including the Chrome we are about to
                // launch. Running the sweep AFTER our launch would kill our own Chrome on subsequent init calls.
                BrowserLauncher.killOrphans(pattern = "kyo-browser-", command = "pgrep").andThen {
                    // Fork a detached fiber that holds Chrome's scope open for the whole run. When the kyo
                    // scheduler shuts down, the fiber is interrupted and the scope's finalizers tear down
                    // Chrome and its temp user-data directory.
                    //
                    // Setup is wrapped in Abort.run so a BrowserSetupException (e.g. unsupported platform on
                    // linux-arm64) lands on the cachedUrl Promise as a Failure rather than vanishing into the
                    // detached fiber. Without this, callers awaiting cachedUrl.safe.get hang until the
                    // surrounding test/op timeout fires instead of seeing the actual cause.
                    Fiber.initUnscoped {
                        Scope.run {
                            Abort.run[BrowserSetupException] {
                                for
                                    cfg <- chromeConfig
                                    url <- BrowserLauncher.launch(cfg)
                                    // Idempotent: cachedUrl.complete returns false on a second completion; we discard the
                                    // boolean because the cachedUrl is consumed via .safe.get downstream which awaits whichever
                                    // completion landed first.
                                    _ <- Sync.Unsafe.defer(discard(cachedUrl.complete(Result.Success(url))))
                                    // Holds the scope open until the fiber is interrupted on runtime shutdown.
                                    _ <- Async.never
                                yield ()
                            }.map {
                                case Result.Success(_) => Kyo.unit
                                case Result.Failure(ex) =>
                                    Sync.Unsafe.defer(discard(cachedUrl.complete(Result.Failure(ex))))
                                case Result.Panic(t) =>
                                    Sync.Unsafe.defer(discard(cachedUrl.complete(Result.Panic(t))))
                            }
                        }
                    }.unit
                }
            else Kyo.unit
            end if
        }

    /** Cross-platform Chrome launch config that downloads Chrome-for-Testing on first call (cached for subsequent calls). */
    def chromeConfig(using Frame): Browser.LaunchConfig < (Async & Abort[BrowserSetupException]) =
        Browser.chromeForTestingLaunchConfig()

end SharedChrome
