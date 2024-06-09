package kyoTest.internal

import kyo.*
import kyo.internal.KyoSttpMonad
import kyoTest.KyoTest
import scala.util.Failure
import sttp.monad.Canceler

class KyoSttpMonadTest extends KyoTest:

    "map" in run {
        KyoSttpMonad.map(IOs(1))(_ + 1).map(r => assert(r == 2))
    }

    "flatMap" in run {
        KyoSttpMonad.flatMap(IOs(1))(v => IOs(v + 1)).map(r => assert(r == 2))
    }

    "handleError" - {
        "ok" in run {
            KyoSttpMonad.handleError(IOs(1))(_ => 2).map(r => assert(r == 1))
        }
        "nok" in run {
            KyoSttpMonad.handleError(IOs.fail(new Exception))(_ => 2).map(r => assert(r == 2))
        }
    }

    "ensure" in run {
        var calls = 0
        KyoSttpMonad.ensure(IOs(1), IOs(calls += 1)).map { r =>
            assert(r == 1)
            assert(calls == 1)
        }
    }

    "error" in run {
        val ex = new Exception
        IOs.toTry(KyoSttpMonad.error(ex)).map(r => assert(r == Failure(ex)))
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
            IOs.toTry(KyoSttpMonad.eval(throw ex)).map(r => assert(r == Failure(ex)))
        }
    }

    "suspend" - {
        "ok" in run {
            KyoSttpMonad.suspend(1).map(r => assert(r == 1))
        }
        "nok" in run {
            val ex = new Exception
            IOs.toTry(KyoSttpMonad.suspend(throw ex)).map(r => assert(r == Failure(ex)))
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
            IOs.toTry(result).map(r => assert(r == Failure(ex)))
        }

        "cancel" in run {
            var cancelled = false
            val result = KyoSttpMonad.async[Int] { cb =>
                Canceler(() => cancelled = true)
            }
            Fibers.run(result).map(_.interrupt).map { interrupted =>
                assert(interrupted)
                assert(cancelled)
            }
        }
    }

end KyoSttpMonadTest
