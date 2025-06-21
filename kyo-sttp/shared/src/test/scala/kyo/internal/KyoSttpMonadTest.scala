package kyo.internal

import kyo.*
import org.scalatest.concurrent.Eventually.*
import sttp.monad.Canceler

class KyoSttpMonadTest extends Test:

    "map" in run {
        KyoSttpMonad.map(Sync(1))(_ + 1).map(r => assert(r == 2))
    }

    "flatMap" in run {
        KyoSttpMonad.flatMap(Sync(1))(v => Sync(v + 1)).map(r => assert(r == 2))
    }

    "handleError" - {
        "ok" in run {
            KyoSttpMonad.handleError(Sync(1))(_ => 2).map(r => assert(r == 1))
        }
        "nok" in run {
            KyoSttpMonad.handleError(Sync(throw new Exception))(_ => 2).map(r => assert(r == 2))
        }
    }

    "ensure" in run {
        var calls = 0
        KyoSttpMonad.ensure(Sync(1), Sync(calls += 1)).map { r =>
            assert(r == 1)
            assert(calls == 1)
        }
    }

    "error" in run {
        val ex = new Exception
        Abort.run[Throwable](KyoSttpMonad.error(ex)).map(r => assert(r == Result.fail(ex)))
    }

    "unit" in {
        val x = new Object
        assert(KyoSttpMonad.unit(x).equals(x))
    }

    "eval" - {
        "ok" in run {
            KyoSttpMonad.eval(1).map(r => assert(r == 1))
        }
        "nok" in run {
            val ex = new Exception
            Abort.run[Throwable](KyoSttpMonad.eval(throw ex)).map(r => assert(r == Result.fail(ex)))
        }
    }

    "suspend" - {
        "ok" in run {
            KyoSttpMonad.suspend(1).map(r => assert(r == 1))
        }
        "nok" in run {
            val ex = new Exception
            Abort.run[Throwable](KyoSttpMonad.suspend(throw ex)).map(r => assert(r == Result.fail(ex)))
        }
    }

    "async" - {
        "ok" in run {
            var interrupted = false
            KyoSttpMonad.async[Int] { cb =>
                cb(Right(42))
                Canceler(() => interrupted = true)
            }.map { r =>
                assert(!interrupted)
                assert(r == 42)
            }
        }

        "nok" in run {
            var interrupted = false
            val ex          = new Exception("test")
            val result = KyoSttpMonad.async[Int] { cb =>
                cb(Left(ex))
                Canceler(() => interrupted = true)
            }
            Abort.run[Throwable](result).map { r =>
                assert(!interrupted)
                assert(r == Result.panic(ex))
            }
        }

        "cancel" in runJVM {
            for
                started   <- Latch.init(1)
                cancelled <- Latch.init(1)
                fiber <- Async.run {
                    KyoSttpMonad.async[Int] { _ =>
                        import AllowUnsafe.embrace.danger
                        started.unsafe.release()
                        Canceler(() => cancelled.unsafe.release())
                    }
                }
                _           <- started.await
                _           <- Async.sleep(10.millis)
                interrupted <- fiber.interrupt
                _           <- cancelled.await
                result      <- fiber.getResult
            yield
                assert(interrupted)
                assert(result.isPanic)
            end for
        }
    }

end KyoSttpMonadTest
