package kyo

import sttp.client3.*
import kyo.Requests.Backend

object PlatformBackend:
    val default =
        new Backend:
            val b = FetchBackend()
            def send[T](r: Request[T, Any]) =
                Fibers.fromFuture(r.send(b))
end PlatformBackend
