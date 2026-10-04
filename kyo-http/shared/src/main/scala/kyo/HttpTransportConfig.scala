package kyo

/** Low-level transport tuning for the byte transport underlying both client and server.
  *
  * All parameters have production-ready defaults. Override only when profiling reveals a bottleneck or when deploying on hardware with
  * unusual memory or CPU characteristics.
  *
  *   - `channelCapacity`, number of in-flight chunks that the inbound/outbound pump channels can buffer before backpressure kicks in.
  *     Increasing this trades memory for throughput on high-latency connections. Zero or less makes them rendezvous channels, which hand
  *     each chunk straight from producer to consumer.
  *   - `readChunkSize`, size of each read buffer allocated per connection. Larger values reduce system-call overhead on bulk transfers at
  *     the cost of higher per-connection memory usage. It has no effect on JS and Wasm, where Node sizes each socket read itself, at most
  *     64 KiB.
  *   - `maxHeaderSize`, hard limit on the size of a message head: the request or status line and the header fields, through the empty
  *     line that ends them. Body bytes that arrive in the same read do not count, on either side. Default 64 KiB. Enforced by kyo-http's
  *     HTTP/1.1 parser (server dispatch and client connection), not by the underlying byte transport. The server answers a request whose
  *     head exceeds it with 431 Request Header Fields Too Large, or 414 URI Too Long when the request line alone exceeds it, with
  *     `Connection: close`, and closes the connection. The client fails a response whose head has no empty line within the first
  *     `maxHeaderSize` bytes with `HttpProtocolException`.
  *   - `handshakeTimeout`, deadline for a TLS handshake to complete. A peer that finishes the TCP phase but then stalls the handshake
  *     (sends nothing, or a partial ClientHello, and never finishes) would otherwise pin the connection indefinitely (a slowloris
  *     handshake-stall denial of service, CWE-400). When finite, the connection is reaped at the deadline, so zero fails every handshake at
  *     once. This value reaches both roles: a server's accepted handshakes and a client's `connectTls`. Defaults to `Duration.Infinity`
  *     (off); read/write/idle deadlines stay caller-composable via `Async.timeout`. The client's TCP connect deadline is a separate knob,
  *     `HttpClientConfig.connectTimeout`.
  *
  * `readChunkSize` and `maxHeaderSize` are each a [[kyo.ByteSize]] and are narrowed where they are used, as kyo-core's stream reads narrow
  * theirs: zero becomes one byte and a size beyond `Int.MaxValue` becomes `Int.MaxValue`, so no byte size is refused.
  *
  * @see
  *   [[kyo.HttpServerConfig]] Accepts an `HttpTransportConfig` via the `transportConfig` field
  * @see
  *   [[kyo.HttpClient.init]] Accepts an `HttpTransportConfig` (a construction-time setting for the pooled client)
  */
case class HttpTransportConfig(
    channelCapacity: Int,
    readChunkSize: ByteSize,
    maxHeaderSize: ByteSize,
    handshakeTimeout: Duration = Duration.Infinity
) derives CanEqual:
    def channelCapacity(v: Int): HttpTransportConfig       = copy(channelCapacity = v)
    def readChunkSize(v: ByteSize): HttpTransportConfig    = copy(readChunkSize = v)
    def maxHeaderSize(v: ByteSize): HttpTransportConfig    = copy(maxHeaderSize = v)
    def handshakeTimeout(v: Duration): HttpTransportConfig = copy(handshakeTimeout = v)
end HttpTransportConfig

object HttpTransportConfig:
    val default: HttpTransportConfig = HttpTransportConfig(
        channelCapacity = 4,
        readChunkSize = 8.kib,
        maxHeaderSize = 64.kib,
        handshakeTimeout = Duration.Infinity
    )
end HttpTransportConfig
