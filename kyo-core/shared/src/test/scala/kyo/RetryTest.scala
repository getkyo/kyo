package kyo

class RetryTest extends kyo.test.Test[Any]:

    val ex = new Exception

    "no retries" - {
        "ok" in {
            var calls = 0
            Retry[Any](Schedule.never) {
                calls += 1
                42
            }.map { v =>
                assert(v == 42 && calls == 1)
            }
        }
        "nok" in {
            var calls = 0
            Abort.run[Exception] {
                Retry[Exception](Schedule.never) {
                    calls += 1
                    throw ex
                }
            }.map { v =>
                assert(v.isFailure && calls == 1)
            }
        }
    }

    "retries" - {
        "ok" in {
            var calls = 0
            Retry[Any](Schedule.repeat(3)) {
                calls += 1
                42
            }.map { v =>
                assert(v == 42 && calls == 1)
            }
        }
        "nok" in {
            var calls = 0
            Abort.run[Exception] {
                Retry[Exception](Schedule.repeat(3)) {
                    calls += 1
                    throw ex
                }
            }.map { v =>
                assert(v.isFailure && calls == 4)
            }
        }
    }

    "backoff" in {
        var calls = 0
        // Virtual time: the retry blocks on the backoff schedule (1+2+4+8ms) until the advancer drives the
        // clock, so `!early` proves the backoff delayed it and `calls == 5` is exact, with no wall clock.
        Clock.withTimeControl { control =>
            for
                fiber <- Fiber.initUnscoped {
                    Abort.run[Exception] {
                        Retry[Exception](Schedule.exponentialBackoff(1.milli, 2.0, Duration.Infinity).take(4)) {
                            calls += 1
                            throw ex
                        }
                    }
                }
                early    <- fiber.done
                advancer <- Fiber.initUnscoped(Loop.forever(control.advance(1.milli)))
                v        <- fiber.get
                _        <- advancer.interrupt
            yield assert(!early && v.isFailure && calls == 5)
        }
    }

    "runs that first retry at the same instant do not all retry in step" in {
        // Under a clock frozen at the epoch every run evaluates the shared jittered schedule at the same instant. Each advance of one
        // microsecond fires the sleepers due by then; a fired run calls again and re-arms its next sleeper before the fence after the
        // advance returns, so the fence pins each run's call count.
        val runs     = 4
        val schedule = Schedule.fixed(1.milli).jitter(0.5).take(1000)
        Clock.withTimeControl { control =>
            for
                calls   <- Kyo.fill(runs)(AtomicInt.init(0))
                running <- Kyo.foreach(calls.zipWithIndex) { (counter, idx) =>
                    Fiber.initUnscoped(Abort.run[String] {
                        Random.withSeed(11 + idx)(Retry[String](schedule)(counter.incrementAndGet.andThen(Abort.fail("retry"))))
                    })
                }
                _       <- control.awaitPendingSleepers(runs)
                firedAt <- Loop(0, Map.empty[Int, Int]) { (step, fired) =>
                    if fired.size == runs || step > 2000 then Loop.done(fired)
                    else
                        for
                            _      <- control.advance(1.micros, Duration.Zero)
                            _      <- control.awaitPendingSleepers(runs)
                            counts <- Kyo.foreach(calls)(_.get)
                        yield Loop.continue(
                            step + 1,
                            counts.zipWithIndex.foldLeft(fired) { case (acc, (count, idx)) =>
                                if count >= 2 && !acc.contains(idx) then acc.updated(idx, step) else acc
                            }
                        )
                }
                _ <- Kyo.foreachDiscard(running)(_.interrupt)
            yield
                assert(firedAt.size == runs, s"every run must retry within 2ms: firedAt=$firedAt")
                assert(firedAt.values.toSet.size > 1, s"every run retried in the same microsecond: firedAt=$firedAt")
            end for
        }
    }

    "a retry on a schedule without jitter leaves a seeded Random stream untouched" in {
        Clock.withTimeControl { control =>
            for
                expected <- Random.withSeed(5)(Random.nextLong)
                calls    <- AtomicInt.init(0)
                fiber    <- Fiber.initUnscoped {
                    Random.withSeed(5) {
                        Abort.run[String](Retry[String](Schedule.fixed(1.milli).take(1)) {
                            calls.incrementAndGet.map(n => if n == 1 then Abort.fail("retry") else Kyo.unit)
                        }).andThen(Random.nextLong)
                    }
                }
                _      <- control.awaitPendingSleepers(1)
                _      <- control.advance(1.milli, Duration.Zero)
                actual <- fiber.get
                n      <- calls.get
            yield assert(n == 2 && actual == expected, s"calls=$n first draw after the retry=$actual expected=$expected")
        }
    }

    "a run under a seeded Random retries after the delay its schedule gives with the first draw as seed" in {
        // Every attempt fails and re-arms a sleeper before the fence after an advance returns, so the fence pins the call count.
        val schedule = Schedule.fixed(1.milli).jitter(0.5).take(1000)
        Random.withSeed(7)(Random.nextLong).map { seed =>
            val expected = schedule.withSeed(seed).next(Instant.Epoch).map(_._1).getOrElse(Duration.Zero)
            Clock.withTimeControl { control =>
                for
                    calls <- AtomicInt.init(0)
                    fiber <- Fiber.initUnscoped(Abort.run[String] {
                        Random.withSeed(7)(Retry[String](schedule)(calls.incrementAndGet.andThen(Abort.fail("retry"))))
                    })
                    _         <- control.awaitPendingSleepers(1)
                    _         <- control.advance(expected.minusOrZero(1.nanos), Duration.Zero)
                    _         <- control.awaitPendingSleepers(1)
                    justShort <- calls.get
                    _         <- control.advance(1.nanos, Duration.Zero)
                    _         <- control.awaitPendingSleepers(1)
                    atDelay   <- calls.get
                    _         <- fiber.interrupt
                yield
                    assert(expected > Duration.Zero && expected != 1.milli)
                    assert(justShort == 1, s"the retry fired before the ${expected.show} the seeded schedule gives")
                    assert(atDelay == 2, s"the retry did not fire at the ${expected.show} the seeded schedule gives")
                end for
            }
        }
    }

    "default schedule" - {
        "succeeds immediately without retries" in {
            var calls = 0
            Retry[Any] {
                calls += 1
                42
            }.map { v =>
                assert(v == 42 && calls == 1)
            }
        }

        "retries up to max attempts" in {
            var calls = 0
            Abort.run[Exception] {
                Retry[Exception] {
                    calls += 1
                    throw ex
                }
            }.map { v =>
                assert(v.isFailure && calls == 4)
            }
        }
    }

    "panics" - {
        "should not retry on panic" in {
            var calls = 0
            Abort.run[Exception] {
                Retry[Exception](Schedule.repeat(3)) {
                    calls += 1
                    Abort.panic(new RuntimeException("panic"))
                }
            }.map { v =>
                assert(v.isPanic && calls == 1)
            }
        }

        "should not retry on panic with default schedule" in {
            var calls = 0
            Abort.run[Exception] {
                Retry[Exception] {
                    calls += 1
                    Abort.panic(new RuntimeException("panic"))
                }
            }.map { v =>
                assert(v.isPanic && calls == 1)
            }
        }
    }
end RetryTest
