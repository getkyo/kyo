package kyo

import kyo.internal.CurlBindings
import kyo.internal.CurlEventLoop

/** Native backend using libcurl for HTTP transport. Client-only — server is not supported. */
object CurlBackend extends Backend:

    locally { discard(CurlBindings.curl_global_init(CurlBindings.CURL_GLOBAL_DEFAULT)) }

    def connectionFactory(maxResponseSizeBytes: Int, daemon: Boolean)(using AllowUnsafe): Backend.ConnectionFactory =
        val eventLoop = new CurlEventLoop(daemon)
        new Backend.ConnectionFactory:

            def connect(host: String, port: Int, ssl: Boolean, connectTimeout: Maybe[Duration])(using
                Frame
            ): Backend.Connection < (Async & Abort[HttpError]) =
                new CurlConnection(host, port, ssl, eventLoop, maxResponseSizeBytes, connectTimeout)

            def close(gracePeriod: Duration)(using Frame): Unit < Async =
                Sync.defer(eventLoop.shutdown())
        end new
    end connectionFactory

    def server(config: HttpServer.Config, handler: Backend.ServerHandler)(using Frame): Backend.Server < Async =
        throw new UnsupportedOperationException("HTTP server is not supported on Scala Native platform")

end CurlBackend
