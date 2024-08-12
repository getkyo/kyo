package kyo

import kyo.Request.Backend
import sttp.client3.*

object PlatformBackend:
    val default =
        new Backend:
            val b = FetchBackend()
            def send[T](r: Request[T, Any]) =
                Fiber.fromFuture(r.send(b)).map(_.get)
end PlatformBackend
