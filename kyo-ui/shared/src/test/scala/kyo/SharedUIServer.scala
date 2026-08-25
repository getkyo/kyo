package kyo

/** One HttpServer shared across every kyo-ui browser suite in a JVM/Node run, mirroring [[kyo.internal.SharedChrome]].
  *
  * [[UITest.withUI]] stores each leaf's UI in [[current]] and points the shared Chrome at [[url]]; the server's page and
  * WebSocket routes re-read [[current]] per request (`UIServer.handlers` takes `ui` by-name), so one long-lived server
  * serves every leaf's UI in turn. Collapsing the previous per-leaf ephemeral server into one keeps Chrome on a single
  * origin (connection reuse) and removes the Windows socket exhaustion (WSAENOBUFS / error 10055) that per-leaf ephemeral
  * ports produced. Safe without locking because leaves never overlap: JS suites run sequentially and `UITest` is
  * `.sequential`, and on the JVM each suite is its own forked JVM.
  *
  * The server runs inside a long-lived background fiber held open with `Async.never`; on scheduler shutdown the fiber is
  * interrupted and the scope's finalizers close it.
  *
  * Unsafe boundary: the `AtomicRef.Unsafe`, `Promise.Unsafe`, and `AtomicBoolean.Unsafe` below are built at val-init time,
  * before any Sync handler is on the stack; all later access goes through the safe wrappers.
  */
private[kyo] object SharedUIServer:

    // The UI the shared server serves; withUI writes it before each navigation, the handlers read it per request.
    private val current =
        import AllowUnsafe.embrace.danger
        given Frame = Frame.internal
        AtomicRef.Unsafe.init[UI](UI.div())
    end current

    @volatile private var cachedUrl: Promise.Unsafe[String, Abort[HttpBindException]] =
        import AllowUnsafe.embrace.danger
        Promise.Unsafe.init[String, Abort[HttpBindException]]()
    end cachedUrl

    private val initStarted =
        import AllowUnsafe.embrace.danger
        AtomicBoolean.Unsafe.init(false)
    end initStarted

    /** Store the UI the shared server serves on the next navigation. */
    def set(ui: UI)(using Frame): Unit < Sync = current.safe.set(ui)

    /** The shared server's base URL, binding it on first call. */
    def url(using Frame): String < (Async & Abort[HttpBindException]) =
        ensureStarted.andThen(cachedUrl.safe.get)

    private def ensureStarted(using Frame): Unit < Async =
        Sync.Unsafe.defer {
            if initStarted.compareAndSet(false, true) then
                Fiber.initUnscoped {
                    Scope.run {
                        Abort.run[HttpBindException] {
                            for
                                handlers <- UI.runHandlers("/")(current.safe.get)
                                server   <- HttpServer.init(0, "localhost")(handlers*)
                                _ <- Sync.Unsafe.defer(discard(cachedUrl.complete(Result.Success(s"http://localhost:${server.port}/"))))
                                _ <- Async.never // hold the scope open until the fiber is interrupted on shutdown
                            yield ()
                        }.map {
                            case Result.Success(_)  => Kyo.unit
                            case Result.Failure(ex) => Sync.Unsafe.defer(discard(cachedUrl.complete(Result.Failure(ex))))
                            case Result.Panic(t)    => Sync.Unsafe.defer(discard(cachedUrl.complete(Result.Panic(t))))
                        }
                    }
                }.unit
            else Kyo.unit
            end if
        }

end SharedUIServer
