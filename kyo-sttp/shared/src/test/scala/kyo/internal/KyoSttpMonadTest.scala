package kyo.internal

import kyo.*
import org.scalatest.concurrent.Eventually.*
import sttp.monad.Canceler

class KyoSttpMonadTest extends Test:

    "map" in run {
        KyoSttpMonad.map(IO(1))(_ + 1).map(r => assert(r == 2))
    }

    "flatMap" in run {
        KyoSttpMonad.flatMap(IO(1))(v => IO(v + 1)).map(r => assert(r == 2))
    }

    "handleError" - {
        "ok" in run {
            KyoSttpMonad.handleError(IO(1))(_ => 2).map(r => assert(r == 1))
        }
        "nok" in run {
            KyoSttpMonad.handleError(IO(throw new Exception))(_ => 2).map(r => assert(r == 2))
        }
    }

    "ensure" in run {
        var calls = 0
        KyoSttpMonad.ensure(IO(1), IO(calls += 1)).map { r =>
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
            val result = KyoSttpMonad.async[Int] { cb =>
                cb(Right(42))
                Canceler(() => {})
            }
            result.map(r => assert(r == 42))
        }

        "nok" in run {
            val ex = new Exception("test")
            val result = KyoSttpMonad.async[Int] { cb =>
                cb(Left(ex))
                Canceler(() => {})
            }
            Abort.run[Throwable](result).map(r => assert(r == Result.panic(ex)))
        }

        "cancel" in runJVM {
            var cancelled = false
            val result = KyoSttpMonad.async[Int] { cb =>
                cb(Left(new Exception))
                Canceler(() => cancelled = true)
            }
            Async.run(result).map(_.getResult).map { _ =>
                assert(cancelled)
            }
        }
    }

end KyoSttpMonadTest
