package kyo2.internal

import kyo2.*
import kyo2.kernel.Platform
import scala.annotation.targetName
import scala.concurrent.Future
import scala.util.Try

trait BaseKyoTest[S]:

    type Assertion

    def success: Assertion

    def run(v: Future[Assertion] < S): Future[Assertion]

    @targetName("runAssertion")
    def run(v: Assertion < S): Future[Assertion] = run(v.map(Future.successful(_)))

    def runJVM(v: => Future[Assertion] < S): Future[Assertion] =
        if Platform.isJVM then
            run(v)
        else
            Future.successful(success)

    def runJS(v: => Future[Assertion] < S): Future[Assertion] =
        if Platform.isJS then
            run(v)
        else
            Future.successful(success)

    given tryCanEqual[T]: CanEqual[Try[T], Try[T]]                   = CanEqual.derived
    given eitherCanEqual[T, U]: CanEqual[Either[T, U], Either[T, U]] = CanEqual.derived
    given throwableCanEqual: CanEqual[Throwable, Throwable]          = CanEqual.derived

    def timeout =
        if Platform.isDebugEnabled then
            Duration.Infinity
        else
            5.seconds

end BaseKyoTest
