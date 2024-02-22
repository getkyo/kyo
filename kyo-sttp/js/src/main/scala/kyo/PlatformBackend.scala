package kyo

import kyo.Requests.Backend
import sttp.client3.*

object PlatformBackend:
    val default =
        new Backend:
            val b = FetchBackend()
            def send[T](r: Request[T, Any]) =
                Fibers.fromFuture(r.send(b))
end PlatformBackend
