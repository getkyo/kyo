package kyo2.kernel

import kyo2.*
import kyo2.Tagged.*
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.*

class RuntimeTest extends Test:

    def fork[A](f: => A < Any): A < Any =
        Effect.defer {
            val future = Future(f.eval)
            Await.result(future, timeout.toScala): A
        }

    "does not allow capturing across threads" in {
        val computation = Effect.defer {
            val runtime = summon[Runtime]
            fork {
                Effect.defer {
                    val newRuntime = summon[Runtime]
                    assert(runtime ne newRuntime)
                }
            }
        }

        computation.eval
    }

    "allows resuming in the same thread" in {
        val computation = Effect.defer {
            val runtime = summon[Runtime]
            Effect.defer {
                val sameRuntime = summon[Runtime]
                assert(runtime eq sameRuntime)
            }
        }

        computation.eval
    }

    "suspends when Runtime is from a different thread" in {
        var capturedRuntime: Runtime = null
        val computation = Effect.defer {
            capturedRuntime = summon[Runtime]
            ()
        }

        computation.eval

        val differentThreadComputation = fork {
            Effect.defer {
                val currentRuntime = summon[Runtime]
                assert(capturedRuntime ne currentRuntime)
            }
        }

        differentThreadComputation.eval
    }

    "handles nested deferrals correctly" in {
        val computation = Effect.defer {
            val outerRuntime = summon[Runtime]
            Effect.defer {
                val innerRuntime = summon[Runtime]
                assert(outerRuntime eq innerRuntime)
            }
        }

        computation.eval
    }

    "propagates Runtime through flatMap" in {
        val computation = Effect.defer {
            val initialRuntime = summon[Runtime]
            Effect.defer {
                42
            }.flatMap { value =>
                Effect.defer {
                    val laterRuntime = summon[Runtime]
                    assert(initialRuntime eq laterRuntime)
                    assert(value == 42)
                }
            }
        }

        computation.eval
    }

    "maintains different Runtimes across forks" in {
        val computation = Effect.defer {
            val runtime1 = summon[Runtime]
            fork {
                Effect.defer {
                    val runtime2 = summon[Runtime]
                    assert(runtime1 ne runtime2)
                }
            }
        }

        computation.eval
    }

    "no leak between forked executions" in run {
        var capturedRuntime: Runtime = null

        val computation1 = Effect.defer {
            capturedRuntime = Runtime.get
            42
        }

        val computation2 = Effect.defer {
            val currentRuntime = Runtime.get
            assert(currentRuntime ne capturedRuntime)
            84
        }

        for
            _      <- fork(computation1.eval)
            result <- computation2
        yield assert(result == 84)
        end for
    }

    "new Runtime for nested eval calls" in run {
        val outerComputation = Effect.defer {
            val outerRuntime = Runtime.get
            val innerComputation = Effect.defer {
                val innerRuntime = Runtime.get
                assert(innerRuntime ne outerRuntime)
                21
            }
            innerComputation.eval * 2
        }

        outerComputation.map(result => assert(result == 42))
    }

    "capture Runtime in closures" in run {
        var capturedRuntime: Runtime = null

        val computation = Effect.defer {
            capturedRuntime = Runtime.get
            () => Runtime.get // Return a closure that accesses Runtime
        }

        val closure = computation.eval

        Effect.defer {
            val currentRuntime = Runtime.get
            assert(currentRuntime ne capturedRuntime)
            assert(closure() eq currentRuntime)
        }
    }

    "forced runtime leak" in {
        val runtime = Runtime.get

        val res =
            fork {
                given Runtime = runtime
                (1: Int < Any).map(_ + 1).map(_ + 2)
            }

        assert(res.eval == 4)
    }

    "forced runtime leak + eval" in {
        val runtime = Runtime.get

        val res =
            fork {
                given Runtime = runtime
                (1: Int < Any).map(_ + 1).map(_ + 2).eval
            }

        assert(res.eval == 4)
    }

end RuntimeTest
