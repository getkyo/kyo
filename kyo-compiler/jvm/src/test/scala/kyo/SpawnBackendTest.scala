package kyo

import io.aeron.driver.MediaDriver
import kyo.internal.*

class SpawnBackendTest extends kyo.test.Test[Any]:

    override def timeout = 300.seconds

    /** Every entry on the test JVM's classpath, as `Path`s. A spawned worker's `-cp` is built from the
      * config classpath, so it must carry the kyo.internal.CompilerWorker class, kyo-aeron, and the presentation
      * compiler; the full test classpath supplies all three.
      */
    private def fullClasspath: Chunk[Path] =
        Chunk.from(
            java.lang.System.getProperty("java.class.path", "")
                .split(Path.pathSeparator.charAt(0))
                .iterator
                .filter(_.nonEmpty)
                .map(Path(_))
                .toSeq
        )

    /** A real forked-worker Config: the full test classpath as both the worker's runtime classpath and
      * the pc's target classpath, the own scala version, and `isolate = true`. `scalacOptions`
      * distinguishes otherwise-equal configs (a distinct config routes to a distinct worker session).
      */
    private def spawnConfig(scalacOptions: Chunk[String] = Chunk.empty): Compiler.Config =
        Compiler.Config(
            toolchain = Compiler.Toolchain(CompilerPool.ownVersion, fullClasspath),
            classpath = fullClasspath,
            scalacOptions = scalacOptions,
            sourceRoots = Chunk.empty,
            isolate = Present(true)
        )

    /** The in-process counterpart of [[spawnConfig]] (`isolate = false`), version-matched so it routes
      * to the Local backend.
      */
    private def localConfig(scalacOptions: Chunk[String] = Chunk.empty): Compiler.Config =
        spawnConfig(scalacOptions).copy(isolate = Present(false))

    /** The current JVM's own `java` launcher, used to spawn a throwaway child process when a test needs
      * a real `Process` handle without a full worker.
      */
    private val javaBin =
        Path(java.lang.System.getProperty("java.home"), "bin", "java").toString

    /** Runs `f` against a fresh embedded MediaDriver, closed on scope exit. */
    private def withDriver[A](f: MediaDriver => A < (Async & Abort[CompilerException] & Scope))(using
        Frame
    ): A < (Async & Abort[CompilerException]) =
        Scope.run(Scope.acquireRelease(Sync.defer(CompilerPool.launchDriver()))(d => Sync.defer(d.close())).map(f))

    /** Scope-binds a spawned backend so its close runs on every exit path: an assertion failure
      * mid-test must never leak the worker process or its aeron client (a leaked client's conductor
      * thread is non-daemon and keeps the forked test JVM alive past the suite). Close is safe to run
      * twice, so tests that close mid-body as part of their scenario still hold.
      */
    private def scopedSpawn(config: Compiler.Config, driver: MediaDriver, streamIdBase: Int)(using
        Frame
    ): SpawnBackend < (Async & Abort[CompilerException] & Scope) =
        Scope.acquireRelease(SpawnBackend.init(config, driver, streamIdBase))(b => Abort.run[Throwable](b.close).unit)

    /** Scope-binds an in-process backend so its pc shuts down on every exit path. */
    private def scopedLocal(config: Compiler.Config)(using
        Frame
    ): Backend < (Async & Abort[CompilerException] & Scope) =
        Scope.acquireRelease(LocalBackend.init(config))(b => Abort.run[Throwable](b.close).unit)

    "a fixed buffer yields equal results on Local and Spawn (parity)" in {
        withDriver { driver =>
            for
                local <- scopedLocal(localConfig())
                spawn <- scopedSpawn(spawnConfig(), driver, 0)
                uri       = Compiler.Uri("Parity.scala")
                cleanText = "object Main { val x: Int = 1 }"
                errorText = "object Main { val x: Int = \"not an int\" }"

                localClean <- local.run(Request.Compile(uri, cleanText))
                spawnClean <- spawn.run(Request.Compile(uri, cleanText))
                _ = assert(localClean == spawnClean, s"clean compile parity: local=$localClean spawn=$spawnClean")

                localError <- local.run(Request.Compile(uri, errorText))
                spawnError <- spawn.run(Request.Compile(uri, errorText))
                _ = assert(localError == spawnError, s"error compile parity: local=$localError spawn=$spawnError")
                _ = localError match
                    case Response.Diagnostics(diags) => assert(diags.nonEmpty, s"error buffer should yield diagnostics, got $diags")
                    case other                       => assert(false, s"expected Diagnostics, got $other")

                completionText = "object Main { val r = \"\".  }"
                offset         = completionText.indexOf("\".") + 2
                localComp <- local.run(Request.Completions(uri, completionText, offset))
                spawnComp <- spawn.run(Request.Completions(uri, completionText, offset))
                _ = (localComp, spawnComp) match
                    case (Response.Completions(a), Response.Completions(b)) =>
                        assert(
                            a.map(_.label).toSeq.sorted == b.map(_.label).toSeq.sorted,
                            s"completions parity: local=${a.size} spawn=${b.size}"
                        )
                        assert(a.nonEmpty, s"expected non-empty completions, got $a")
                    case other => assert(false, s"expected two Completions, got $other")

                _ <- Abort.run[Throwable](local.close)
                _ <- Abort.run[Throwable](spawn.close)
            yield ()
        }
    }

    "interrupting a Spawn op releases its pending entry and the backend stays usable for a superseding op" in {
        Scope.run {
            Abort.run[Closed] {
                for
                    sentCh <- Channel.initUnscoped[Int](16)
                    respCh <- Channel.initUnscoped[Envelope](16)
                    exchange <- Exchange.initUnscoped[Request, Response, Envelope, Nothing, TransportError](
                        encode = (id, req) => Envelope.Req(id, req),
                        send = (frame: Envelope) =>
                            frame match
                                case Envelope.Req(id, _) => Abort.run[Closed](sentCh.put(id)).unit
                                case _                   => (),
                        receive = Stream(respCh.streamUntilClosed().emit),
                        decode = (frame: Envelope) =>
                            frame match
                                case Envelope.Resp(id, response) => Exchange.Message.Response(id, response)
                                case _                           => Exchange.Message.Skip
                    )
                    // A throwaway child process supplies a real Process handle; this leaf drives `run`
                    // through a controlled in-memory Exchange (not a real aeron session), so the aeron
                    // client is unused (passed null) and teardown is exchange.close plus a direct
                    // process kill, never SpawnBackend.close.
                    proc <- Abort.run[CommandException](Command(javaBin, "-version").spawnUnscoped).map {
                        case Result.Success(p) => p
                        case Result.Failure(e) => Abort.panic(e)
                        case Result.Panic(t)   => Abort.panic(t)
                    }
                    backend = new SpawnBackend(proc, null, exchange)
                    uri     = Compiler.Uri("Interrupt.scala")

                    // op1 registers (id 0) and parks: no response for id 0 is ever fed.
                    fiber1  <- Fiber.initUnscoped(Abort.run[CompilerException](backend.run(Request.Compile(uri, "object A"))))
                    sentId1 <- sentCh.take
                    _ = assert(sentId1 == 0, s"first op id should be 0, got $sentId1")
                    _    <- fiber1.interrupt
                    res1 <- Abort.run[Throwable](fiber1.get)
                    _ = assert(res1.isPanic, s"interrupted op1 must surface as an interrupt, not completion: $res1")

                    // op2 (superseding) registers (id 1); feeding its response proves the exchange is
                    // still usable after the interrupt cleanup.
                    fiber2  <- Fiber.initUnscoped(Abort.run[CompilerException](backend.run(Request.Completions(uri, "object B {}", 0))))
                    sentId2 <- sentCh.take
                    _ = assert(sentId2 == 1, s"superseding op id should be 1, got $sentId2")
                    _    <- respCh.put(Envelope.Resp(sentId2, Response.Completions(Chunk.empty)))
                    res2 <- fiber2.get
                    _ = res2 match
                        case Result.Success(Response.Completions(items)) =>
                            assert(items.isEmpty, s"expected the fed empty completions, got $items")
                        case other => assert(false, s"superseding op2 must succeed with the fed response, got $other")

                    _ <- exchange.close
                    _ <- backend.process.destroyForcibly
                yield ()
            }.map {
                case Result.Success(_)   => ()
                case Result.Failure(c)   => Abort.panic(new RuntimeException(s"unexpected channel close: $c"))
                case Result.Panic(error) => Abort.panic(error)
            }
        }
    }

    "no thread leak after a kill: close kills the worker and every later op fails with a typed Fatal" in {
        withDriver { driver =>
            for
                backend <- scopedSpawn(spawnConfig(), driver, 0)
                uri = Compiler.Uri("Kill.scala")

                // A live round-trip proves the worker is up and serving before the kill.
                live <- backend.run(Request.Compile(uri, "object Main { }"))
                _ = live match
                    case Response.Diagnostics(diags) => assert(diags.isEmpty, s"clean buffer should be diagnostic-free, got $diags")
                    case other                       => assert(false, s"expected Diagnostics from a live worker, got $other")

                _    <- Abort.run[Throwable](backend.close)
                code <- backend.process.waitFor
                _ = assert(!code.isSuccess, s"a force-killed worker must not exit successfully, got $code")
                alive <- backend.process.isAlive
                _ = assert(!alive, "worker process must be dead after close")

                // Every op after the kill fails with a typed Fatal and none hangs (the closed exchange
                // fails them immediately rather than parking).
                r1 <- Abort.run[CompilerException](backend.run(Request.Compile(uri, "object A")))
                r2 <- Abort.run[CompilerException](backend.run(Request.Completions(uri, "object B {}", 0)))
                r3 <- Abort.run[CompilerException](backend.run(Request.DidClose(uri)))
                _ = List(r1, r2, r3).foreach { r =>
                    val ok = r match
                        case Result.Failure(_: CompilerTransportException) => true
                        case _                                             => false
                    assert(ok, s"every op after a kill must fail with CompilerTransportException, got $r")
                }
            yield ()
        }
    }

    "a worker-comms failure surfaces didClose as a typed CompilerTransportException; a live worker yields Closed" in {
        withDriver { driver =>
            for
                backend <- scopedSpawn(spawnConfig(), driver, 0)
                uri = Compiler.Uri("DidClose.scala")

                liveResult <- Abort.run[CompilerException](backend.run(Request.DidClose(uri)))
                _ = liveResult match
                    case Result.Success(Response.Closed) => succeed("live didClose returned Response.Closed")
                    case other => assert(false, s"expected Success(Response.Closed) from a live worker, got $other")

                _ <- Abort.run[Throwable](backend.close)

                brokenResult <- Abort.run[CompilerException](backend.run(Request.DidClose(uri)))
                _ = brokenResult match
                    case Result.Failure(_: CompilerTransportException) =>
                        succeed("broken didClose surfaced as a typed CompilerTransportException")
                    case other => assert(false, s"expected a CompilerTransportException from a broken worker, got $other")
            yield ()
        }
    }

    "one shared MediaDriver per pool: two Spawn workers both reach it; the driver closes on scope close" in {
        val launched = new java.util.concurrent.atomic.AtomicInteger(0)
        val closed   = new java.util.concurrent.atomic.AtomicInteger(0)
        Scope.run {
            Scope.acquireRelease(
                Sync.defer { discard(launched.incrementAndGet()); CompilerPool.launchDriver() }
            )(d => Sync.defer { discard(closed.incrementAndGet()); d.close() }).map { driver =>
                for
                    // Two distinct configs force two distinct worker sessions over the one shared driver;
                    // distinct stream-id bases (0, 1) keep their req/resp streams disjoint.
                    b1 <- scopedSpawn(spawnConfig(Chunk.empty), driver, 0)
                    b2 <- scopedSpawn(spawnConfig(Chunk("-deprecation")), driver, 1)
                    uri = Compiler.Uri("Driver.scala")

                    // Distinguishable buffers: a cross-talk between the two sessions would swap these.
                    r1 <- b1.run(Request.Compile(uri, "object Main { val x: Int = \"not an int\" }"))
                    r2 <- b2.run(Request.Compile(uri, "object Main { val y: Int = 1 }"))
                    _ = r1 match
                        case Response.Diagnostics(diags) =>
                            assert(diags.nonEmpty, s"worker 1 (error buffer) must yield diagnostics, got $diags")
                        case other => assert(false, s"expected Diagnostics from worker 1, got $other")
                    _ = r2 match
                        case Response.Diagnostics(diags) => assert(diags.isEmpty, s"worker 2 (clean buffer) must yield none, got $diags")
                        case other                       => assert(false, s"expected Diagnostics from worker 2, got $other")

                    _ = assert(launched.get() == 1, s"exactly one MediaDriver must be launched, got ${launched.get()}")

                    _ <- Abort.run[Throwable](b1.close)
                    _ <- Abort.run[Throwable](b2.close)
                yield ()
            }
        }.andThen {
            Sync.defer(assert(closed.get() == 1, s"the shared driver must be closed exactly once on scope close, got ${closed.get()}"))
        }
    }

    "interrupting init during the readiness probe force-kills the partial worker (no orphaned process)" in {
        withDriver { driver =>
            for
                // Capture THIS init's worker process via the onSpawn seam (fired once the interrupt-safe
                // kill is armed, just before the readiness probe), so the assertion targets exactly the
                // worker this test spawns rather than the noisy global worker count.
                spawned <- Sync.defer(new java.util.concurrent.atomic.AtomicReference[Maybe[Process]](Absent))
                fiber <- Fiber.initUnscoped(
                    Abort.run[CompilerException](SpawnBackend.init(spawnConfig(), driver, 7, p => spawned.set(Present(p))))
                )
                // The worker JVM has spawned but cannot answer the probe for seconds, so once the process
                // is captured the init fiber is parked in the readiness probe and has NOT completed.
                // Interrupting now lands squarely mid-probe (no fixed sleep that a fast boot could outrun).
                captured <- pollUntil(500, 10.millis)(Sync.defer(spawned.get().isDefined))
                _       = assert(captured, "the worker process must spawn before the readiness probe completes")
                process = spawned.get().get
                stillBooting <- process.isAlive
                _ = assert(stillBooting, "the worker must still be booting when the interrupt is delivered")
                _ <- fiber.interrupt
                _ <- Abort.run[Throwable](fiber.get)
                // The interrupt-safe finalizer force-kills the partial worker; poll (bounded) for the
                // death rather than asserting once and racing destroyForcibly.
                dead <- pollUntil(500, 10.millis)(process.isAlive.map(alive => !alive))
                _ = assert(dead, "interrupting init must force-kill the partial worker, not leave an orphaned JVM")
            yield ()
        }
    }

    /** Re-checks the effectful `cond` every `step` until it holds or `attempts` are exhausted,
      * suspending between checks so the init fiber under test makes progress. Returns the final value.
      */
    private def pollUntil(attempts: Int, step: Duration)(cond: => Boolean < Sync)(using Frame): Boolean < Async =
        cond.map { c =>
            if c || attempts <= 0 then c
            else Async.sleep(step).andThen(pollUntil(attempts - 1, step)(cond))
        }

end SpawnBackendTest
