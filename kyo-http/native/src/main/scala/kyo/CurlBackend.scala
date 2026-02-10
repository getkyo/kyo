package kyo

import kyo.internal.CurlBindings
import kyo.internal.CurlEventLoop

/** Native client backend using libcurl for HTTP transport. */
object CurlBackend extends Backend.Client:

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

end CurlBackend
