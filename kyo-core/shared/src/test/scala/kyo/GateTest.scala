package kyo

class GateTest extends kyo.test.Test[Any]:

    "init" - {
        "initWith" in {
            Gate.initWith(2) { gate =>
                for
                    f <- Fiber.initUnscoped(gate.pass)
                    _ <- gate.pass
                    _ <- f.get
                    c <- gate.passCount
                yield assert(c == 1)
            }
        }

        "initWith resource safety" in {
            for
                gate <- Scope.run(Gate.init(2))
                c    <- gate.closed
            yield assert(c)
        }

        "initUnscoped" in {
            for
                gate <- Gate.initUnscoped(2)
                f    <- Fiber.initUnscoped(gate.pass)
                _    <- gate.pass
                _    <- f.get
                c    <- gate.passCount
            yield assert(c == 1)
        }

        "initUnscopedWith" in {
            Gate.initUnscopedWith(1) { gate =>
                gate.pass.andThen(gate.passCount.map(c => assert(c == 1)))
            }
        }

        "initUnscoped with stop condition" in {
            for
                gate <- Gate.initUnscoped(1, (ph, _) => ph >= 2)
                _    <- gate.pass
                _    <- gate.pass
                _    <- gate.pass
                r    <- Abort.run[Closed](gate.pass)
                c    <- gate.closed
            yield
                assert(r.isFailure)
                assert(c)
        }

        "initUnscoped with totalPasses" in {
            for
                gate <- Gate.initUnscoped(1, totalPasses = 2)
                _    <- gate.pass
                _    <- gate.pass
                r    <- Abort.run[Closed](gate.pass)
                c    <- gate.closed
            yield
                assert(r.isFailure)
                assert(c)
        }

        "zero parties creates noop" in {
            for
                gate <- Gate.initUnscoped(0)
                c    <- gate.closed
            yield assert(c)
        }

        "negative parties creates noop" in {
            for
                gate <- Gate.initUnscoped(-1)
                c    <- gate.closed
            yield assert(c)
        }

        "noop pass returns immediately" in {
            for
                gate <- Gate.initUnscoped(0)
                _    <- gate.pass
                c    <- gate.closed
            yield assert(c)
        }
    }

    "pass" - {
        "single party" in {
            for
                gate <- Gate.initUnscoped(1)
                _    <- gate.pass
                c    <- gate.passCount
            yield assert(c == 1)
        }

        "two parties" in {
            for
                gate <- Gate.initUnscoped(2)
                _    <- Async.zip(gate.pass, gate.pass)
                c    <- gate.passCount
            yield assert(c == 1)
        }

        "two parties with fibers" in {
            for
                gate <- Gate.initUnscoped(2)
                f    <- Fiber.initUnscoped(gate.pass)
                _    <- gate.pass
                _    <- f.get
                c    <- gate.passCount
            yield assert(c == 1)
        }

        "resets after each pass (multi-pass)" in {
            for
                gate <- Gate.initUnscoped(2)
                _    <- Async.zip(gate.pass, gate.pass)
                _    <- Async.zip(gate.pass, gate.pass)
                _    <- Async.zip(gate.pass, gate.pass)
                c    <- gate.passCount
            yield assert(c == 3)
        }

        "single party multi-pass" in {
            for
                gate <- Gate.initUnscoped(1)
                _    <- gate.pass
                _    <- gate.pass
                _    <- gate.pass
                c    <- gate.passCount
            yield assert(c == 3)
        }

        "contention (1000 parties)" in {
            for
                gate <- Gate.initUnscoped(1000)
                _    <- Async.fill(1000, 1000)(gate.pass)
                c    <- gate.passCount
            yield assert(c == 1)
        }
    }

    "passWith" - {
        "applies continuation after pass" in {
            for
                gate <- Gate.initUnscoped(1)
                v    <- gate.passWith(42)
            yield assert(v == 42)
        }
    }

    "arrive" - {
        "returns immediately without waiting" in {
            for
                gate <- Gate.initUnscoped(2)
                _    <- gate.arrive
                p    <- gate.pendingCount
            yield assert(p == 1)
        }

        "last arrival triggers pass for waiters" in {
            for
                gate <- Gate.initUnscoped(2)
                f    <- Fiber.initUnscoped(gate.pass)
                _    <- assertEventually(gate.arrivedCount.map(_ == 1))
                _    <- gate.arrive
                _    <- f.get
            yield ()
        }

        "no-op on closed gate" in {
            for
                gate <- Gate.initUnscoped(2)
                _    <- gate.close
                _    <- gate.arrive
                a    <- gate.arrivedCount
            yield assert(a == 0)
        }

        "arriveWith applies continuation" in {
            for
                gate <- Gate.initUnscoped(1)
                v    <- gate.arriveWith(42)
            yield assert(v == 42)
        }

        "single party rapid arrive" in {
            for
                gate <- Gate.initUnscoped(1)
                _    <- Kyo.foreach(1 to 10)(_ => gate.arrive)
                c    <- gate.passCount
            yield assert(c == 10)
        }

        "arrive as last party completes promise for waiters" in {
            for
                gate <- Gate.initUnscoped(3)
                f1   <- Fiber.initUnscoped(gate.pass)
                f2   <- Fiber.initUnscoped(gate.pass)
                _    <- assertEventually(gate.arrivedCount.map(_ == 2))
                _    <- gate.arrive
                _    <- f1.get
                _    <- f2.get
            yield ()
        }
    }

    "mixed pass and arrive" - {
        "some pass, some arrive, gate advances correctly" in {
            for
                gate <- Gate.initUnscoped(3)
                f    <- Fiber.initUnscoped(gate.pass)
                _    <- assertEventually(gate.arrivedCount.map(_ == 1))
                _    <- gate.arrive
                _    <- gate.arrive
                _    <- f.get
                c    <- gate.passCount
            yield assert(c == 1)
        }

        "arrive triggers advance, pass waiters released" in {
            for
                gate <- Gate.initUnscoped(2)
                f    <- Fiber.initUnscoped(gate.pass)
                _    <- assertEventually(gate.arrivedCount.map(_ == 1))
                _    <- gate.arrive
                _    <- f.get
            yield ()
        }
    }

    "arrivedCount" - {
        "tracks arrivals within a pass" in {
            for
                gate <- Gate.initUnscoped(3)
                a0   <- gate.arrivedCount
                _    <- gate.arrive
                a1   <- gate.arrivedCount
                _    <- gate.arrive
                a2   <- gate.arrivedCount
            yield
                assert(a0 == 0)
                assert(a1 == 1)
                assert(a2 == 2)
        }

        "resets after pass completes" in {
            for
                gate <- Gate.initUnscoped(2)
                _    <- gate.arrive
                _    <- gate.arrive
                a    <- gate.arrivedCount
            yield assert(a == 0)
        }

        "reflects arrived count at time of close" in {
            for
                gate <- Gate.initUnscoped(3)
                _    <- gate.arrive
                _    <- gate.arrive
                _    <- gate.close
                a    <- gate.arrivedCount
            yield assert(a == 2)
        }
    }

    "pendingCount" - {
        "initial value equals parties" in {
            for
                gate <- Gate.initUnscoped(5)
                p    <- gate.pendingCount
            yield assert(p == 5)
        }

        "decreases on arrival" in {
            for
                gate <- Gate.initUnscoped(3)
                _    <- gate.arrive
                p    <- gate.pendingCount
            yield assert(p == 2)
        }

        "resets after pass completes" in {
            for
                gate <- Gate.initUnscoped(2)
                _    <- Async.zip(gate.pass, gate.pass)
                p    <- gate.pendingCount
            yield assert(p == 2)
        }

        "reflects pending count at time of close" in {
            for
                gate <- Gate.initUnscoped(5)
                _    <- gate.arrive
                _    <- gate.arrive
                _    <- gate.close
                p    <- gate.pendingCount
            yield assert(p == 3)
        }
    }

    "passAt" - {
        "n=0 waits for first pass" in {
            for
                gate <- Gate.initUnscoped(2)
                f    <- Fiber.initUnscoped(gate.passAt(0))
                d    <- Fiber.done(f)
                _    <- Async.zip(gate.pass, gate.pass)
                _    <- f.get
            yield assert(!d)
        }

        "returns immediately for past passes" in {
            for
                gate <- Gate.initUnscoped(1)
                _    <- gate.pass
                _    <- gate.pass
                _    <- gate.passAt(0)
                _    <- gate.passAt(1)
                c    <- gate.passCount
            yield assert(c == 2)
        }

        "blocks until target pass completes" in {
            for
                gate <- Gate.initUnscoped(1)
                f    <- Fiber.initUnscoped(gate.passAt(2))
                d1   <- Fiber.done(f)
                _    <- gate.pass
                d2   <- Fiber.done(f)
                _    <- gate.pass
                d3   <- Fiber.done(f)
                _    <- gate.pass
                _    <- f.get
            yield
                assert(!d1)
                assert(!d2)
                assert(!d3) // must wait for pass 2 to complete, not just pass 0
            end for
        }

        "n equals current pass blocks until it completes" in {
            for
                gate <- Gate.initUnscoped(1)
                _    <- gate.pass
                c    <- gate.passCount
                f    <- Fiber.initUnscoped(gate.passAt(c))
                _    <- gate.pass
                _    <- f.get
                c2   <- gate.passCount
            yield assert(c2 == 2)
        }

        "waits through multiple passes" in {
            for
                gate <- Gate.initUnscoped(1)
                f    <- Fiber.initUnscoped(gate.passAt(5))
                _    <- gate.pass // pass 0
                d0   <- Fiber.done(f)
                _    <- gate.pass // pass 1
                d1   <- Fiber.done(f)
                _    <- gate.pass // pass 2
                _    <- gate.pass // pass 3
                _    <- gate.pass // pass 4
                d4   <- Fiber.done(f)
                _    <- gate.pass // pass 5
                _    <- f.get
            yield
                assert(!d0) // not done after pass 0
                assert(!d1) // not done after pass 1
                assert(!d4) // not done after pass 4
        }

        "does not complete early on intermediate pass" in {
            for
                gate <- Gate.initUnscoped(2)
                f    <- Fiber.initUnscoped(gate.passAt(3))
                _    <- Async.zip(gate.pass, gate.pass) // pass 0
                d0   <- Fiber.done(f)
                _    <- Async.zip(gate.pass, gate.pass) // pass 1
                d1   <- Fiber.done(f)
                _    <- Async.zip(gate.pass, gate.pass) // pass 2
                d2   <- Fiber.done(f)
                _    <- Async.zip(gate.pass, gate.pass) // pass 3
                _    <- f.get
            yield
                assert(!d0)
                assert(!d1)
                assert(!d2) // must not complete until pass 3
        }

        "fails with Closed if gate closes before target" in {
            for
                gate <- Gate.initUnscoped(1)
                f    <- Fiber.initUnscoped(Abort.run[Closed](gate.passAt(5)))
                _    <- gate.pass // pass 0
                _    <- gate.close
                r    <- f.get
            yield assert(r.isFailure)
        }

        "past pass returns success even if gate is now closed" in {
            for
                gate <- Gate.initUnscoped(1)
                _    <- gate.pass
                _    <- gate.close
                _    <- gate.passAt(0)
                c    <- gate.closed
            yield assert(c)
        }
    }

    "passAtWith" - {
        "applies continuation after target pass" in {
            for
                gate <- Gate.initUnscoped(1)
                _    <- gate.pass
                v    <- gate.passAtWith(0)(42)
            yield assert(v == 42)
        }
    }

    "passCount" - {
        "starts at zero" in {
            for
                gate <- Gate.initUnscoped(3)
                c    <- gate.passCount
            yield assert(c == 0)
        }

        "increments on each completed pass" in {
            for
                gate <- Gate.initUnscoped(1)
                _    <- gate.pass
                c1   <- gate.passCount
                _    <- gate.pass
                c2   <- gate.passCount
            yield
                assert(c1 == 1)
                assert(c2 == 2)
        }

        "retains final value after close" in {
            for
                gate <- Gate.initUnscoped(1)
                _    <- gate.pass
                _    <- gate.pass
                _    <- gate.close
                c    <- gate.passCount
            yield assert(c == 2)
        }

        "retains final value after auto-close" in {
            for
                gate <- Gate.initUnscoped(1, totalPasses = 3)
                _    <- gate.pass
                _    <- gate.pass
                _    <- gate.pass
                c    <- gate.passCount
            yield assert(c == 3)
        }
    }

    "close" - {
        "returns true on first close" in {
            for
                gate <- Gate.initUnscoped(2)
                r    <- gate.close
            yield assert(r)
        }

        "returns false on subsequent close" in {
            for
                gate <- Gate.initUnscoped(2)
                r1   <- gate.close
                r2   <- gate.close
            yield
                assert(r1)
                assert(!r2)
        }

        "closed returns true after close" in {
            for
                gate <- Gate.initUnscoped(2)
                _    <- gate.close
                c    <- gate.closed
            yield assert(c)
        }

        "pass fails with Abort[Closed] after close" in {
            for
                gate <- Gate.initUnscoped(2)
                _    <- gate.close
                r    <- Abort.run[Closed](gate.pass)
            yield assert(r.isFailure)
        }

        "pending pass waiters fail with Abort[Closed]" in {
            for
                gate <- Gate.initUnscoped(3)
                f1   <- Fiber.initUnscoped(Abort.run[Closed](gate.pass))
                f2   <- Fiber.initUnscoped(Abort.run[Closed](gate.pass))
                _    <- assertEventually(gate.arrivedCount.map(_ == 2))
                _    <- gate.close
                r1   <- f1.get
                r2   <- f2.get
            yield
                assert(r1.isFailure)
                assert(r2.isFailure)
        }

        "pending passAt waiters fail with Abort[Closed]" in {
            for
                gate <- Gate.initUnscoped(1)
                f    <- Fiber.initUnscoped(Abort.run[Closed](gate.passAt(5)))
                _    <- gate.close
                r    <- f.get
            yield assert(r.isFailure)
        }

        "close during advancePass (stop condition) completes pass then closes" in {
            for
                gate <- Gate.initUnscoped(2, (ph, _) => ph >= 0)
                r1   <- Fiber.initUnscoped(Abort.run[Closed](gate.pass))
                r2   <- Fiber.initUnscoped(Abort.run[Closed](gate.pass))
                v1   <- r1.get
                v2   <- r2.get
                c    <- gate.closed
            yield
                assert(v1.isSuccess)
                assert(v2.isSuccess)
                assert(c)
        }
    }

    "auto-close" - {
        "stop condition closes gate" in {
            for
                gate <- Gate.initUnscoped(1, (ph, _) => ph >= 1)
                _    <- gate.pass
                _    <- gate.pass
                r    <- Abort.run[Closed](gate.pass)
                c    <- gate.closed
            yield
                assert(r.isFailure)
                assert(c)
        }

        "stop condition receives correct parties count" in {
            var receivedParties = -1
            for
                gate <- Gate.initUnscoped(
                    5,
                    (_, p) =>
                        receivedParties = p; false
                )
                _ <- Async.fill(5, 5)(gate.pass)
            yield assert(receivedParties == 5)
            end for
        }

        "totalPasses closes after N passes" in {
            for
                gate <- Gate.initUnscoped(1, totalPasses = 3)
                _    <- gate.pass
                _    <- gate.pass
                _    <- gate.pass
                r    <- Abort.run[Closed](gate.pass)
                c    <- gate.closed
            yield
                assert(r.isFailure)
                assert(c)
        }

        "totalPasses=1 allows exactly one pass" in {
            for
                gate <- Gate.initUnscoped(1, totalPasses = 1)
                _    <- gate.pass
                r    <- Abort.run[Closed](gate.pass)
            yield assert(r.isFailure)
        }

        "totalPasses=0 creates noop" in {
            for
                gate <- Gate.initUnscoped(1, totalPasses = 0)
                c    <- gate.closed
                _    <- gate.pass
            yield assert(c)
        }

        "negative totalPasses creates noop" in {
            for
                gate <- Gate.initUnscoped(1, totalPasses = -1)
                c    <- gate.closed
                _    <- gate.pass
            yield assert(c)
        }

        "zero parties triggers close on advance" in {
            for
                gate <- Gate.Dynamic.initUnscoped(1)
                _    <- gate.leave
                c    <- gate.closed
            yield assert(c)
        }
    }

    "state after close" - {
        "arrivedCount reflects state at time of close" in {
            for
                gate <- Gate.initUnscoped(5)
                _    <- gate.arrive
                _    <- gate.arrive
                _    <- gate.arrive
                _    <- gate.close
                a    <- gate.arrivedCount
            yield assert(a == 3)
        }

        "pendingCount reflects state at time of close" in {
            for
                gate <- Gate.initUnscoped(5)
                _    <- gate.arrive
                _    <- gate.arrive
                _    <- gate.close
                p    <- gate.pendingCount
            yield assert(p == 3)
        }

        "passCount reflects state at time of close" in {
            for
                gate <- Gate.initUnscoped(1)
                _    <- gate.pass
                _    <- gate.pass
                _    <- gate.pass
                _    <- gate.close
                c    <- gate.passCount
            yield assert(c == 3)
        }

        "closed returns true" in {
            for
                gate <- Gate.initUnscoped(3)
                _    <- gate.close
                c    <- gate.closed
            yield assert(c)
        }

        "close returns false on second call" in {
            for
                gate <- Gate.initUnscoped(3)
                r1   <- gate.close
                r2   <- gate.close
            yield
                assert(r1)
                assert(!r2)
        }

        "all counters consistent with each other" in {
            for
                gate <- Gate.initUnscoped(10)
                _    <- Kyo.foreach(1 to 4)(_ => gate.arrive)
                _    <- gate.close
                a    <- gate.arrivedCount
                p    <- gate.pendingCount
                c    <- gate.passCount
            yield
                assert(a == 4)
                assert(p == 6)
                assert(a + p == 10)
                assert(c == 0)
        }

        "auto-close preserves counters" in {
            for
                gate <- Gate.initUnscoped(1, totalPasses = 2)
                _    <- gate.pass
                _    <- gate.pass
                a    <- gate.arrivedCount
                p    <- gate.pendingCount
                c    <- gate.passCount
            yield
                assert(c == 2)
                assert(a == 0)
                assert(p == 1)
        }

        "close with no arrivals" in {
            for
                gate <- Gate.initUnscoped(5)
                _    <- gate.close
                a    <- gate.arrivedCount
                p    <- gate.pendingCount
            yield
                assert(a == 0)
                assert(p == 5)
        }

        "close after partial arrivals" in {
            for
                gate <- Gate.initUnscoped(4)
                _    <- gate.arrive
                _    <- gate.close
                a    <- gate.arrivedCount
                p    <- gate.pendingCount
            yield
                assert(a == 1)
                assert(p == 3)
        }

        "close immediately after pass advance" in {
            for
                gate <- Gate.initUnscoped(2)
                _    <- Async.zip(gate.pass, gate.pass)
                _    <- gate.close
                a    <- gate.arrivedCount
                p    <- gate.pendingCount
                c    <- gate.passCount
            yield
                assert(a == 0)
                assert(p == 2)
                assert(c == 1)
        }
    }

    "interrupt masking" - {
        "interrupting one waiter does not affect others" in {
            for
                gate <- Gate.initUnscoped(3)
                f1   <- Fiber.initUnscoped(gate.pass)
                f2   <- Fiber.initUnscoped(gate.pass)
                _    <- assertEventually(gate.arrivedCount.map(_ == 2))
                _    <- f1.interrupt
                _    <- assertEventually(Fiber.done(f1))
                f3   <- Fiber.initUnscoped(gate.pass)
                _    <- f2.get
                _    <- f3.get
            yield ()
        }
    }

    "concurrency" - {

        val repeats = 100

        "pass and close" in {
            (for
                parties <- Choice.eval(1, 2, 3, 10)
                gate    <- Gate.initUnscoped(parties)
                latch   <- Latch.init(1)
                passFiber <- Fiber.initUnscoped(
                    latch.await.andThen(Async.fill(parties, parties)(Abort.run[Closed](gate.pass)))
                )
                closeFiber <- Fiber.initUnscoped(latch.await.andThen(gate.close))
                _          <- latch.release
                results    <- passFiber.get
                _          <- closeFiber.get
                isClosed   <- gate.closed
            yield
                assert(isClosed)
                assert(results.forall(r => r.isSuccess || r.isFailure))
            )
                .handle(Choice.run, _.unit, Loop.repeat(repeats))
                .unit
        }

        "pass and arrive" in {
            (for
                parties <- Choice.eval(2, 3, 5)
                gate    <- Gate.initUnscoped(parties)
                latch   <- Latch.init(1)
                passFiber <- Fiber.initUnscoped(
                    latch.await.andThen(Async.fill(parties - 1, parties - 1)(Abort.run[Closed](gate.pass)))
                )
                arriveFiber <- Fiber.initUnscoped(
                    latch.await.andThen(gate.arrive)
                )
                _ <- latch.release
                _ <- passFiber.get
                _ <- arriveFiber.get
                c <- gate.passCount
            yield assert(c == 1))
                .handle(Choice.run, _.unit, Loop.repeat(repeats))
                .unit
        }

        "concurrent close attempts" in {
            (for
                parties <- Choice.eval(1, 2, 3, 10)
                gate    <- Gate.initUnscoped(parties)
                latch   <- Latch.init(1)
                closeFiber <- Fiber.initUnscoped(
                    latch.await.andThen(Async.fill(10, 10)(gate.close))
                )
                _       <- latch.release
                results <- closeFiber.get
            yield assert(results.count(_ == true) == 1))
                .handle(Choice.run, _.unit, Loop.repeat(repeats))
                .unit
        }

        "multi-pass contention" in {
            (for
                parties <- Choice.eval(2, 3, 5, 10)
                gate    <- Gate.initUnscoped(parties)
                _       <- Async.fill(parties, parties)(Kyo.foreach(1 to 10)(_ => gate.pass))
                count   <- gate.passCount
            yield assert(count == 10))
                .handle(Choice.run, _.unit, Loop.repeat(repeats))
                .unit
        }

        "arrive contention with 1 party" in {
            (for
                gate  <- Gate.initUnscoped(1)
                latch <- Latch.init(1)
                fibers <- Async.fill(10, 10)(
                    Fiber.initUnscoped(latch.await.andThen(gate.arrive))
                )
                _     <- latch.release
                _     <- Kyo.foreach(fibers)(_.get)
                count <- gate.passCount
            yield assert(count == 10))
                .handle(Loop.repeat(repeats))
                .unit
        }

        "arrive with stop condition under contention" in {
            (for
                gate  <- Gate.initUnscoped(1, totalPasses = 5)
                latch <- Latch.init(1)
                fiber <- Fiber.initUnscoped(
                    latch.await.andThen(
                        Kyo.foreach(1 to 10)(_ => Abort.run[Closed](gate.pass))
                    )
                )
                _       <- latch.release
                results <- fiber.get
                count   <- gate.passCount
                c       <- gate.closed
            yield
                assert(count == 5)
                assert(c)
                assert(results.count(_.isSuccess) == 5)
            )
                .handle(Loop.repeat(repeats))
                .unit
        }
    }

    "Dynamic" - {
        "init" - {
            "initWith" in {
                Gate.Dynamic.initWith(2) { gate =>
                    for
                        f <- Fiber.initUnscoped(gate.pass)
                        _ <- gate.pass
                        _ <- f.get
                        c <- gate.passCount
                    yield assert(c == 1)
                }
            }

            "initUnscoped" in {
                for
                    gate <- Gate.Dynamic.initUnscoped(1)
                    _    <- gate.pass
                    c    <- gate.passCount
                yield assert(c == 1)
            }

            "zero parties creates noop" in {
                for
                    gate <- Gate.Dynamic.initUnscoped(0)
                    c    <- gate.closed
                yield assert(c)
            }

            "negative parties creates noop" in {
                for
                    gate <- Gate.Dynamic.initUnscoped(-1)
                    c    <- gate.closed
                yield assert(c)
            }

            "noop pass returns immediately" in {
                for
                    gate <- Gate.Dynamic.initUnscoped(0)
                    _    <- gate.pass
                    c    <- gate.closed
                yield assert(c)
            }

            "totalPasses closes after N passes" in {
                for
                    gate <- Gate.Dynamic.initUnscoped(1, totalPasses = 2)
                    _    <- gate.pass
                    _    <- gate.pass
                    r    <- Abort.run[Closed](gate.pass)
                    c    <- gate.closed
                yield
                    assert(r.isFailure)
                    assert(c)
            }

            "totalPasses=0 creates noop" in {
                for
                    gate <- Gate.Dynamic.initUnscoped(1, totalPasses = 0)
                    c    <- gate.closed
                    _    <- gate.pass
                yield assert(c)
            }

            "negative totalPasses creates noop" in {
                for
                    gate <- Gate.Dynamic.initUnscoped(1, totalPasses = -1)
                    c    <- gate.closed
                    _    <- gate.pass
                yield assert(c)
            }
        }

        "join" - {
            "adds party" in {
                for
                    gate <- Gate.Dynamic.initUnscoped(1)
                    _    <- gate.join
                    s    <- gate.size
                yield assert(s == 2)
            }

            "join(n) adds n parties" in {
                for
                    gate <- Gate.Dynamic.initUnscoped(1)
                    _    <- gate.join(5)
                    s    <- gate.size
                yield assert(s == 6)
            }

            "joinWith applies continuation" in {
                for
                    gate <- Gate.Dynamic.initUnscoped(1)
                    v    <- gate.joinWith(42)
                yield assert(v == 42)
            }

            "no-op on closed gate" in {
                for
                    gate <- Gate.Dynamic.initUnscoped(1)
                    _    <- gate.close
                    _    <- gate.join
                    s    <- gate.size
                yield assert(s == 1)
            }
        }

        "leave" - {
            "removes party" in {
                for
                    gate <- Gate.Dynamic.initUnscoped(3)
                    _    <- gate.leave
                    s    <- gate.size
                yield assert(s == 2)
            }

            "leaveWith applies continuation" in {
                for
                    gate <- Gate.Dynamic.initUnscoped(2)
                    v    <- gate.leaveWith(42)
                yield assert(v == 42)
            }

            "triggers pass when all remaining have arrived" in {
                for
                    gate <- Gate.Dynamic.initUnscoped(3)
                    f    <- Fiber.initUnscoped(gate.pass)
                    _    <- assertEventually(gate.arrivedCount.map(_ == 1))
                    _    <- gate.arrive
                    _    <- assertEventually(gate.arrivedCount.map(_ == 2))
                    _    <- gate.leave
                    _    <- f.get
                    c    <- gate.passCount
                yield assert(c == 1)
            }

            "leave to zero parties closes gate" in {
                for
                    gate <- Gate.Dynamic.initUnscoped(1)
                    _    <- gate.leave
                    c    <- gate.closed
                yield assert(c)
            }

            "no-op on closed gate" in {
                for
                    gate <- Gate.Dynamic.initUnscoped(2)
                    _    <- gate.close
                    _    <- gate.leave
                    s    <- gate.size
                yield assert(s == 2)
            }

            "leave after arrive triggers advance" in {
                for
                    gate <- Gate.Dynamic.initUnscoped(3)
                    f    <- Fiber.initUnscoped(gate.pass)
                    _    <- assertEventually(gate.arrivedCount.map(_ == 1))
                    _    <- gate.arrive
                    _    <- assertEventually(gate.arrivedCount.map(_ == 2))
                    _    <- gate.leave
                    _    <- f.get
                yield ()
            }
        }

        "size" - {
            "reflects current party count after join and leave" in {
                for
                    gate <- Gate.Dynamic.initUnscoped(2)
                    s0   <- gate.size
                    _    <- gate.join
                    s1   <- gate.size
                    _    <- gate.leave
                    s2   <- gate.size
                yield
                    assert(s0 == 2)
                    assert(s1 == 3)
                    assert(s2 == 2)
            }

            "reflects party count at time of close" in {
                for
                    gate <- Gate.Dynamic.initUnscoped(3)
                    _    <- gate.join(2)
                    _    <- gate.close
                    s    <- gate.size
                yield assert(s == 5)
            }
        }

        "subgroup" - {
            "subgroup pass signals parent" in {
                for
                    parent <- Gate.Dynamic.initUnscoped(1)
                    sub    <- parent.subgroup(1)
                    pf     <- Fiber.initUnscoped(parent.pass)
                    _      <- sub.pass
                    _      <- pf.get
                    c      <- parent.passCount
                yield assert(c == 1)
            }

            "parent waits for all subgroups" in {
                for
                    parent <- Gate.Dynamic.initUnscoped(1)
                    sub1   <- parent.subgroup(1)
                    sub2   <- parent.subgroup(1)
                    pf     <- Fiber.initUnscoped(parent.pass)
                    d1     <- pf.done
                    _      <- sub1.pass
                    d2     <- pf.done
                    _      <- sub2.pass
                    _      <- pf.get
                yield assert(!d1)
            }

            "subgroup close signals parent" in {
                for
                    parent <- Gate.Dynamic.initUnscoped(1)
                    sub    <- parent.subgroup(1)
                    pf     <- Fiber.initUnscoped(parent.pass)
                    _      <- sub.close
                    _      <- parent.pass
                    _      <- pf.get
                    c      <- parent.passCount
                yield assert(c >= 1) // subgroup.close triggers parent pass; at least one pass completed
            }

            "subgroup with zero parties" in {
                for
                    parent <- Gate.Dynamic.initUnscoped(1)
                    sub    <- parent.subgroup(0)
                    sc     <- sub.closed
                    ps     <- parent.size
                yield
                    // Zero-party subgroup is live but doesn't join parent
                    assert(!sc)
                    assert(ps == 1)
            }

            "nested subgroups (subgroup of subgroup)" in {
                for
                    root <- Gate.Dynamic.initUnscoped(0)
                    mid  <- root.subgroup(0)
                    leaf <- mid.subgroup(1)
                    rf   <- Fiber.initUnscoped(root.pass)
                    _    <- leaf.pass
                    _    <- rf.get
                yield succeed("leaf.pass propagates up through mid to root; no deadlock")
            }
        }

        "close" - {
            "signals parent via arriveAndLeave" in {
                for
                    parent <- Gate.Dynamic.initUnscoped(1)
                    sub    <- parent.subgroup(2)
                    pf     <- Fiber.initUnscoped(parent.pass)
                    _      <- sub.close
                    _      <- parent.pass
                    _      <- pf.get
                    ps     <- parent.size
                yield assert(ps == 1)
            }

            "pending waiters fail with Abort[Closed]" in {
                for
                    gate <- Gate.Dynamic.initUnscoped(3)
                    f1   <- Fiber.initUnscoped(Abort.run[Closed](gate.pass))
                    f2   <- Fiber.initUnscoped(Abort.run[Closed](gate.pass))
                    _    <- assertEventually(gate.arrivedCount.map(_ == 2))
                    _    <- gate.close
                    r1   <- f1.get
                    r2   <- f2.get
                yield
                    assert(r1.isFailure)
                    assert(r2.isFailure)
            }
        }

        "stop condition" - {
            "receives correct dynamic parties count" in {
                var receivedParties = -1
                for
                    gate <- Gate.Dynamic.initUnscoped(
                        2,
                        (_, p) =>
                            receivedParties = p; false
                    )
                    _ <- gate.join
                    _ <- Async.fill(3, 3)(gate.pass)
                yield assert(receivedParties == 3)
                end for
            }
        }

        "state after close" - {
            "size reflects party count at time of close" in {
                for
                    gate <- Gate.Dynamic.initUnscoped(3)
                    _    <- gate.join(2)
                    _    <- gate.close
                    s    <- gate.size
                yield assert(s == 5)
            }

            "arrivedCount reflects state at time of close" in {
                for
                    gate <- Gate.Dynamic.initUnscoped(5)
                    _    <- gate.arrive
                    _    <- gate.arrive
                    _    <- gate.close
                    a    <- gate.arrivedCount
                yield assert(a == 2)
            }

            "pendingCount reflects state at time of close" in {
                for
                    gate <- Gate.Dynamic.initUnscoped(5)
                    _    <- gate.arrive
                    _    <- gate.close
                    p    <- gate.pendingCount
                yield assert(p == 4)
            }

            "close after join preserves increased size" in {
                for
                    gate <- Gate.Dynamic.initUnscoped(2)
                    _    <- gate.join(3)
                    _    <- gate.close
                    s    <- gate.size
                    p    <- gate.pendingCount
                yield
                    assert(s == 5)
                    assert(p == 5)
            }

            "close after leave preserves decreased size" in {
                for
                    gate <- Gate.Dynamic.initUnscoped(5)
                    _    <- gate.leave
                    _    <- gate.leave
                    _    <- gate.close
                    s    <- gate.size
                    p    <- gate.pendingCount
                yield
                    assert(s == 3)
                    assert(p == 3)
            }
        }

        "concurrency" - {

            val repeats = 100

            "join races with pass" in {
                (for
                    parties <- Choice.eval(2, 3, 5)
                    gate    <- Gate.Dynamic.initUnscoped(parties)
                    latch   <- Latch.init(1)
                    passFiber <- Fiber.initUnscoped(
                        latch.await.andThen(Async.fill(parties, parties)(Abort.run[Closed](gate.pass)))
                    )
                    joinFiber <- Fiber.initUnscoped(
                        latch.await.andThen(gate.join)
                    )
                    _       <- latch.release
                    _       <- joinFiber.get
                    _       <- gate.close
                    results <- passFiber.get
                    c       <- gate.closed
                    s       <- gate.size
                yield
                    assert(c)
                    assert(s == parties + 1)
                    assert(results.forall(r => r.isSuccess || r.isFailure))
                )
                    .handle(Choice.run, _.unit, Loop.repeat(repeats))
                    .unit
            }

            "subgroup contention" in {
                (for
                    parent <- Gate.Dynamic.initUnscoped(1)
                    subs   <- Kyo.foreach(1 to 3)(_ => parent.subgroup(1))
                    latch  <- Latch.init(1)
                    parentFiber <- Fiber.initUnscoped(
                        latch.await.andThen(Abort.run[Closed](parent.pass))
                    )
                    subFibers <- Kyo.foreach(subs)(sub =>
                        Fiber.initUnscoped(latch.await.andThen(Abort.run[Closed](sub.pass)))
                    )
                    _ <- latch.release
                    _ <- Kyo.foreach(subFibers)(_.get)
                    _ <- parentFiber.get
                    c <- parent.passCount
                yield assert(c == 1))
                    .handle(Loop.repeat(repeats))
                    .unit
            }

            "close subgroup during parent arrive contention" in {
                (for
                    parent <- Gate.Dynamic.initUnscoped(1)
                    sub    <- parent.subgroup(1)
                    latch  <- Latch.init(1)
                    closeFiber <- Fiber.initUnscoped(
                        latch.await.andThen(sub.close)
                    )
                    passFiber <- Fiber.initUnscoped(
                        latch.await.andThen(Abort.run[Closed](parent.pass))
                    )
                    _ <- latch.release
                    _ <- closeFiber.get
                    r <- passFiber.get
                yield
                    // race test: verifies no hang/panic; outcome is non-deterministic
                    assert(r.isSuccess || r.isFailure))
                    .handle(Loop.repeat(repeats))
                    .unit
            }
        }
    }

    "unsafe" - {
        import AllowUnsafe.embrace.danger

        "initializes with correct pending count" in {
            val gate = Gate.Unsafe.init(3)
            assert(gate.pendingCount() == 3)
        }

        "pass decrements pending count" in {
            val gate = Gate.Unsafe.init(3)
            gate.pass()
            assert(gate.pendingCount() == 2)
        }

        "releases all parties when last arrives" in {
            val gate          = Gate.Unsafe.init(3)
            var releasedCount = 0
            for _ <- 1 to 3 do
                val fiber = gate.pass()
                fiber.onComplete(_ => releasedCount += 1)
            // Gate is multi-pass: after all arrive, it resets for next pass
            assert(gate.pendingCount() == 3)
            assert(gate.passCount() == 1)
            assert(releasedCount == 3)
        }

        "noop for zero parties" in {
            val gate = Gate.Unsafe.init(0)
            assert(gate.pendingCount() == 0)
            val fiber = gate.pass()
            assert(fiber.done())
        }

        "safe conversion" in {
            val unsafeGate     = Gate.Unsafe.init(2)
            val safeGate: Gate = unsafeGate.safe
            assert(safeGate.unsafe.pendingCount() == 2)
        }
    }

end GateTest
