package kyo.internal

import kyo.*
import scala.concurrent.Future

private[kyo] trait BaseKyoCoreTest extends BaseKyoKernelTest[Abort[Any] & Async & Scope]:
    def run(v: Future[Assertion] < (Abort[Any] & Async & Scope))(using Frame): Future[Assertion] =
        import AllowUnsafe.embrace.danger
        v.handle(
            Scope.run,
            Abort.recover[Any] {
                case ex: Throwable => throw ex
                case e             => throw new IllegalStateException(s"Test aborted with $e")
            },
            Async.timeout(timeout),
            Fiber.initUnscoped,
            _.map(_.toFuture).map(_.flatten),
            Sync.Unsafe.evalOrThrow
        )
    end run

    def untilTrue[S](f: => Boolean < S)(using Frame): Unit < (Async & S) =
        Abort.recover(Abort.panic) {
            Retry[AssertionError](Schedule.fixed(10.millis)) {
                f.map {
                    case false => throw new AssertionError("untilTrue condition failed")
                    case true  =>
                }
            }
        }
    end untilTrue
end BaseKyoCoreTest
