package kyo.test

import kyo.*
import org.scalatest.Assertion
import org.scalatest.Suite
import scala.concurrent.Future

trait KyoScalatestApi extends KyoTestApiSync[Assertion] with KyoTestApiAsync[Future[Assertion]] with KyoTestApiSpecialAssertion[Assertion]:
    self: Suite =>

    type Assert = KyoAssert.Assert

    override inline def assertKyo(inline condition: Boolean)(using Frame): Unit < Assert =
        KyoAssert.get(assert(condition))

    override def assertKyo(assertion: => Assertion)(using f: Frame): Unit < Assert =
        KyoAssert.get(assertion)

    override def runKyoSync(effect: Any < (Assert & Memo & Abort[Any] & IO))(using Frame): Assertion =
        import AllowUnsafe.embrace.danger

        effect.handle(
            KyoAssert.run,
            Memo.run,
            Abort.run[Any](_)
        ).map {
            case Result.Success(_)              => succeed
            case Result.Failure(thr: Throwable) => IO(throw thr)
            case Result.Failure(e)              => IO(fail(t"Test failed with Abort: $e".toString))
            case Result.Panic(thr)              => IO(throw thr)
        }.handle(
            IO.Unsafe.evalOrThrow
        )
    end runKyoSync

    override def runKyoAsync(effect: Any < (Assert & Memo & Resource & Abort[Any] & Async))(using Frame): Future[Assertion] =
        import AllowUnsafe.embrace.danger

        effect.handle(
            KyoAssert.run,
            Resource.run,
            Memo.run,
            Abort.run
        ).map {
            case Result.Success(_)              => succeed
            case Result.Failure(thr: Throwable) => IO(throw thr)
            case Result.Failure(e)              => IO(fail(t"Test failed with Abort: $e".toString))
            case Result.Panic(thr)              => IO(throw thr)
        }.handle(
            Async.run(_),
            _.map(_.toFuture),
            IO.Unsafe.evalOrThrow
        )
    end runKyoAsync
end KyoScalatestApi
