package kyo

import kyo.Requests.Backend
import sttp.client3.*

object PlatformBackend:
    val default =
        new Backend:
            val b = FetchBackend()
            def send[A: Flat](r: Request[A, Any]) =
                given Frame = Frame.internal
                Abort.run(Async.fromFuture(r.send(b)))
                    .map(_.foldError(ex => Abort.fail(FailedRequest(ex.failureOrPanic)))(identity))
            end send
end PlatformBackend
