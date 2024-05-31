package kyo.internal

import kyo.*
import scala.concurrent.Future
import scala.language.implicitConversions
import scala.util.Try

trait BaseKyoTest:

    type Assertion

    def success: Assertion

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
        if kyo.internal.Platform.isDebugEnabled then
            Duration.Infinity
        else
            5.seconds

    // given Conversion[Assertion, Future[Assertion]] = Future.successful(_)

    def runJVM(
        v: => Assertion < KyoApp.Effects
    )(using Flat[Assertion]): Future[Assertion] =
        if kyo.internal.Platform.isJVM then
            run(v)
        else
            Future.successful(success)

    def runJS(
        v: => Assertion < KyoApp.Effects
    )(using Flat[Assertion]): Future[Assertion] =
        if kyo.internal.Platform.isJS then
            run(v)
        else
            Future.successful(success)

    def run(
        v: => Assertion < KyoApp.Effects
    )(using Flat[Assertion]): Future[Assertion] =
        IOs.run(KyoApp.runFiber(timeout)(v).toFuture).map(_.get)(using Platform.executionContext)

end BaseKyoTest
