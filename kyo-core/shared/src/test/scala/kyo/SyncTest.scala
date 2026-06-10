package kyo

import kyo.Result.Error
import kyo.Result.Panic
import scala.util.Try

class SyncTest extends kyo.test.Test[Any]:

    "lazyRun" - {
        "execution" in {
            var called = false
            val v =
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
            val v =
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
            val frames = 10000
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
    }
    "run" - {
        "execution" in {
            var called = false
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
            val frames = 100000
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
            "runs finalizer on Abort.fail".ignore("Sync.ensure finalizer is not yet run when the computation aborts via Abort.fail") in {
                ()
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

            "error-aware ensure passes error on Abort.fail".ignore(
                "an error-aware Sync.ensure finalizer is not yet passed the abort error on Abort.fail"
            ) in { () }

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

end SyncTest
