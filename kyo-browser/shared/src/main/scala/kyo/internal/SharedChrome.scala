package kyo.internal

import kyo.*

/** A Chrome instance shared across all callers in a JVM/Node/Native run.
  *
  * Only the WebSocket URL is shared. Each [[run]] opens its own connection and tab through `Browser.run(url)`; this avoids
  * resource-lifecycle issues that arise when a single `CdpClient` is shared across many scopes.
  *
  * Chrome is launched inside a long-lived background fiber that holds the scope open with `Async.never`. When the kyo scheduler shuts down
  * (JVM exit, Node exit, Native exit), the fiber is interrupted and the scope's finalizers run, destroying Chrome and cleaning up its temp
  * user-data directory.
  *
  * The state lives in an [[Instance]]; the object delegates to one built with the Chrome-for-Testing launch and the orphan sweep.
  */
private[kyo] object SharedChrome:

    /** Launch and sweep hooks plus the shared state. Tests build their own with a fake launch.
      *
      * The state is a generation: one launch, the promise its URL completes, and the fiber that holds its scope open. A caller that finds
      * the generation's Chrome gone (its launch failed, or a call against it lost the connection) replaces that generation, and only if it
      * is still the current one, so callers that all saw the same dead Chrome cause a single relaunch. The replaced generation's fiber is
      * interrupted, which closes its scope: a Chrome that is still running but unusable is killed and its directory removed rather than
      * held until the process exits.
      */
    final private[kyo] class Instance(
        launch: Frame => String < (Async & Scope & Abort[BrowserSetupException]),
        sweep: Frame => Unit < Async
    ):
        // Unsafe: the instance is built outside any effect; every read and write goes through Sync.Unsafe.defer.
        private val current =
            given AllowUnsafe = AllowUnsafe.embrace.danger
            AtomicRef.Unsafe.init(Generation())
        end current

        /** Returns the WebSocket debug URL of the shared Chrome process, launching it on first call. */
        def init(using Frame): String < (Async & Abort[BrowserSetupException]) =
            Sync.Unsafe.defer(current.get()).map(generation => start(generation).andThen(generation.url.safe.get))

        /** Runs `body` in a fresh tab of the shared Chrome, relaunching the Chrome once if it is gone before `body` has started.
          *
          * When Chrome crashes mid-run the cached URL points at a port nothing listens on, so every later caller reusing it fails its
          * WebSocket handshake; when a launch fails, its failure would otherwise be the answer to every later caller. So a failure that means
          * the shared Chrome is gone ([[BrowserConnectionLostException]] or [[BrowserSetupFailedException]]) always replaces the generation,
          * and the next caller gets a fresh Chrome.
          *
          * The call itself is retried only when the failure came before `body` started (the launch, the connect, or the tab setup). Once
          * `body` has started it may have acted on state outside the browser (a click that reached a server, a counter it advanced), and
          * running it again would act twice, so the failure propagates to the caller, who owns that state and can rebuild it before trying
          * again. Every other failure (assertion, timeout, navigation, element, ...) propagates immediately.
          */
        def run[A, S](body: String => A < (Browser & S))(using
            Frame
        ): A < (Async & Abort[BrowserReadException | BrowserSetupException] & S) =
            withUrl((url, started) => Browser.run(url)(started.andThen(body(url))))

        /** The generation and retry logic of [[run]], with the connection left to `f`. `f` runs `started` at the point where the caller's
          * body begins, after which a failure is never retried.
          */
        private[kyo] def withUrl[A, S](f: (String, Unit < Sync) => A < (Async & Abort[BrowserReadException | BrowserSetupException] & S))(
            using Frame
        ): A < (Async & Abort[BrowserReadException | BrowserSetupException] & S) =
            def attempt(relaunches: Int): A < (Async & Abort[BrowserReadException | BrowserSetupException] & S) =
                AtomicBoolean.init(false).map { started =>
                    Sync.Unsafe.defer(current.get()).map { generation =>
                        Abort.recover[BrowserConnectionLostException | BrowserSetupFailedException] { e =>
                            replace(generation).andThen(started.get).map { bodyStarted =>
                                if bodyStarted || relaunches == 0 then Abort.fail(e) else attempt(relaunches - 1)
                            }
                        } {
                            start(generation).andThen(generation.url.safe.get).map(url => f(url, started.set(true)))
                        }
                    }
                }
            attempt(relaunches = 1)
        end withUrl

        /** Makes a fresh generation current if `stale` still is, and closes the scope of `stale`'s Chrome. */
        private def replace(stale: Generation)(using Frame): Unit < Sync =
            Sync.Unsafe.defer {
                if current.compareAndSet(stale, Generation()) then stale.release()
            }

        private def start(generation: Generation)(using frame: Frame): Unit < Async =
            Sync.Unsafe.defer {
                if generation.started.compareAndSet(false, true) then
                    sweep(frame).andThen {
                        Fiber.initUnscoped {
                            // An interrupted launch completes nothing below, so a caller waiting on this generation would wait
                            // forever; it gets a failure that sends it to the current generation instead.
                            Sync.ensure(Sync.Unsafe.defer(discard(generation.url.complete(Result.Failure(
                                BrowserSetupFailedException("the shared Chrome was replaced before its launch finished")
                            ))))) {
                                Scope.run {
                                    Abort.run[BrowserSetupException] {
                                        for
                                            url <- launch(frame)
                                            _   <- Sync.Unsafe.defer(discard(generation.url.complete(Result.Success(url))))
                                            // Holds the scope open until the generation is replaced or the runtime shuts down.
                                            _ <- Async.never
                                        yield ()
                                    }.map {
                                        case Result.Success(_)  => Kyo.unit
                                        case Result.Failure(ex) =>
                                            Sync.Unsafe.defer(discard(generation.url.complete(Result.Failure(ex))))
                                        case Result.Panic(t) =>
                                            Sync.Unsafe.defer(discard(generation.url.complete(Result.Panic(t))))
                                    }
                                }
                            }
                        }.map { holder =>
                            Sync.Unsafe.defer {
                                generation.holder.set(Present(holder.unsafe))
                                // A replace that ran before the holder was recorded found nothing to interrupt.
                                if current.get() ne generation then generation.release()
                            }
                        }
                    }
                else Kyo.unit
                end if
            }
    end Instance

    final private class Generation()(using AllowUnsafe):
        val url     = Promise.Unsafe.init[String, Abort[BrowserSetupException]]()
        val started = AtomicBoolean.Unsafe.init(false)
        val holder  = AtomicRef.Unsafe.init[Maybe[Fiber.Unsafe[Unit, Any]]](Absent)

        /** Interrupts the fiber holding this generation's scope, if it has started. Closing the scope kills the Chrome it launched. */
        def release()(using Frame): Unit =
            holder.get().foreach(fiber => discard(fiber.interrupt()))
    end Generation

    private val shared = new Instance(
        frame =>
            given Frame = frame
            chromeConfig.map(cfg => BrowserLauncher.launch(cfg))
        ,
        frame =>
            given Frame = frame
            BrowserLauncher.killOrphans(pattern = BrowserLauncher.userDataDirPrefix, command = "pgrep")
    )

    /** Returns the WebSocket debug URL of the shared Chrome process, launching it on first call. */
    def init(using Frame): String < (Async & Abort[BrowserSetupException]) =
        shared.init

    /** See [[Instance.run]]. */
    def run[A, S](body: String => A < (Browser & S))(using
        Frame
    ): A < (Async & Abort[BrowserReadException | BrowserSetupException] & S) =
        shared.run(body)

    /** See [[Instance.withUrl]]: for a harness that runs setup of its own in the tab before the caller's body, and marks the body's
      * start after it.
      */
    private[kyo] def withUrl[A, S](f: (String, Unit < Sync) => A < (Async & Abort[BrowserReadException | BrowserSetupException] & S))(
        using Frame
    ): A < (Async & Abort[BrowserReadException | BrowserSetupException] & S) =
        shared.withUrl(f)

    /** Cross-platform Chrome launch config that downloads Chrome-for-Testing on first call (cached for subsequent calls). */
    def chromeConfig(using Frame): Browser.LaunchConfig < (Async & Abort[BrowserSetupException]) =
        Browser.chromeForTestingLaunchConfig()

end SharedChrome
