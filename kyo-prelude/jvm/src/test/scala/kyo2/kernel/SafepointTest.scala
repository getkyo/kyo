package kyo2.kernel

import kyo2.*
import kyo2.Tagged.*
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

end SafepointTest
