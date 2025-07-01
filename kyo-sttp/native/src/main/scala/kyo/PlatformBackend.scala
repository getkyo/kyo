package kyo

import kyo.Requests.Backend
import sttp.client3.*

object PlatformBackend:
    val default =
        new Backend:
            val b = CurlBackend()
            def send[A](r: Request[A, Any]) =
                given Frame = Frame.internal
                def call    = r.send(b)
                Abort.run[Throwable](Sync(call))
                    .map(_.foldError(identity, ex => Abort.fail(FailedRequest(ex.failureOrPanic))))
            end send
end PlatformBackend
