package kyo.compat

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kyo.compat.*
import scala.concurrent.Promise
import scala.concurrent.duration.*
import scala.util.Failure
import scala.util.Success

class FiberTest extends CompatTest:

    "CFiber.init(c) forks and CFiber.get awaits the value" in run {
        val body: CIO[Int] = CIO.defer { 42 }
        val c              = CFiber.init[Int](body).flatMap(fib => fib.get)
        c.map(v => assert(v == 42))
    }

    "CFiber.init runs concurrently with caller" in run {
        val ctr = new AtomicInteger(0)
        val body: CIO[Int] = CIO.defer {
            val _ = ctr.incrementAndGet()
            7
        }
        val c = CFiber.init[Int](body).flatMap(fib => fib.get)
        c.map(v => assert(v == 7 && ctr.get == 1))
    }

    "CFiber.get re-fails on typed error (round-trip)" in run {
        val src: CIO[Int] = CIO.fail(TestError("oops"))
        val c =
            CFiber.init(src).flatMap { fib =>
                fib.get.liftToTry
            }
        c.map {
            case Failure(e: TestError) if e.msg == "oops" => succeed
            case other                                    => fail(s"expected Failure(TestError(\"oops\")), got: $other")
        }
    }

    "Multiple CFiber.get calls return same value" in run {
        val c =
            CFiber.init[Int](CIO.defer { 42 }).flatMap { fib =>
                fib.get.flatMap { a =>
                    fib.get.flatMap { b =>
                        fib.get.flatMap { d =>
                            CIO.defer((a, b, d))
                        }
                    }
                }
            }
        c.map { case (a, b, d) =>
            assert(a == 42 && b == 42 && d == 42)
        }
    }

    // ----- CFiber.onComplete: 3 scenarios -----

    "onComplete fires with Success(value) when the fiber succeeds" in run {
        val ctr = new AtomicInteger(0)
        val c =
            CFiber.init[Int](CIO.defer { 42 }).flatMap { fib =>
                CPromise.init[Unit].flatMap { fired =>
                    fib.onComplete {
                        case Success(_) =>
                            CIO.defer { val _ = ctr.incrementAndGet() }.flatMap(_ => fired.succeed(()).unit)
                        case Failure(_) => fired.succeed(()).unit
                    }.flatMap { _ =>
                        fib.get.flatMap { v =>
                            fired.get.flatMap { _ =>
                                CIO.defer((v, ctr.get))
                            }
                        }
                    }
                }
            }
        c.map { case (v, count) =>
            assert(v == 42 && count == 1, s"v=$v count=$count")
        }
    }

    "onComplete fires with Failure(error) when the fiber fails" in run {
        val ctr = new AtomicInteger(0)
        val src: CIO[Int] =
            CIO.defer { throw new RuntimeException("boom") }
        val c =
            CFiber.init(src).flatMap { fib =>
                CPromise.init[Unit].flatMap { fired =>
                    fib.onComplete {
                        case Failure(_) =>
                            CIO.defer { val _ = ctr.incrementAndGet() }.flatMap(_ => fired.succeed(()).unit)
                        case Success(_) => fired.succeed(()).unit
                    }.flatMap { _ =>
                        fib.get.liftToTry.flatMap { _ =>
                            fired.get.flatMap { _ =>
                                CIO.defer(ctr.get)
                            }
                        }
                    }
                }
            }
        c.map(count => assert(count == 1, s"count=$count"))
    }

    "onComplete Failure branch receives the same throwable on failure" in run {
        val observed = new AtomicReference[Throwable](null)
        val t        = new RuntimeException("x")
        val c =
            CFiber.init(CIO.fail(t)).flatMap { fib =>
                fib.onComplete {
                    case Failure(e) => CIO.defer { observed.set(e) }
                    case Success(_) => CIO.unit
                }.flatMap { _ =>
                    CIO.sleep(100.millis).flatMap { _ =>
                        CIO.defer(observed.get)
                    }
                }
            }
        c.map { obs =>
            assert(obs ne null, "callback was not invoked")
            assert(obs.getMessage == "x", s"wrong throwable: $obs")
        }
    }

    "onComplete fires when the fiber dies with a java.lang.Error" in run {
        // java.lang.Error IS a Throwable, so the callback MUST fire.
        val ctr = new AtomicInteger(0)
        val body: CIO[Int] =
            CIO.defer { throw new java.lang.Error("panic") }
        val c =
            CFiber.init(body).flatMap { fib =>
                fib.onComplete { _ =>
                    CIO.defer { val _ = ctr.incrementAndGet() }
                }.flatMap { _ =>
                    CIO.sleep(200.millis).flatMap { _ =>
                        CIO.defer(ctr.get)
                    }
                }
            }
        c.map(count => assert(count == 1, s"expected 1 (any Throwable fires callback), got $count"))
    }

    "onComplete callback failure does not crash the surrounding fiber" in run {
        // When a user-provided onComplete callback fails, the surrounding
        // chain MUST keep running. Each backend reports the unhandled
        // failure through its native runtime mechanism (ZIO's logger,
        // CE's IORuntime error reporter, etc.) — the surface only
        // promises that the caller's program isn't taken down with it.
        val ctr = new java.util.concurrent.atomic.AtomicInteger(0)
        val c =
            CFiber.init(CIO.defer { 1 }).flatMap { fib =>
                fib.onComplete { _ =>
                    CIO.defer { throw new RuntimeException("boom-cb") }
                }.flatMap { _ =>
                    CIO.sleep(300.millis).flatMap { _ =>
                        CIO.defer { val _ = ctr.incrementAndGet(); ctr.get }
                    }
                }
            }
        c.map(observed => assert(observed == 1, s"surrounding chain stopped: $observed"))
    }
    "CFiber.onComplete registered AFTER fiber completes fires immediately" in run {
        // Wait for the fiber to finish via fiber.get, then register onComplete
        // and confirm the callback fires by observing a CPromise signal.
        val c = CFiber.init(CIO.value(42)).flatMap { fiber =>
            fiber.get.flatMap { _ =>
                CPromise.init[scala.util.Try[Int]].flatMap { fired =>
                    fiber.onComplete { t =>
                        fired.succeed(t).unit
                    }.flatMap { _ =>
                        fired.get
                    }
                }
            }
        }
        c.map { result =>
            result match
                case Success(v) => assert(v == 42, s"expected Success(42), got $result")
                case other      => fail(s"expected Success(42), got $other")
        }
    }

    "CFiber.get on already-completed fiber returns immediately" in run {
        // Spawn CFiber.init(CIO.value(42)). Await first .get to ensure the
        // fiber has completed; then measure the latency of a second .get.
        val c = CFiber.init(CIO.value(42)).flatMap { fiber =>
            fiber.get.flatMap { _ =>
                CIO.defer(java.lang.System.nanoTime()).flatMap { before =>
                    fiber.get.flatMap { v =>
                        CIO.defer {
                            val after   = java.lang.System.nanoTime()
                            val deltaMs = (after - before) / 1_000_000L
                            (v, deltaMs)
                        }
                    }
                }
            }
        }
        c.map { case (v, deltaMs) =>
            assert(v == 42, s"expected 42, got $v")
            assert(deltaMs < 100L, s"fiber.get on already-completed fiber took ${deltaMs}ms, expected < 100ms")
        }
    }
    "CFiber.init(...).get returns the value" in run {
        // A fiber wrapping a value must resolve via get to that value.
        val c = CFiber.init(CIO.value(42)).flatMap { fiber => fiber.get }
        c.map(v => assert(v == 42, s"expected 42, got $v"))
    }

    "CFiber onComplete fires with the fiber's outcome" in run {
        // Register onComplete(cb) on a fiber that succeeds with 7.
        // Assert cb fires with Success(7).
        val stored = new java.util.concurrent.atomic.AtomicReference[scala.util.Try[Int]](null)
        val c = CFiber.init(CIO.value(7)).flatMap { fiber =>
            fiber.onComplete { t =>
                CIO.defer { stored.set(t) }
            }.flatMap { _ =>
                CIO.sleep(100.millis).map { _ => stored.get() }
            }
        }
        c.map { result =>
            assert(result != null, "onComplete callback was not invoked")
            result match
                case scala.util.Success(v) => assert(v == 7, s"expected Success(7), got $result")
                case other                 => fail(s"expected Success(7), got $other")
        }
    }

    "CFiber.init exposes the running fiber before its body completes" in run {
        // Verify that CFiber.init has registered the fork BEFORE the body
        // proceeds, by gating the body on a CLatch. The fiber handle resolves
        // immediately; the body stays suspended until the gate is released.
        val ctr = new AtomicInteger(0)
        CLatch.init(1).flatMap { gate =>
            val body = gate.await.flatMap(_ => CIO.defer { ctr.incrementAndGet() })
            CFiber.init(body).flatMap { fib =>
                // Fork registered; body suspended on the gate, so ctr is still 0.
                assert(ctr.get == 0)
                gate.release.flatMap { _ =>
                    fib.get.map { result =>
                        assert(result == 1 && ctr.get == 1, s"result=$result ctr=${ctr.get}")
                    }
                }
            }
        }
    }

    "CFiber.onComplete is deferred: building the CIO does not register the callback" in run {
        // Building the onComplete CIO does NOT register the callback; only
        // running it does. Bridge an external completion signal via a Promise
        // wired through CIO.async, so the fiber's body suspends until the
        // promise is completed externally.
        val ctr  = new AtomicInteger(0)
        val gate = Promise[Int]()
        val body = CIO.async[Int] { cb =>
            gate.future.onComplete(t => cb(t))(scala.concurrent.ExecutionContext.parasitic)
        }
        val c = CFiber.init(body).flatMap { fib =>
            val onCompleteCIO = fib.onComplete(_ => CIO.defer { val _ = ctr.incrementAndGet() })
            // Counter must be 0 before running the onComplete CIO.
            assert(ctr.get == 0, s"counter must be 0 after building onCompleteCIO, got ${ctr.get}")
            onCompleteCIO.flatMap { _ =>
                CIO.defer { gate.success(99) }.flatMap { _ =>
                    CIO.sleep(100.millis).map { _ =>
                        assert(ctr.get == 1, s"counter must be 1 after fiber completes, got ${ctr.get}")
                    }
                }
            }
        }
        c
    }

end FiberTest
