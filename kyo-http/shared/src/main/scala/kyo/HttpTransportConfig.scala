package kyo

/** Low-level transport tuning for the byte transport underlying both client and server.
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
  *   - `handshakeTimeout`, deadline for a TLS handshake to complete. A peer that finishes the TCP phase but then stalls the handshake
  *     (sends nothing, or a partial ClientHello, and never finishes) would otherwise pin the connection indefinitely (a slowloris
  *     handshake-stall denial of service, CWE-400). When finite, the connection is reaped at the deadline. This value reaches both roles:
  *     a server's accepted handshakes and a client's `connectTls`. Defaults to `Duration.Infinity` (off); read/write/idle deadlines stay
  *     caller-composable via `Async.timeout`. The client's TCP connect deadline is a separate knob, `HttpClientConfig.connectTimeout`.
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
    handshakeTimeout: Duration = Duration.Infinity
) derives CanEqual:
    require(
        handshakeTimeout > Duration.Zero || handshakeTimeout == Duration.Infinity,
        s"handshakeTimeout must be positive or Infinity: $handshakeTimeout"
    )
    def channelCapacity(v: Int): HttpTransportConfig       = copy(channelCapacity = v)
    def readChunkSize(v: Int): HttpTransportConfig         = copy(readChunkSize = v)
    def maxHeaderSize(v: Int): HttpTransportConfig         = copy(maxHeaderSize = v)
    def handshakeTimeout(v: Duration): HttpTransportConfig = copy(handshakeTimeout = v)
end HttpTransportConfig

object HttpTransportConfig:
    val default: HttpTransportConfig = HttpTransportConfig(
        channelCapacity = 4,
        readChunkSize = 8192,
        maxHeaderSize = 65536,
        handshakeTimeout = Duration.Infinity
    )
end HttpTransportConfig
