package kyo.internal

import kyo.*

private[kyo] object HttpPlatformBackend:
    lazy val client: HttpBackend.Client            = new CurlClientBackend(daemon = true)
    lazy val server: HttpBackend.Server            = new H2oServerBackend
    lazy val wsClient: HttpBackend.WebSocketClient = new WebSocketClientNotImplemented
end HttpPlatformBackend

private class WebSocketClientNotImplemented extends HttpBackend.WebSocketClient:
    def connect[A, S](
        host: String,
        port: Int,
        path: String,
        ssl: Boolean,
        headers: HttpHeaders,
        config: WebSocketConfig
    )(f: WebSocket => A < S)(using Frame): A < (S & Async & Abort[HttpException]) =
        throw new UnsupportedOperationException("WebSocket client not yet implemented for this platform")
end WebSocketClientNotImplemented
