package kyo.test

import kyo.*
import org.scalatest.exceptions.TestCanceledException
import org.scalatest.exceptions.TestFailedException

private[test] object KyoAssert:
    opaque type Assert = Abort[Failure] & IO

    sealed private[test] case class Failure(inner: TestFailedException | TestCanceledException)

    def run[A, S](effect: A < (Assert & IO & S))(using SafeClassTag[Failure], Frame): A < (IO & S) =
        Abort.runPartial[Failure](effect).map:
            case Result.Success(a)   => a
            case Result.Failure(exc) => IO(throw exc.inner)

    inline def get(inline block: => org.scalatest.Assertion)(using Frame): Unit < Assert =
        IO:
            try
                val _ = block
            catch
                case e: (TestFailedException | TestCanceledException) => Abort.fail(Failure(e))
                case _                                                => ()

end KyoAssert
