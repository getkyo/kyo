package kyo

import kyo.Requests.Backend
import sttp.client3.*

object PlatformBackend:
    val default =
        new Backend:
            val b = CurlBackend()
            def send[A: Flat](r: Request[A, Any]) =
                given Frame = Frame.internal
                def call    = r.send(b)
                Abort.run[Throwable](IO(call))
                    .map(_.fold(ex => Abort.fail(FailedRequest(ex.getFailure)))(identity))
            end send
end PlatformBackend
