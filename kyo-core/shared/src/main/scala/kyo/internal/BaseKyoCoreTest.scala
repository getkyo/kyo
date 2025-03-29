package kyo.internal

import kyo.*
import scala.concurrent.Future

private[kyo] trait BaseKyoCoreTest extends BaseKyoKernelTest[Abort[Any] & Async & Resource]:
    def run(v: Future[Assertion] < (Abort[Any] & Async & Resource)): Future[Assertion] =
        import AllowUnsafe.embrace.danger
        v.handle(
            Resource.run,
            Abort.recover[Any] {
                case ex: Throwable => throw ex
                case e             => throw new IllegalStateException(s"Test aborted with $e")
            },
            Async.timeout(timeout),
            Async.run,
            _.map(_.toFuture).map(_.flatten),
            IO.Unsafe.evalOrThrow
        )
    end run
end BaseKyoCoreTest
