package kyo

/** Low-level transport tuning for the NIO pump-and-parser pipeline used by both client and server.
  *
  * All parameters have production-ready defaults. Override only when profiling reveals a bottleneck or when deploying on hardware with
  * unusual memory or CPU characteristics.
  *
  *   - `channelCapacity`, number of in-flight chunks that the inbound/outbound pump channels can buffer before backpressure kicks in.
  *     Increasing this trades memory for throughput on high-latency connections.
  *   - `readChunkSize`, size of each read buffer allocated per connection (bytes). Larger values reduce system-call overhead on bulk
  *     transfers at the cost of higher per-connection memory usage.
  *   - `maxHeaderSize`, hard limit on the total byte size of HTTP headers. Requests or responses exceeding this limit are rejected with a
  *     protocol error. Default 65536 (64 KiB). Enforced by kyo-http's HTTP/1.1 parser (server dispatch and client connection), not by the
  *     underlying byte transport.
  *   - `ioPoolSize`, sizes the io_uring submission-queue depth on the Linux io_uring backend, where the depth is `max(256, ioPoolSize * 64)`.
  *     It has no effect on the epoll, kqueue, NIO, or Node backends, and does NOT set a thread count: every backend runs a single I/O
  *     event-loop driver per transport. Defaults to `max(1, cores / 2)`.
  *   - `handshakeTimeout`, deadline for a server-side accept TLS handshake to complete. A client that finishes the TCP accept but then
  *     stalls the TLS handshake (sends nothing, or a partial ClientHello, and never finishes) would otherwise pin the accepted connection
  *     indefinitely (a slowloris handshake-stall denial of service, CWE-400). When finite, the server reaps such a connection at the deadline.
  *     Defaults to `Duration.Infinity` (off), preserving the original behavior; read/write/idle deadlines stay caller-composable via
  *     `Async.timeout`. Applies to the server only (the client's connect+handshake deadline is `HttpClientConfig.connectTimeout`).
  *
  * @see
  *   [[kyo.HttpServerConfig]] Accepts an `HttpTransportConfig` via the `transportConfig` field
  * @see
  *   [[kyo.HttpClient.init]] Accepts an `HttpTransportConfig` (a construction-time setting for the pooled client)
  */
case class HttpTransportConfig(
    channelCapacity: Int,
    readChunkSize: Int,
    maxHeaderSize: Int,
    ioPoolSize: Int,
    handshakeTimeout: Duration = Duration.Infinity
) derives CanEqual:
    def channelCapacity(v: Int): HttpTransportConfig       = copy(channelCapacity = v)
    def readChunkSize(v: Int): HttpTransportConfig         = copy(readChunkSize = v)
    def maxHeaderSize(v: Int): HttpTransportConfig         = copy(maxHeaderSize = v)
    def ioPoolSize(v: Int): HttpTransportConfig            = copy(ioPoolSize = v)
    def handshakeTimeout(v: Duration): HttpTransportConfig = copy(handshakeTimeout = v)
end HttpTransportConfig

object HttpTransportConfig:
    val default: HttpTransportConfig = HttpTransportConfig(
        channelCapacity = 4,
        readChunkSize = 8192,
        maxHeaderSize = 65536,
        ioPoolSize = Math.max(1, Runtime.getRuntime.availableProcessors() / 2),
        handshakeTimeout = Duration.Infinity
    )
end HttpTransportConfig
