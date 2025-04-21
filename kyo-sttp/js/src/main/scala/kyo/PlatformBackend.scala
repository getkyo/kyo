package kyo

import kyo.Requests.Backend
import sttp.client3.*

object PlatformBackend:
    val default =
        new Backend:
            val b = FetchBackend()
            def send[A](r: Request[A, Any]) =
                given Frame = Frame.internal
                Abort.run(Async.fromFuture(r.send(b)))
                    .map(_.foldError(identity, ex => Abort.fail(FailedRequest(ex.failureOrPanic))))
            end send
end PlatformBackend
