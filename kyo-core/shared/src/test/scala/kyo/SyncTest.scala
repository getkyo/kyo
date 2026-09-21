package kyo

import kyo.Result.Error
import kyo.Result.Panic
import scala.util.Try

class SyncTest extends kyo.test.Test[Any]:

    sealed private trait Replayed extends kyo.kernel.ArrowEffect[Const[Unit], Const[Int]]

    "lazyRun" - {
        "execution" in {
            var called = false
            val v      =
                Sync.defer {
                    called = true
                    1
                }
            assert(!called)
            v.map { result =>
                assert(result == 1)
                assert(called)
            }
        }
        "next handled effects can execute" in {
            import AllowUnsafe.embrace.danger
            var called = false
            val v      =
                Env.get[Int].map { i =>
                    Sync.defer {
                        called = true
                        i
                    }
                }
            assert(!called)
            val v2 = Sync.Unsafe.run(v)
            assert(!called)
            assert(
                Abort.run(Env.run(1)(v2)).eval ==
                    Result.succeed(1)
            )
            assert(called)
        }
        "failure" in {
            import AllowUnsafe.embrace.danger
            val ex        = new Exception
            def fail: Int = throw ex

            val ios = List(
                Sync.defer(fail),
                Sync.defer(fail).map(_ + 1),
                Sync.defer(1).map(_ => fail),
                Sync.defer(Sync.defer(1)).map(_ => fail)
            )
            ios.foreach { io =>
                assert(Try(Sync.Unsafe.evalOrThrow(io)) == Try(fail))
            }
            ()
        }
        "stack-safe" in {
            val frames                   = 10000
            def loop(i: Int): Int < Sync =
                Sync.defer {
                    if i < frames then
                        loop(i + 1)
                    else
                        i
                }
            loop(0).map { result =>
                assert(result == frames)
            }
        }
        // The map after the recursive defer makes each level
        // leave a cont behind (#1739). The assertion is on the value, so a rescue that unwinds by
        // dropping accumulated conts fails too.
        "stack-safe when a map follows the recursive defer" in {
            val depth                    = 1000000
            def step(n: Int): Int < Sync =
                if n <= 0 then 0
                else Sync.defer(step(n - 1)).map(_ + 1)
            step(depth).map { result =>
                assert(result == depth)
            }
        }
    }
    "run" - {
        "execution" in {
            var called        = false
            val v: Int < Sync =
                Sync.defer {
                    called = true
                    1
                }
            assert(!called)
            v.map { result =>
                assert(result == 1)
                assert(called)
            }
        }
        "stack-safe" in {
            val frames                    = 100000
            def loop(i: Int): Unit < Sync =
                Sync.defer {
                    if i < frames then
                        loop(i + 1)
                    else
                        (
                    )
                }
            loop(0).andThen(succeed("verifies no stack overflow at depth 100000"))
        }
        "failure" in {
            import AllowUnsafe.embrace.danger
            val ex        = new Exception
            def fail: Int = throw ex

            val ios = List(
                Sync.defer(fail),
                Sync.defer(fail).map(_ + 1),
                Sync.defer(1).map(_ => fail),
                Sync.defer(Sync.defer(1)).map(_ => fail)
            )
            ios.foreach { io =>
                assert(Try(Sync.Unsafe.evalOrThrow(io)) == Try(fail))
            }
            ()
        }
    }

    "ensure" - {
        "success" in {
            var called = false
            Sync.ensure { called = true }(1).map { result =>
                assert(result == 1)
                assert(called)
            }
        }
        "failure" in {
            val ex     = new Exception
            var called = false
            Abort.run[Any](Sync.ensure { called = true } {
                Sync.defer[Int, Any](throw ex)
            }).map { result =>
                assert(result == Result.panic(ex))
                assert(called)
            }
        }
        "call-by-name" in {
            var count       = 0
            var countEnsure = 0

            val io: Unit < Sync =
                Sync.ensure({ countEnsure = countEnsure + 1 })({ count = count + 1 })

            io.andThen(io).map: _ =>
                assert(count == 2)
                assert(countEnsure == 2)
        }

        "resource safety" - {
            "runs finalizer on Abort.fail" in {
                var called = false
                Abort.run[String](Sync.ensure { called = true }(Abort.fail("boom"))).map { result =>
                    assert(result == Result.fail("boom"))
                    assert(called)
                }
            }

            // The way to hold a resource across a computation that can abort, given the gap above: reify
            // every outcome into a Result inside the bracket so the body always completes, and re-raise it
            // after the release has run. kyo.Jsonl.writeAll holds one file write handle across a stream fold
            // this way, so this pins the shape that keeps the handle from leaking on a failing stream.
            "reifying the body's aborts into a Result makes acquireReleaseWith release on abort" in {
                var released = 0
                val computed =
                    Sync.acquireReleaseWith(Sync.defer("resource"))(_ => Sync.defer { released += 1 }) { _ =>
                        Abort.run[Any](Abort.fail("boom"))
                    }.map(outcome => Abort.get(outcome.asInstanceOf[Result[Nothing, Unit]]))
                Abort.run[String](computed).map { result =>
                    assert(result == Result.fail("boom"))
                    assert(released == 1)
                }
            }

            "runs finalizer exactly once under multiple evaluations" in {
                var count = 0
                Sync.ensure { count += 1 }(1).map(_ + 1).map { result =>
                    assert(count == 1)
                    assert(result == 2)
                }
            }

            "nested ensures execute in LIFO order" in {
                var order = List.empty[Int]
                Sync.ensure { order = 1 :: order } {
                    Sync.ensure { order = 2 :: order } {
                        Sync.ensure { order = 3 :: order } {
                            42
                        }
                    }
                }.map { _ =>
                    assert(order == List(1, 2, 3))
                }
            }

            "error-aware ensure passes Absent on success" in {
                var received: Maybe[Error[Any]] = Present(Panic(new Exception("sentinel")))
                Sync.ensure((ex: Maybe[Error[Any]]) => received = ex)(42).map { result =>
                    assert(received == Absent)
                    assert(result == 42)
                }
            }

            "error-aware ensure passes Present(Panic) on exception" in {
                var received: Maybe[Error[Any]] = Absent
                val ex                          = new RuntimeException("boom")
                Abort.run[Any](Sync.ensure((e: Maybe[Error[Any]]) => received = e) {
                    throw ex
                }).map { result =>
                    assert(received.isDefined)
                    assert(received.get == Panic(ex))
                }
            }

            "error-aware ensure passes error on Abort.fail" in {
                var received: Maybe[Error[Any]] = Absent
                Abort.run[String](Sync.ensure((e: Maybe[Error[Any]]) => received = e)(Abort.fail("boom"))).map { result =>
                    assert(result == Result.fail("boom"))
                    assert(received == Present(Result.Failure("boom")))
                }
            }

            "works without fiber context" in {
                import AllowUnsafe.embrace.danger
                var called = false
                val result = Sync.Unsafe.evalOrThrow(Sync.ensure { called = true }(42))
                assert(called)
                assert(result == 42)
            }

            "call-by-name regression (#1228)" in {
                var sideEffect = false
                val io         = Sync.ensure { sideEffect = true }(42)
                assert(!sideEffect)
                io.map { result =>
                    assert(sideEffect)
                    assert(result == 42)
                }
            }
        }

        // A handler resuming the same cont more than once replays the regions it carries. A bracket's
        // extent is over once the first resumption completes it, so the release would already have run
        // when the next arrives. Where the bracket sits decides the outcome.
        "under a handler that replays" - {

            "every branch of a replaying handler runs against the live resource, released once after all of them" in {
                for
                    released <- AtomicInt.init(0)
                    seen     <- AtomicRef.init(Chunk.empty[(Int, Int)])
                    body = Sync.ensure(released.incrementAndGet.unit) {
                        Choice.eval(1, 2).map(n => released.get.map(r => seen.updateAndGet(_.append((n, r))).andThen(n)))
                    }
                    res <- Abort.run[Closed](Choice.run(body))
                    r   <- released.get
                    s   <- seen.get
                yield
                    assert(res == Result.succeed(Chunk(1, 2)), s"$res")
                    assert(r == 1, s"released $r")
                    assert(s == Chunk((1, 0), (2, 0)), s"a branch did not run against a live resource: $s")
                end for
            }

            // handleCont holds the region it dumps: the release moves to the holder and runs once, after every
            // shot, so a clause that resumes twice runs both against the live resource with no refusal.
            "a replaying handler holds the region, releasing once after every shot" in {
                import kyo.kernel.ArrowEffect
                for
                    released <- AtomicInt.init(0)
                    body = (Sync.ensure(released.incrementAndGet.unit) {
                        ArrowEffect.suspend[Any](Tag[Replayed], ())
                    }: Int < (Replayed & Sync))
                    res <- Abort.run[Closed] {
                        ArrowEffect.handleCont[Const[Unit], Const[Int], Replayed, Int, Int, Sync, Any](Tag[Replayed], body)(
                            [C] => (_, cont) => cont(1).map(a => cont(2).map(b => a + b)),
                            a => a
                        )
                    }
                    r <- released.get
                yield
                    assert(res == Result.succeed(3), s"$res")
                    assert(r == 1, s"released $r")
                end for
            }

            "a bracket acquired inside each branch gives every branch a live resource of its own" in {
                for
                    released <- AtomicInt.init(0)
                    seen     <- AtomicRef.init(Chunk.empty[(Int, Int)])
                    body = Choice.eval(1, 2).map { n =>
                        Sync.ensure(released.incrementAndGet.unit)(released.get.map(r => seen.updateAndGet(_.append((n, r))).andThen(n)))
                    }
                    res <- Abort.run[Closed](Choice.run(body))
                    r   <- released.get
                    s   <- seen.get
                yield
                    assert(res == Result.succeed(Chunk(1, 2)), s"$res")
                    assert(r == 2, s"released $r")
                    assert(s == Chunk((1, 0), (2, 1)), s"a branch did not get its own resource: $s")
                end for
            }

            // The bracket's extent is the suspension itself, so it ends the moment the choice is
            // answered. Held, that ending only records: the release runs once, after every branch.
            "a bracket whose extent ends at the choice point still outlives every branch" in {
                for
                    released <- AtomicInt.init(0)
                    seen     <- AtomicRef.init(Chunk.empty[(Int, Int)])
                    body = Sync.ensure(released.incrementAndGet.unit)(Choice.eval(1, 2)).map { n =>
                        released.get.map(r => seen.updateAndGet(_.append((n, r))).andThen(n))
                    }
                    res <- Abort.run[Closed](Choice.run(body))
                    r   <- released.get
                    s   <- seen.get
                yield
                    assert(r == 1, s"released $r")
                    assert(s == Chunk((1, 0), (2, 0)), s"a branch observed its resource already released: $s")
                    assert(res == Result.succeed(Chunk(1, 2)), s"$res")
                end for
            }
        }

        // The fiber boundary answers a join in place, or parks at it carrying the region, rather than
        // handing a continuation out and moving the release onto the boundary.
        "whose use suspends on an async join releases at its own end" in {
            for
                released <- AtomicInt.init(0)
                _        <- Sync.ensure(released.incrementAndGet.unit)(Async.sleep(1.millis).andThen(Sync.defer(())))
                afterUse <- released.get
                _        <- Async.sleep(1.millis)
                total    <- released.get
            yield
                assert(afterUse == 1, s"released $afterUse right after the bracket's use completed")
                assert(total == 1, s"released $total in all")
            end for
        }
    }

    "acquireReleaseWith" - {
        "success" in {
            var order = List.empty[String]

            Sync.acquireReleaseWith(Sync.defer {
                order = order :+ "acquire"
                "resource"
            }) { resource =>
                Sync.defer {
                    order = order :+ s"release:$resource"
                }
            } { resource =>
                Sync.defer {
                    order = order :+ s"use:$resource"
                    resource.length
                }
            }.map { result =>
                assert(result == 8)
                assert(order == List("acquire", "use:resource", "release:resource"))
            }
        }

        // #1846: the use aborting typed, with no Abort.run inside.
        "releases when the use aborts with a typed error" in {
            var released = 0
            Abort.run[String] {
                Sync.acquireReleaseWith(Sync.defer("resource"))(_ => Sync.defer { released += 1 }) { _ =>
                    Abort.fail("boom")
                }
            }.map { result =>
                assert(result == Result.fail("boom"))
                assert(released == 1)
            }
        }

        "release after panic in use" in {
            val ex        = new RuntimeException("boom")
            var released  = false
            var useCalled = false

            Abort.run[Any] {
                Sync.acquireReleaseWith(Sync.defer("resource")) { _ =>
                    Sync.defer {
                        released = true
                    }
                } { _ =>
                    Sync.defer {
                        useCalled = true
                        throw ex
                    }
                }
            }.map { result =>
                assert(result == Result.panic(ex))
                assert(useCalled)
                assert(released)
            }
        }

        "does not release when acquire panics" in {
            val ex       = new RuntimeException("boom")
            var released = false

            Abort.run[Any] {
                Sync.acquireReleaseWith(Sync.defer[String, Any](throw ex)) { _ =>
                    Sync.defer {
                        released = true
                    }
                } { resource =>
                    resource
                }
            }.map { result =>
                assert(result == Result.panic(ex))
                assert(!released)
            }
        }
    }

    "evalOrThrow" - {
        import AllowUnsafe.embrace.danger
        "success" in {
            val result = Sync.Unsafe.evalOrThrow(Sync.defer(42))
            assert(result == 42)
        }

        "throws exceptions" in {
            val ex = new Exception("test error")
            val io = Sync.defer[Int, Any](throw ex)

            val caught = intercept[Exception] {
                Sync.Unsafe.evalOrThrow(io)
            }
            assert(caught == ex)
        }

        "propagates nested exceptions" in {
            val ex = new Exception("nested error")
            val io = Sync.defer(Sync.defer(throw ex))

            val caught = intercept[Exception] {
                Sync.Unsafe.evalOrThrow(io)
            }
            assert(caught == ex)
        }

        "works with mapped values" in {
            val result = Sync.Unsafe.evalOrThrow(Sync.defer(21).map(_ * 2))
            assert(result == 42)
        }
    }

    "abort" - {
        "Sync includes Abort[Nothing]" in {
            val a: Int < Abort[Nothing] = 1
            val b: Int < Sync           = a
            succeed("compile-time subtyping check: Abort[Nothing] <: Sync")
        }

        "does not include wider Abort types" in {
            typeCheckFailure("""
                val a: Int < Abort[String] = 1
                val b: Int < Sync            = a
            """)(
                "Required: Int < kyo.Sync"
            )
        }

        "preserves Nothing as most specific error type" in {
            typeCheckFailure("""
                val io: Int < Sync = Sync.defer {
                    Abort.fail[String]("error")
                }
            """)(
                "Required: Int < kyo.Sync"
            )
        }
    }

    "withLocal" - {
        "basic usage" in {
            val local      = Local.init("test")
            var sideEffect = ""

            Sync.withLocal(local) { value =>
                sideEffect = value
                value.length
            }.map { result =>
                assert(sideEffect == "test")
                assert(result == 4)
            }
        }

        "respects local modifications" in {
            val local    = Local.init("initial")
            var captured = ""

            local.let("modified") {
                Sync.withLocal(local) { value =>
                    captured = value
                    value.toUpperCase
                }
            }.map { result =>
                assert(captured == "modified")
                assert(result == "MODIFIED")
            }
        }

        "lazy evaluation" in {
            val local    = Local.init("test")
            var executed = false

            val computation =
                Sync.withLocal(local) { value =>
                    executed = true
                    value
                }

            assert(!executed)
            computation.map { result =>
                assert(executed)
                assert(result == "test")
            }
        }
    }

    "Unsafe.withLocal" - {

        def unsafeOperation(value: Int)(using unsafe: AllowUnsafe): Int =
            value * 2

        "allows unsafe operations" in {
            val local      = Local.init(42)
            var sideEffect = 0

            Sync.Unsafe.withLocal(local) { value =>
                sideEffect = unsafeOperation(value)
                sideEffect
            }.map { result =>
                assert(result == 84)
                assert(sideEffect == 84)
            }
        }

        "respects local context" in {
            val local    = Local.init(10)
            var captured = 0

            local.let(20) {
                Sync.Unsafe.withLocal(local) { value =>
                    captured = unsafeOperation(value)
                    value + 1
                }
            }.map { result =>
                assert(captured == 40)
                assert(result == 21)
            }
        }

        "composes with other unsafe operations" in {
            val local            = Local.init(5)
            var steps: List[Int] = Nil

            val computation =
                for
                    v1 <- Sync.Unsafe.withLocal(local) { value =>
                        steps = unsafeOperation(value) :: steps
                        value * 2
                    }
                    v2 <- Sync.Unsafe.defer {
                        steps = v1 :: steps
                        v1 + 1
                    }
                yield v2

            computation.map { result =>
                assert(steps == List(10, 10))
                assert(result == 11)
            }
        }
    }

    "ensure under interruption" - {

        // Cleanup that always occurs, for a computation that never got a slice. Holds only while nothing
        // deferred sits above the region, since the abandonment walk stops at one.
        "runs its finalizer for a fiber abandoned before its first slice" in {
            Async.foreachDiscard(1 to 20, 20) { _ =>
                for
                    ran   <- AtomicInt.init(0)
                    p     <- Promise.init[Int, Any]
                    fiber <- Fiber.initUnscoped {
                        import AllowUnsafe.embrace.danger
                        Sync.ensure(Sync.Unsafe.defer(discard(ran.unsafe.incrementAndGet())))(p.get)
                    }
                    _ <- fiber.interrupt
                    _ <- fiber.getResult
                    _ <- assertEventually(ran.get.map(_ == 1))
                    c <- ran.get
                yield assert(c == 1, s"the finalizer ran $c times")
                end for
            }.andThen(assert(true))
        }

        // The finalizer runs as the region ends, and the step that raises the recorded abort applies as the value
        // arrives, so a stop delivered while the finalizer runs, here requested by the finalizer itself, cannot
        // separate the region's clean end from the caller's `ensureMap`. A guard that hands its value on at a clean
        // end closes nothing, and the caller must own the value.
        "a caller's ensureMap after the region runs when the interrupt lands as the region ends" in {
            for
                handoff <- Promise.init[Fiber[Unit, Any], Any]
                ended   <- AtomicBoolean.init(false)
                owned   <- AtomicBoolean.init(false)
                fiber   <- Fiber.initUnscoped {
                    (handoff.get.map { self =>
                        Sync.ensure {
                            // Unsafe: the interrupt is requested from inside the finalizer, so the stop lands on
                            // the first poll after the region's end.
                            Sync.Unsafe.defer {
                                discard(self.unsafe.interrupt())
                                ended.unsafe.set(true)
                            }
                        }(Sync.defer("token")).ensureMap { _ =>
                            // Unsafe: the mark must land in the step that delivers the value, as a registration would; an
                            // effectful write would be a step of its own, parked by the same stop.
                            import AllowUnsafe.embrace.danger
                            owned.unsafe.set(true)
                            Async.never.andThen(Kyo.unit)
                        }
                    }): Unit < (Sync & Async)
                }
                _ <- handoff.complete(Result.succeed(fiber))
                _ <- fiber.getResult
                e <- ended.get
                o <- owned.get
            yield
                assert(e, "the finalizer did not run")
                assert(o, "the region ended cleanly and handed its value on, and the caller's ensureMap never owned it")
            end for
        }

        // An interrupt landing as the body produces its outcome: the body interrupts its own fiber, so
        // delivery lands at the next safepoint, after the body's step and before the region completes.
        "still runs the finalizer" in {
            for
                ran     <- AtomicInt.init(0)
                handoff <- Promise.init[Fiber[Unit, Any], Any]
                fiber   <- Fiber.initUnscoped {
                    handoff.get.map { self =>
                        Sync.ensure(ran.incrementAndGet.unit) {
                            Sync.defer {
                                // Unsafe: the interrupt has to be requested from inside the body, before its
                                // step ends, which is not an effectful position.
                                import AllowUnsafe.embrace.danger
                                discard(self.unsafe.interrupt())
                            }
                        }
                    }
                }
                _   <- handoff.complete(Result.succeed(fiber))
                res <- fiber.getResult
                _   <- assertEventually(ran.get.map(_ == 1))
                r   <- ran.get
            yield
                assert(res.isPanic, s"$res")
                assert(r == 1, s"the finalizer ran $r times")
            end for
        }

        "runs the finalizer exactly once when the body aborts" in {
            for
                ran     <- AtomicInt.init(0)
                handoff <- Promise.init[Fiber[Unit, Any], Any]
                fiber   <- Fiber.initUnscoped {
                    handoff.get.map { self =>
                        Abort.run[String] {
                            Sync.ensure(ran.incrementAndGet.unit) {
                                Sync.defer {
                                    // Unsafe: the interrupt has to be requested from inside the body, before
                                    // its step ends, which is not an effectful position.
                                    import AllowUnsafe.embrace.danger
                                    discard(self.unsafe.interrupt())
                                }.andThen(Abort.fail("boom"))
                            }
                        }.unit
                    }
                }
                _ <- handoff.complete(Result.succeed(fiber))
                _ <- fiber.getResult
                _ <- assertEventually(ran.get.map(_ == 1))
                r <- ran.get
            yield assert(r == 1, s"the finalizer ran $r times")
            end for
        }

        "a plain handler that resumes twice runs both shots against the live resource, released once" in {
            import kyo.kernel.ArrowEffect
            for
                released <- AtomicInt.init(0)
                seen     <- AtomicRef.init(Chunk.empty[Int])
                body = (Sync.ensure(released.incrementAndGet.unit) {
                    ArrowEffect.suspend[Any](Tag[Replayed], ()).map(n =>
                        released.get.map(r => seen.updateAndGet(_.append(r)).andThen(n))
                    )
                }: Int < (Replayed & Sync))
                res <- Abort.run[Closed] {
                    ArrowEffect.handleCont[Const[Unit], Const[Int], Replayed, Int, Int, Sync, Any](Tag[Replayed], body)(
                        [C] => (_, cont) => cont(1).map(a => cont(2).map(b => a + b)),
                        a => a
                    )
                }
                r <- released.get
                s <- seen.get
            yield
                assert(res == Result.succeed(3), s"$res")
                assert(r == 1, s"released $r")
                assert(s == Chunk(0, 0), s"a shot did not run against a live resource: $s")
            end for
        }

    }

end SyncTest
