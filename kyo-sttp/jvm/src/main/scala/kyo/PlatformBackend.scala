package kyo

import java.net.http.HttpClient
import kyo.Requests.Backend
import kyo.internal.KyoSttpMonad
import sttp.capabilities.WebSockets
import sttp.client3.*

object PlatformBackend:

    def apply(backend: SttpBackend[KyoSttpMonad.M, WebSockets])(using Frame): Backend =
        new Backend:
            def send[A: Flat](r: Request[A, Any]) =
                r.send(backend)

    def apply(client: HttpClient)(using Frame): Backend =
        apply(HttpClientKyoBackend.usingClient(client))

    val default =
        new Backend:
            val b = HttpClientKyoBackend()
            def send[A: Flat](r: Request[A, Any]) =
                r.send(b)
end PlatformBackend
