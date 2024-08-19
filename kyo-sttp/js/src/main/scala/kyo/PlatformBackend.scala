package kyo

import kyo.Requests.Backend
import sttp.client3.*

object PlatformBackend:
    val default =
        new Backend:
            val b = FetchBackend()
            def send[A](r: Request[A, Any]) =
                Fiber.fromFuture(r.send(b)).map(_.get)
end PlatformBackend
