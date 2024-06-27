package kyo2.kernel

import kyo2.*
import kyo2.Tagged.*
import scala.collection.mutable.Queue
import scala.collection.mutable.Stack
import scala.concurrent.Await
import scala.concurrent.Future

class SafepointTest extends Test:

    def fork[A](f: => A < Any): A < Any =
        Effect.defer {
            val future = Future(f.eval)
            Await.result(future, timeout.toScala): A
        }

    "does not allow capturing across threads" in {
        val computation = Effect.defer {
            val runtime = summon[Safepoint]
            fork {
                Effect.defer {
                    val newSafepoint = summon[Safepoint]
                    assert(runtime ne newSafepoint)
                }
            }
        }

        computation.eval
    }

    "allows resuming in the same thread" in {
        val computation = Effect.defer {
            val runtime = summon[Safepoint]
            Effect.defer {
                val sameSafepoint = summon[Safepoint]
                assert(runtime eq sameSafepoint)
            }
        }

        computation.eval
    }

    "suspends when Safepoint is from a different thread" in {
        var capturedSafepoint: Safepoint = null
        val computation = Effect.defer {
            capturedSafepoint = summon[Safepoint]
            ()
        }

        computation.eval

        val differentThreadComputation = fork {
            Effect.defer {
                val currentSafepoint = summon[Safepoint]
                assert(capturedSafepoint ne currentSafepoint)
            }
        }

        differentThreadComputation.eval
    }

    "handles nested deferrals correctly" in {
        val computation = Effect.defer {
            val outerSafepoint = summon[Safepoint]
            Effect.defer {
                val innerSafepoint = summon[Safepoint]
                assert(outerSafepoint eq innerSafepoint)
            }
        }

        computation.eval
    }

    "propagates Safepoint through flatMap" in {
        val computation = Effect.defer {
            val initialSafepoint = summon[Safepoint]
            Effect.defer {
                42
            }.flatMap { value =>
                Effect.defer {
                    val laterSafepoint = summon[Safepoint]
                    assert(initialSafepoint eq laterSafepoint)
                    assert(value == 42)
                }
            }
        }

        computation.eval
    }

    "maintains different Safepoints across forks" in {
        val computation = Effect.defer {
            val runtime1 = summon[Safepoint]
            fork {
                Effect.defer {
                    val runtime2 = summon[Safepoint]
                    assert(runtime1 ne runtime2)
                }
            }
        }

        computation.eval
    }

    "no leak between forked executions" in run {
        var capturedSafepoint: Safepoint = null

        val computation1 = Effect.defer {
            capturedSafepoint = Safepoint.get
            42
        }

        val computation2 = Effect.defer {
            val currentSafepoint = Safepoint.get
            assert(currentSafepoint ne capturedSafepoint)
            84
        }

        for
            _      <- fork(computation1.eval)
            result <- computation2
        yield assert(result == 84)
        end for
    }

    "new Safepoint for nested eval calls" in run {
        val outerComputation = Effect.defer {
            val outerSafepoint = Safepoint.get
            val innerComputation = Effect.defer {
                val innerSafepoint = Safepoint.get
                assert(innerSafepoint ne outerSafepoint)
                21
            }
            innerComputation.eval * 2
        }

        outerComputation.map(result => assert(result == 42))
    }

    "capture Safepoint in closures" in run {
        var capturedSafepoint: Safepoint = null

        val computation = Effect.defer {
            capturedSafepoint = Safepoint.get
            () => Safepoint.get // Return a closure that accesses Safepoint
        }

        val closure = computation.eval

        Effect.defer {
            val currentSafepoint = Safepoint.get
            assert(currentSafepoint ne capturedSafepoint)
            assert(closure() eq currentSafepoint)
        }
    }

    "forced runtime leak" in {
        val runtime = Safepoint.get

        val res =
            fork {
                given Safepoint = runtime
                (1: Int < Any).map(_ + 1).map(_ + 2)
            }

        assert(res.eval == 4)
    }

    "forced runtime leak + eval" in {
        val runtime = Safepoint.get

        val res =
            fork {
                given Safepoint = runtime
                (1: Int < Any).map(_ + 1).map(_ + 2).eval
            }

        assert(res.eval == 4)
    }

    "interceptors" - {
        "immediate" - {
            "use the interceptor" in {
                var executed = false
                val interceptor = new Safepoint.Interceptor:
                    def ensure(f: () => Unit): Unit = ()
                    def enter(frame: Frame, value: Any): Boolean =
                        executed = true
                        true
                    def exit(): Unit = ()

                Safepoint.immediate(interceptor)((1: Int < Any).map(_ + 1).eval)
                assert(executed)
            }

            "restore previous interceptor" in {
                var count = 0
                val interceptor1 = new Safepoint.Interceptor:
                    def ensure(f: () => Unit): Unit = ()
                    def enter(frame: Frame, value: Any): Boolean =
                        count += 1
                        true
                    def exit(): Unit = ()

                val interceptor2 = new Safepoint.Interceptor:
                    def ensure(f: () => Unit): Unit = ()
                    def enter(frame: Frame, value: Any): Boolean =
                        count += 10
                        true
                    def exit(): Unit = ()

                Safepoint.immediate(interceptor1) {
                    Safepoint.immediate(interceptor2)((1: Int < Any).map(_ + 1).eval)
                }.eval

                assert(count == 11)
            }
        }

        "propagating" - {
            "through suspensions" in {
                var count = 0
                val interceptor = new Safepoint.Interceptor:
                    def ensure(f: () => Unit): Unit = ()
                    def enter(frame: Frame, value: Any): Boolean =
                        count += 1
                        true
                    def exit(): Unit = ()

                def suspendingFunction(): Int < Any =
                    Effect.defer(42).map(_ + 1)

                Safepoint.propagating(interceptor) {
                    for
                        _ <- suspendingFunction()
                        _ <- suspendingFunction()
                    yield 42
                }.eval

                assert(count == 6)
            }

            "restores previous interceptor after completion" in {
                var outerCount = 0
                var innerCount = 0

                val outerInterceptor = new Safepoint.Interceptor:
                    def ensure(f: () => Unit): Unit = ()
                    def enter(frame: Frame, value: Any): Boolean =
                        outerCount += 1
                        true
                    def exit(): Unit = ()

                val innerInterceptor = new Safepoint.Interceptor:
                    def ensure(f: () => Unit): Unit = ()
                    def enter(frame: Frame, value: Any): Boolean =
                        innerCount += 1
                        true
                    def exit(): Unit = ()

                def suspendingFunction(): Int < Any =
                    Effect.defer(42).map(_ + 1)

                Safepoint.immediate(outerInterceptor) {
                    Safepoint.propagating(innerInterceptor) {
                        for
                            _ <- suspendingFunction()
                            _ <- suspendingFunction()
                        yield 42
                    }.map(_ => suspendingFunction())
                }.eval

                assert(outerCount == 0)
                assert(innerCount == 6)
            }
        }

        "example logging interceptor" in {
            import scala.collection.mutable.ArrayBuffer

            class LoggingInterceptor extends Safepoint.Interceptor:
                val logs = ArrayBuffer.empty[String]

                def enter(frame: Frame, value: Any): Boolean =
                    val parsed = frame.parse
                    logs += s"Entering ${parsed.methodName} with value: $value"
                    true
                end enter

                def exit(): Unit =
                    logs += "Exiting method"
            end LoggingInterceptor

            val interceptor = new LoggingInterceptor()

            def computation(x: Int): Int < Any =
                Effect.defer {
                    x + 1
                }.map { y =>
                    Effect.defer {
                        y * 2
                    }
                }

            val result = Safepoint.propagating(interceptor) {
                for
                    a <- computation(5)
                    b <- computation(a)
                yield b
            }.eval

            assert(result == 26)

            val expectedLogs = Seq(
                "Entering apply with value: ()",
                "Exiting method",
                "Entering computation with value: 6",
                "Exiting method",
                "Entering apply with value: ()",
                "Exiting method",
                "Entering Unknown with value: 12",
                "Exiting method",
                "Entering apply with value: ()",
                "Exiting method",
                "Entering computation with value: 13",
                "Exiting method",
                "Entering apply with value: ()",
                "Exiting method",
                "Entering $anonfun with value: 26",
                "Exiting method"
            )

            assert(interceptor.logs == expectedLogs)
        }

        "example wall-clock profiling interceptor" in {
            import scala.collection.mutable.Stack

            class ProfilingInterceptor extends Safepoint.Interceptor:
                val starts = Stack.empty[(Frame, Long)]
                var log    = Stack.empty[(Frame, Long)]

                def enter(frame: Frame, value: Any): Boolean =
                    starts.push((frame, System.nanoTime()))
                    true

                def exit(): Unit =
                    val (frame, start) = starts.pop()
                    log.push((frame, System.nanoTime() - start))
            end ProfilingInterceptor

            val interceptor = new ProfilingInterceptor

            def computation(x: Int): Int < Any =
                Effect.defer {
                    Thread.sleep(1)
                    x + 1
                }.map { y =>
                    Effect.defer {
                        Thread.sleep(1)
                        y * 2
                    }
                }

            val result = Safepoint.propagating(interceptor) {
                for
                    a <- computation(5)
                    b <- computation(a)
                yield b
            }.eval

            assert(result == 26)

            interceptor.log.foreach { case (frame, duration) =>
                assert(duration > 0)
                assert(duration < 3000000)
            }

            assert(interceptor.log.size == 8)
            assert(interceptor.log.exists(_._1.parse.methodName == "computation"))
        }
    }

end SafepointTest
