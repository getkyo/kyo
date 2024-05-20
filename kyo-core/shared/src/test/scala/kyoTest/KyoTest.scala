package kyoTest

import kyo.*
import org.scalatest.NonImplicitAssertions
import org.scalatest.compatible.Assertion
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.language.implicitConversions
import scala.util.Try

class KyoTest extends AsyncFreeSpec with NonImplicitAssertions:

    override given executionContext: ExecutionContext = Platform.executionContext

    given tryCanEqual[T]: CanEqual[Try[T], Try[T]]                   = CanEqual.derived
    given eitherCanEqual[T, U]: CanEqual[Either[T, U], Either[T, U]] = CanEqual.derived
    given throwableCanEqual: CanEqual[Throwable, Throwable]          = CanEqual.derived

    def retry[S](f: => Boolean < S): Boolean < S =
        def loop(): Boolean < S =
            f.map {
                case true  => true
                case false => loop()
            }
        loop()
    end retry

    def timeout =
        if Platform.isDebugEnabled then
            Duration.Infinity
        else
            5.seconds

    implicit def toFuture(a: Assertion): Future[Assertion] = Future.successful(a)

    def runJVM(
        v: => Assertion < KyoApp.Effects
    ): Future[Assertion] =
        if Platform.isJVM then
            run(v)
        else
            Future.successful(succeed)

    def runJS(
        v: => Assertion < KyoApp.Effects
    ): Future[Assertion] =
        if Platform.isJS then
            run(v)
        else
            Future.successful(succeed)

    def run(
        v: => Assertion < KyoApp.Effects
    ): Future[Assertion] =
        IOs.run(KyoApp.runFiber(timeout)(v).toFuture).map(_.get)
end KyoTest
