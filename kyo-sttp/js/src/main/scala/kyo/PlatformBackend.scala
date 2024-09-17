package kyo

import kyo.Requests.Backend
import sttp.client3.*

object PlatformBackend:
    val default =
        new Backend:
            val b = FetchBackend()
            def send[A](r: Request[A, Any]) =
                given Frame = Frame.internal
                Abort.run(Fiber.fromFuture[Throwable, Response[A]](r.send(b)).map(_.get))
                    .map(_.fold(ex => Abort.fail(FailedRequest(ex.getFailure)))(identity))
            end send
end PlatformBackend
