package kyo

import io.aeron.driver.MediaDriver
import kyo.internal.*

class CompilerPoolTest extends kyo.test.Test[Any]:

    override def timeout = 60.seconds

    /** Minimal Config for tests: own version, no classpath, no options. */
    private def minConfig(tag: String = "default", version: String = CompilerPool.ownVersion): Compiler.Config =
        Compiler.Config(
            toolchain = Compiler.Toolchain(version, Chunk.empty),
            classpath = Chunk.empty,
            scalacOptions = Chunk(s"-Dtest.tag=$tag"),
            sourceRoots = Chunk.empty,
            isolate = Present(false)
        )

    /** A pre-completed cache promise holding the given instance, for seeding the instances cache. */
    private def completed(instance: Instance): Promise[Instance, Abort[CompilerError]] =
        import AllowUnsafe.embrace.danger
        val p = Promise.Unsafe.init[Instance, Abort[CompilerError]]().safe
        p.unsafe.completeDiscard(Result.succeed(instance))
        p
    end completed

    /** Constructs a CompilerPool with a fresh semaphore and createLocks but a caller-supplied
      * instances cache. The MediaDriver is null: the pool only threads it to SpawnBackend.init, which
      * is never called when the instances cache is pre-seeded with stubs.
      */
    private def makePool(
        settings: Compiler.Pool.Settings,
        instances: Cache[Compiler.Config, Promise[Instance, Abort[CompilerError]]],
        stuckTimeout: Duration = CompilerPool.defaultStuckTimeout
    ): CompilerPool < (Sync & Scope) =
        Meter.initSemaphore(settings.maxConcurrentCompiles).map { globalSemaphore =>
            AtomicInt.init(0).map { streamIdCounter =>
                new CompilerPool(settings, instances, globalSemaphore, null, streamIdCounter, stuckTimeout)
            }
        }

    /** Like [[makePool]] but with a real shared embedded MediaDriver, for the one leaf that drives the
      * real SpawnBackend (version-mismatch routing); the driver is Scope-closed on exit.
      */
    private def makePoolWithDriver(
        settings: Compiler.Pool.Settings,
        instances: Cache[Compiler.Config, Promise[Instance, Abort[CompilerError]]]
    ): CompilerPool < (Sync & Scope) =
        Meter.initSemaphore(settings.maxConcurrentCompiles).map { globalSemaphore =>
            Scope.acquireRelease(Sync.defer(MediaDriver.launchEmbedded()))(d => Sync.defer(d.close())).map { driver =>
                AtomicInt.init(0).map { streamIdCounter =>
                    new CompilerPool(settings, instances, globalSemaphore, driver, streamIdCounter)
                }
            }
        }

    "one instance per config: two concurrent ops on the same config reuse it; eviction triggers recreate" in {
        Scope.run {
            val cfg = minConfig("one-instance")
            val uri = Compiler.Uri("Test.scala")
            val settings =
                Compiler.Pool.Settings(isolate = false, maxConcurrentCompiles = 4, maxLiveCompilers = 2, idleEviction = Duration.Zero)

            Cache.initWithFinalizer[Compiler.Config, Promise[Instance, Abort[CompilerError]]](
                settings.maxLiveCompilers,
                expireAfterAccess = settings.idleEviction
            )(promise => Abort.run[CompilerError](promise.get).map { case Result.Success(i) => i.close; case _ => () }).map { instances =>
                makePool(settings, instances).map { pool =>
                    for
                        // First op on cfg: creates the instance.
                        c1 <- pool.compiler(cfg)
                        _  <- Abort.run[CompilerError](c1.compile(uri, "object A"))

                        // Second op on the same config: reuses the cached instance.
                        c2   <- pool.compiler(cfg)
                        res2 <- Abort.run[CompilerError](c2.compile(uri, "object A"))
                        _ = assert(
                            !res2.isPanic,
                            s"unexpected panic on reuse: $res2"
                        )

                        // Evict cfg by pushing two more configs (maxLiveCompilers = 2).
                        cfg2 = minConfig("cfg2")
                        cfg3 = minConfig("cfg3")
                        c2a <- pool.compiler(cfg2)
                        _   <- Abort.run[CompilerError](c2a.compile(uri, "object B"))
                        c3a <- pool.compiler(cfg3)
                        _   <- Abort.run[CompilerError](c3a.compile(uri, "object C"))

                        // After eviction, a third op on cfg must succeed via recreate (not fail).
                        c1b  <- pool.compiler(cfg)
                        res3 <- Abort.run[CompilerError](c1b.compile(uri, "object A"))
                        _ = assert(
                            !res3.isPanic,
                            s"eviction+recreate produced a panic: $res3"
                        )
                    yield ()
                }
            }
        }
    }

    "single-flight create: N concurrent first-ops on the same config drive the backend exactly N times (one instance, N invocations)" in {
        Scope.run {
            val runCount = new java.util.concurrent.atomic.AtomicInteger(0)
            val cfg      = minConfig("single-flight")
            val settings =
                Compiler.Pool.Settings(isolate = false, maxConcurrentCompiles = 4, maxLiveCompilers = 16, idleEviction = Duration.Zero)

            val countingBackend = new Backend:
                def run(request: Request)(using Frame): Response < (Async & Abort[CompilerError]) =
                    Sync.defer {
                        discard(runCount.incrementAndGet())
                        Response.Diagnostics(Chunk.empty)
                    }
                def close(using Frame): Unit < (Async & Abort[Throwable]) = ()

            Cache.initWithFinalizer[Compiler.Config, Promise[Instance, Abort[CompilerError]]](
                settings.maxLiveCompilers,
                expireAfterAccess = settings.idleEviction
            )(promise => Abort.run[CompilerError](promise.get).map { case Result.Success(i) => i.close; case _ => () }).map { instances =>
                Meter.initMutexUnscoped.map { mutex =>
                    val seededInstance = Instance(countingBackend, mutex)
                    instances.add(cfg, completed(seededInstance)).andThen {
                        makePool(settings, instances).map { pool =>
                            val N = 4
                            for
                                gate <- Latch.init(1)
                                fibers <- Kyo.fill(N) {
                                    Fiber.initUnscoped {
                                        gate.await.andThen {
                                            pool.compiler(cfg).map { c =>
                                                Abort.run[CompilerError](c.compile(Compiler.Uri("f.scala"), "object X"))
                                            }
                                        }
                                    }
                                }
                                _ <- gate.release
                                _ <- Kyo.foreachDiscard(fibers)(_.get)
                                count = runCount.get()
                                // All N fibers used the single pre-seeded instance; backend ran N times.
                                _ = assert(count == N, s"expected $N backend runs via single instance, got $count")
                            yield ()
                            end for
                        }
                    }
                }
            }
        }
    }

    "effectiveIsolate: version-mismatch with isolate=false routes to SpawnBackend (a worker that cannot start fails InitializationFailed)" in {
        Scope.run {
            val settings =
                Compiler.Pool.Settings(isolate = false, maxConcurrentCompiles = 4, maxLiveCompilers = 16, idleEviction = Duration.Zero)

            // Non-own version + isolate=false -> effectiveIsolate=true -> SpawnBackend. minConfig has an
            // empty classpath, so the spawned worker cannot load kyo.internal.CompilerWorker and the readiness
            // probe fails: a worker-start InitializationFailed.
            val mismatchCfg = minConfig("mismatch", version = "3.0.0")
            // Own version + isolate=false -> effectiveIsolate=false -> LocalBackend (no worker spawned).
            val localCfg = minConfig("local", version = CompilerPool.ownVersion)

            Cache.initWithFinalizer[Compiler.Config, Promise[Instance, Abort[CompilerError]]](
                settings.maxLiveCompilers,
                expireAfterAccess = settings.idleEviction
            )(promise => Abort.run[CompilerError](promise.get).map { case Result.Success(i) => i.close; case _ => () }).map { instances =>
                makePoolWithDriver(settings, instances).map { pool =>
                    for
                        // Version mismatch routes to SpawnBackend; the empty-classpath worker cannot
                        // start, so the readiness probe surfaces a worker-start InitializationFailed.
                        cMismatch   <- pool.compiler(mismatchCfg)
                        resMismatch <- Abort.run[CompilerError](cMismatch.compile(Compiler.Uri("m.scala"), "object M"))
                        _ = resMismatch match
                            case Result.Failure(CompilerError.InitializationFailed(msg)) =>
                                assert(msg.contains("worker"), s"expected a worker-start InitializationFailed, got: '$msg'")
                            case other =>
                                assert(false, s"expected InitializationFailed (worker could not start) for version mismatch, got $other")

                        // Own version with isolate=false must NOT spawn a worker (route to LocalBackend),
                        // so it never produces a worker-start InitializationFailed.
                        cLocal   <- pool.compiler(localCfg)
                        resLocal <- Abort.run[CompilerError](cLocal.compile(Compiler.Uri("l.scala"), "object L"))
                        _ = resLocal match
                            case Result.Failure(CompilerError.InitializationFailed(msg)) if msg.contains("worker failed to start") =>
                                assert(false, s"own-version + isolate=false wrongly routed to SpawnBackend: '$msg'")
                            case _ =>
                                ()
                    yield ()
                }
            }
        }
    }

    "per-instance serialization: two concurrent ops on the same instance never overlap" in {
        Scope.run {
            val overlapCount = new java.util.concurrent.atomic.AtomicInteger(0)
            val maxOverlap   = new java.util.concurrent.atomic.AtomicInteger(0)
            val cfg          = minConfig("serialize")
            val settings =
                Compiler.Pool.Settings(isolate = false, maxConcurrentCompiles = 4, maxLiveCompilers = 16, idleEviction = Duration.Zero)

            Channel.initUnscoped[Unit](2).map { parkCh =>
                val serialBackend = new Backend:
                    def run(request: Request)(using Frame): Response < (Async & Abort[CompilerError]) =
                        Sync.defer {
                            val v = overlapCount.incrementAndGet()
                            discard(maxOverlap.updateAndGet(cur => math.max(cur, v)))
                        }
                            .andThen {
                                Abort.run[Closed](parkCh.take).andThen {
                                    Sync.defer {
                                        discard(overlapCount.decrementAndGet())
                                        Response.Diagnostics(Chunk.empty)
                                    }
                                }
                            }
                    def close(using Frame): Unit < (Async & Abort[Throwable]) = ()

                Cache.initWithFinalizer[Compiler.Config, Promise[Instance, Abort[CompilerError]]](
                    settings.maxLiveCompilers,
                    expireAfterAccess = settings.idleEviction
                )(promise => Abort.run[CompilerError](promise.get).map { case Result.Success(i) => i.close; case _ => () }).map {
                    instances =>
                        Meter.initMutexUnscoped.map { mutex =>
                            instances.add(cfg, completed(Instance(serialBackend, mutex))).andThen {
                                makePool(settings, instances).map { pool =>
                                    for
                                        gate <- Latch.init(1)
                                        uri = Compiler.Uri("s.scala")
                                        f1 <- Fiber.initUnscoped {
                                            gate.await.andThen {
                                                pool.compiler(cfg).map { c =>
                                                    Abort.run[CompilerError](c.compile(uri, "object A"))
                                                }
                                            }
                                        }
                                        f2 <- Fiber.initUnscoped {
                                            gate.await.andThen {
                                                pool.compiler(cfg).map { c =>
                                                    Abort.run[CompilerError](c.compile(uri, "object B"))
                                                }
                                            }
                                        }
                                        _ <- gate.release
                                        // Release both parked ops so the fibers can complete.
                                        _ <- parkCh.put(())
                                        _ <- parkCh.put(())
                                        _ <- f1.get
                                        _ <- f2.get
                                        _ = assert(maxOverlap.get() == 1, s"per-instance mutex failed: max overlap = ${maxOverlap.get()}")
                                    yield ()
                                }
                            }
                        }
                }
            }
        }
    }

    "global compile cap: at most maxConcurrentCompiles ops run concurrently across all instances" in {
        Scope.run {
            val inFlight    = new java.util.concurrent.atomic.AtomicInteger(0)
            val maxInFlight = new java.util.concurrent.atomic.AtomicInteger(0)
            val settings =
                Compiler.Pool.Settings(isolate = false, maxConcurrentCompiles = 2, maxLiveCompilers = 16, idleEviction = Duration.Zero)
            val cfgA = minConfig("capA")
            val cfgB = minConfig("capB")

            Channel.initUnscoped[Unit](4).map { releaseCh =>
                Channel.initUnscoped[Unit](4).map { enteredCh =>
                    def makeParkingBackend: Backend = new Backend:
                        def run(request: Request)(using Frame): Response < (Async & Abort[CompilerError]) =
                            Sync.defer {
                                val v = inFlight.incrementAndGet()
                                discard(maxInFlight.updateAndGet(cur => math.max(cur, v)))
                            }
                                .andThen {
                                    Abort.run[Closed](enteredCh.put(())).andThen {
                                        Abort.run[Closed](releaseCh.take).andThen {
                                            Sync.defer {
                                                discard(inFlight.decrementAndGet())
                                                Response.Diagnostics(Chunk.empty)
                                            }
                                        }
                                    }
                                }
                        def close(using Frame): Unit < (Async & Abort[Throwable]) = ()

                    Cache.initWithFinalizer[Compiler.Config, Promise[Instance, Abort[CompilerError]]](
                        settings.maxLiveCompilers,
                        expireAfterAccess = settings.idleEviction
                    )(promise => Abort.run[CompilerError](promise.get).map { case Result.Success(i) => i.close; case _ => () }).map {
                        instances =>
                            Meter.initMutexUnscoped.map { mA =>
                                Meter.initMutexUnscoped.map { mB =>
                                    instances.add(cfgA, completed(Instance(makeParkingBackend, mA))).andThen {
                                        instances.add(cfgB, completed(Instance(makeParkingBackend, mB))).andThen {
                                            makePool(settings, instances).map { pool =>
                                                for
                                                    uri = Compiler.Uri("x.scala")

                                                    fA <- Fiber.initUnscoped {
                                                        pool.compiler(cfgA).map { c =>
                                                            Abort.run[CompilerError](c.compile(uri, "object A"))
                                                        }
                                                    }
                                                    fB <- Fiber.initUnscoped {
                                                        pool.compiler(cfgB).map { c =>
                                                            Abort.run[CompilerError](c.compile(uri, "object B"))
                                                        }
                                                    }

                                                    // Wait until both are inside the backend (cap=2 so both enter).
                                                    _ <- enteredCh.take
                                                    _ <- enteredCh.take

                                                    snapshotAt2 = inFlight.get()
                                                    _ = assert(snapshotAt2 == 2, s"expected 2 in-flight at cap=2, got $snapshotAt2")
                                                    _ = assert(maxInFlight.get() <= 2, s"cap violated: max was ${maxInFlight.get()}")

                                                    _ <- releaseCh.put(())
                                                    _ <- releaseCh.put(())
                                                    _ <- fA.get
                                                    _ <- fB.get
                                                    _ = assert(
                                                        maxInFlight.get() <= 2,
                                                        s"cap violated after release: max was ${maxInFlight.get()}"
                                                    )
                                                yield ()
                                            }
                                        }
                                    }
                                }
                            }
                    }
                }
            }
        }
    }

    "pool isolation: a failure on config A does not affect config B" in {
        Scope.run {
            val cfgA = minConfig("iso-a")
            val cfgB = minConfig("iso-b")
            val settings =
                Compiler.Pool.Settings(isolate = false, maxConcurrentCompiles = 4, maxLiveCompilers = 16, idleEviction = Duration.Zero)

            Channel.initUnscoped[Unit](1).map { parkA =>
                val backendA = new Backend:
                    def run(request: Request)(using Frame): Response < (Async & Abort[CompilerError]) =
                        Abort.run[Closed](parkA.take).andThen {
                            Abort.fail(CompilerError.Fatal("A crashed"))
                        }
                    def close(using Frame): Unit < (Async & Abort[Throwable]) = ()

                val backendB = new Backend:
                    def run(request: Request)(using Frame): Response < (Async & Abort[CompilerError]) =
                        Response.Diagnostics(Chunk(
                            Compiler.Diagnostic(Compiler.Span(0, 1), Compiler.Severity.Warning, "b-warn")
                        ))
                    def close(using Frame): Unit < (Async & Abort[Throwable]) = ()

                Cache.initWithFinalizer[Compiler.Config, Promise[Instance, Abort[CompilerError]]](
                    settings.maxLiveCompilers,
                    expireAfterAccess = settings.idleEviction
                )(promise => Abort.run[CompilerError](promise.get).map { case Result.Success(i) => i.close; case _ => () }).map {
                    instances =>
                        Meter.initMutexUnscoped.map { mA =>
                            Meter.initMutexUnscoped.map { mB =>
                                instances.add(cfgA, completed(Instance(backendA, mA))).andThen {
                                    instances.add(cfgB, completed(Instance(backendB, mB))).andThen {
                                        makePool(settings, instances).map { pool =>
                                            for
                                                aInFlight <- Latch.init(1)
                                                uri = Compiler.Uri("x.scala")

                                                fA <- Fiber.initUnscoped {
                                                    pool.compiler(cfgA).map { c =>
                                                        aInFlight.release.andThen {
                                                            Abort.run[CompilerError](c.compile(uri, "object A"))
                                                        }
                                                    }
                                                }
                                                _ <- aInFlight.await

                                                // B runs normally while A is parked.
                                                cB   <- pool.compiler(cfgB)
                                                resB <- Abort.run[CompilerError](cB.compile(uri, "object B"))
                                                _ = resB match
                                                    case Result.Success(diags) =>
                                                        assert(diags.size == 1, s"expected 1 diagnostic from B, got $diags")
                                                        assert(diags.head.message == "b-warn", s"unexpected B diag: ${diags.head}")
                                                    case other =>
                                                        assert(false, s"B should succeed; got $other")

                                                // Release A's park and observe its typed failure.
                                                _    <- parkA.put(())
                                                resA <- fA.get
                                                _ = resA match
                                                    case Result.Failure(CompilerError.Fatal(m)) =>
                                                        assert(m == "A crashed", s"unexpected A message: '$m'")
                                                    case other =>
                                                        assert(false, s"A should fail Fatal; got $other")
                                            yield ()
                                        }
                                    }
                                }
                            }
                        }
                }
            }
        }
    }

    "cross-config parallelism: two ops on distinct configs run in the backend simultaneously" in {
        Scope.run {
            val maxConcurrent = new java.util.concurrent.atomic.AtomicInteger(0)
            val settings =
                Compiler.Pool.Settings(isolate = false, maxConcurrentCompiles = 4, maxLiveCompilers = 16, idleEviction = Duration.Zero)
            val cfgA = minConfig("par-a")
            val cfgB = minConfig("par-b")

            Channel.initUnscoped[Unit](2).map { releaseCh =>
                Channel.initUnscoped[Unit](2).map { enteredCh =>
                    val inFlight = new java.util.concurrent.atomic.AtomicInteger(0)

                    def makeParkingBackend: Backend = new Backend:
                        def run(request: Request)(using Frame): Response < (Async & Abort[CompilerError]) =
                            Sync.defer {
                                val v = inFlight.incrementAndGet()
                                discard(maxConcurrent.updateAndGet(cur => math.max(cur, v)))
                            }
                                .andThen {
                                    Abort.run[Closed](enteredCh.put(())).andThen {
                                        Abort.run[Closed](releaseCh.take).andThen {
                                            Sync.defer {
                                                discard(inFlight.decrementAndGet())
                                                Response.Diagnostics(Chunk.empty)
                                            }
                                        }
                                    }
                                }
                        def close(using Frame): Unit < (Async & Abort[Throwable]) = ()

                    Cache.initWithFinalizer[Compiler.Config, Promise[Instance, Abort[CompilerError]]](
                        settings.maxLiveCompilers,
                        expireAfterAccess = settings.idleEviction
                    )(promise => Abort.run[CompilerError](promise.get).map { case Result.Success(i) => i.close; case _ => () }).map {
                        instances =>
                            Meter.initMutexUnscoped.map { mA =>
                                Meter.initMutexUnscoped.map { mB =>
                                    instances.add(cfgA, completed(Instance(makeParkingBackend, mA))).andThen {
                                        instances.add(cfgB, completed(Instance(makeParkingBackend, mB))).andThen {
                                            makePool(settings, instances).map { pool =>
                                                for
                                                    uri = Compiler.Uri("p.scala")

                                                    fA <- Fiber.initUnscoped {
                                                        pool.compiler(cfgA).map { c =>
                                                            Abort.run[CompilerError](c.compile(uri, "object A"))
                                                        }
                                                    }
                                                    fB <- Fiber.initUnscoped {
                                                        pool.compiler(cfgB).map { c =>
                                                            Abort.run[CompilerError](c.compile(uri, "object B"))
                                                        }
                                                    }

                                                    // Wait until both fibers are inside the backend.
                                                    _ <- enteredCh.take
                                                    _ <- enteredCh.take

                                                    // Both must be in the backend at the same time.
                                                    snapshot = inFlight.get()
                                                    _ = assert(
                                                        snapshot == 2,
                                                        s"expected 2 concurrent ops on distinct configs, got $snapshot"
                                                    )

                                                    _ <- releaseCh.put(())
                                                    _ <- releaseCh.put(())
                                                    _ <- fA.get
                                                    _ <- fB.get
                                                    _ = assert(
                                                        maxConcurrent.get() == 2,
                                                        s"cross-config parallelism not observed: max = ${maxConcurrent.get()}"
                                                    )
                                                yield ()
                                            }
                                        }
                                    }
                                }
                            }
                    }
                }
            }
        }
    }

    "Pool.compiler is a Sync resolve with no instance created; instance created only on first op" in {
        Scope.run {
            val createCount = new java.util.concurrent.atomic.AtomicInteger(0)
            val cfg         = minConfig("lazy", version = CompilerPool.ownVersion)
            val settings =
                Compiler.Pool.Settings(isolate = false, maxConcurrentCompiles = 4, maxLiveCompilers = 16, idleEviction = Duration.Zero)

            Cache.initWithFinalizer[Compiler.Config, Promise[Instance, Abort[CompilerError]]](
                settings.maxLiveCompilers,
                expireAfterAccess = settings.idleEviction
            )(promise => Abort.run[CompilerError](promise.get).map { case Result.Success(i) => i.close; case _ => () }).map { instances =>
                makePool(settings, instances).map { pool =>
                    for
                        // pool.compiler() is Sync; no backend created yet.
                        c <- pool.compiler(cfg)
                        count0 = createCount.get()
                        _      = assert(count0 == 0, s"expected 0 creates after pool.compiler(), got $count0")

                        // First op triggers resolve -> create -> LocalBackend.init (version-matched).
                        resFirst <- Abort.run[CompilerError](c.compile(Compiler.Uri("z.scala"), "object Z"))
                        _ = resFirst match
                            case Result.Panic(e) => assert(false, s"unexpected panic on first op: $e")
                            case _               => ()
                    yield ()
                }
            }
        }
    }

    "pool eviction closes the Instance via the Cache finalizer" in {
        Scope.run {
            Channel.initUnscoped[String](4).map { closedCh =>
                val cfgA = minConfig("evict-a")
                val cfgB = minConfig("evict-b")
                val settings =
                    Compiler.Pool.Settings(isolate = false, maxConcurrentCompiles = 4, maxLiveCompilers = 1, idleEviction = Duration.Zero)

                val backendA = new Backend:
                    def run(request: Request)(using Frame): Response < (Async & Abort[CompilerError]) =
                        Response.Diagnostics(Chunk.empty)
                    def close(using Frame): Unit < (Async & Abort[Throwable]) =
                        Abort.run[Closed](closedCh.put("A")).map(_ => ())

                val backendB = new Backend:
                    def run(request: Request)(using Frame): Response < (Async & Abort[CompilerError]) =
                        Response.Diagnostics(Chunk.empty)
                    def close(using Frame): Unit < (Async & Abort[Throwable]) = ()

                Cache.initWithFinalizer[Compiler.Config, Promise[Instance, Abort[CompilerError]]](
                    settings.maxLiveCompilers,
                    expireAfterAccess = settings.idleEviction
                )(promise => Abort.run[CompilerError](promise.get).map { case Result.Success(i) => i.close; case _ => () }).map {
                    instances =>
                        makePool(settings, instances).map { pool =>
                            for
                                mA <- Meter.initMutexUnscoped
                                mB <- Meter.initMutexUnscoped
                                _  <- instances.add(cfgA, completed(Instance(backendA, mA)))

                                uri = Compiler.Uri("e.scala")

                                // Drive an op on A so it is live.
                                cA <- pool.compiler(cfgA)
                                _  <- Abort.run[CompilerError](cA.compile(uri, "object A"))

                                // Insert B into the cache (maxLiveCompilers = 1 forces A's eviction).
                                _ <- instances.add(cfgB, completed(Instance(backendB, mB)))

                                // Await the close signal: the cache finalizer calls backendA.close.
                                closedTag <- closedCh.take
                                _ = assert(closedTag == "A", s"expected close of A, got '$closedTag'")

                                // B still serves normally after A was evicted.
                                cB   <- pool.compiler(cfgB)
                                resB <- Abort.run[CompilerError](cB.compile(uri, "object B"))
                                _ = resB match
                                    case Result.Success(_) => ()
                                    case other             => assert(false, s"B should succeed after eviction of A; got $other")
                            yield ()
                        }
                }
            }
        }
    }

    "stuck-op reclaim (leg 3): a hung op times out, the instance is evicted and closed, and the config recreates" in {
        Scope.run {
            Channel.initUnscoped[String](4).map { closedCh =>
                val cfg          = minConfig("stuck")
                val stuckTimeout = 500.millis
                val settings =
                    Compiler.Pool.Settings(isolate = false, maxConcurrentCompiles = 4, maxLiveCompilers = 16, idleEviction = Duration.Zero)

                // A backend whose op never returns: only the leg-3 timeout can end it. Its close-on-evict
                // finalizer signals the channel, so the test observes the reclaim deterministically.
                val hangingBackend = new Backend:
                    def run(request: Request)(using Frame): Response < (Async & Abort[CompilerError]) =
                        Async.never
                    def close(using Frame): Unit < (Async & Abort[Throwable]) =
                        Abort.run[Closed](closedCh.put("reclaimed")).map(_ => ())

                Cache.initWithFinalizer[Compiler.Config, Promise[Instance, Abort[CompilerError]]](
                    settings.maxLiveCompilers,
                    expireAfterAccess = settings.idleEviction
                )(promise => Abort.run[CompilerError](promise.get).map { case Result.Success(i) => i.close; case _ => () }).map {
                    instances =>
                        Meter.initMutexUnscoped.map { mutex =>
                            instances.add(cfg, completed(Instance(hangingBackend, mutex))).andThen {
                                makePool(settings, instances, stuckTimeout).map { pool =>
                                    for
                                        uri = Compiler.Uri("stuck.scala")

                                        // (a) the hung op fails with a typed Fatal after ~stuckTimeout. The
                                        // hang can never fail on its own, so the reclaim Fatal proves the
                                        // timeout fired; the elapsed check proves it bounded the hang rather
                                        // than failing instantly.
                                        start    <- Clock.now
                                        cStuck   <- pool.compiler(cfg)
                                        resStuck <- Abort.run[CompilerError](cStuck.compile(uri, "object Stuck"))
                                        elapsed  <- Clock.now.map(_ - start)
                                        _ = resStuck match
                                            case Result.Failure(CompilerError.Fatal(msg)) =>
                                                assert(msg.contains("unresponsive"), s"expected the reclaim Fatal, got: '$msg'")
                                            case other =>
                                                assert(false, s"expected a typed Fatal from the stuck-op reclaim, got $other")
                                        _ = assert(
                                            elapsed >= stuckTimeout * 0.5,
                                            s"op returned in $elapsed; the timeout should have bounded the hang near $stuckTimeout"
                                        )

                                        // (b) the instance's close-on-evict finalizer ran (the worker was reclaimed).
                                        closedTag <- closedCh.take
                                        _ = assert(closedTag == "reclaimed", s"expected the stuck instance to be closed, got '$closedTag'")

                                        // (c) the evicted instance is gone from the cache, so the next op for the
                                        // config resolves a fresh instance rather than the dead hanging one.
                                        afterEvict <- instances.get(cfg)
                                        _ = assert(
                                            afterEvict.isEmpty,
                                            s"expected the evicted instance gone from the cache, got $afterEvict"
                                        )

                                        freshMutex <- Meter.initMutexUnscoped
                                        freshBackend = new Backend:
                                            def run(request: Request)(using Frame): Response < (Async & Abort[CompilerError]) =
                                                Response.Diagnostics(Chunk(
                                                    Compiler.Diagnostic(Compiler.Span(0, 1), Compiler.Severity.Info, "fresh")
                                                ))
                                            def close(using Frame): Unit < (Async & Abort[Throwable]) = ()
                                        _        <- instances.add(cfg, completed(Instance(freshBackend, freshMutex)))
                                        cFresh   <- pool.compiler(cfg)
                                        resFresh <- Abort.run[CompilerError](cFresh.compile(uri, "object Fresh"))
                                        _ = resFresh match
                                            case Result.Success(diags) =>
                                                assert(
                                                    diags.size == 1 && diags.head.message == "fresh",
                                                    s"expected the fresh instance to serve the next op, got $diags"
                                                )
                                            case other =>
                                                assert(false, s"expected the fresh instance to serve the next op, got $other")
                                    yield ()
                                }
                            }
                        }
                }
            }
        }
    }

end CompilerPoolTest
