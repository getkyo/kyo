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
  *   - `connectTimeout`, deadline for a client TCP connect to complete. When finite, the transport arms a `Clock`-driven deadline as the
  *     connect is issued and fails the connect with `NetConnectTimeoutException` if the OS does not deliver a connect outcome (connected or
  *     refused) within the deadline. Bounds the client-side connect independently of the server accept handshake. Defaults to `30.seconds`.
  *     Set to `Duration.Infinity` to use the OS TCP timeout instead.
  *   - `handshakeTimeout`, deadline for a server-side accept TLS handshake to complete. A client that finishes the TCP accept but then
  *     stalls the TLS handshake (sends nothing, or a partial ClientHello, and never finishes) would otherwise pin the accepted connection
  *     indefinitely (a slowloris handshake-stall denial of service, CWE-400). When finite, the server reaps such a connection at the deadline.
  *     Defaults to `Duration.Infinity` (off); read/write/idle deadlines stay caller-composable via
  *     `Async.timeout`. Applies to the server only (the client's connect deadline is `connectTimeout`; combined connect+TLS deadline is
  *     `HttpClientConfig.connectTimeout`).
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
    connectTimeout: Duration = 30.seconds,
    handshakeTimeout: Duration = Duration.Infinity
) derives CanEqual:
    require(
        connectTimeout > Duration.Zero || connectTimeout == Duration.Infinity,
        s"connectTimeout must be positive or Infinity: $connectTimeout"
    )
    def channelCapacity(v: Int): HttpTransportConfig       = copy(channelCapacity = v)
    def readChunkSize(v: Int): HttpTransportConfig         = copy(readChunkSize = v)
    def maxHeaderSize(v: Int): HttpTransportConfig         = copy(maxHeaderSize = v)
    def connectTimeout(v: Duration): HttpTransportConfig   = copy(connectTimeout = v)
    def handshakeTimeout(v: Duration): HttpTransportConfig = copy(handshakeTimeout = v)
end HttpTransportConfig

object HttpTransportConfig:
    val default: HttpTransportConfig = HttpTransportConfig(
        channelCapacity = 4,
        readChunkSize = 8192,
        maxHeaderSize = 65536,
        connectTimeout = 30.seconds,
        handshakeTimeout = Duration.Infinity
    )
end HttpTransportConfig
