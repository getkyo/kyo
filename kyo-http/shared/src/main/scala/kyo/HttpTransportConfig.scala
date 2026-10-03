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
  *     a server's accepted handshakes and a client's `connectTls`. Defaults to `HandshakeTimeout.unlimited` (off); read/write/idle
  *     deadlines stay caller-composable via `Async.timeout`. The client's TCP connect deadline is a separate knob,
  *     `HttpClientConfig.connectTimeout`. It is a [[HttpTransportConfig.HandshakeTimeout]], so neither the constructor nor `copy` can hold
  *     a zero deadline; the setter taking a raw `Duration` returns a `Result` that fails with an [[kyo.HttpConfigException]].
  *
  * `channelCapacity`, `readChunkSize` and `maxHeaderSize` are each a [[HttpTransportConfig.Size]]: one or more. Zero or less is no usable
  * transport, since the byte transport refuses the first two and the parser would reject every header. `Size(8)` checks a literal at compile
  * time, and the setters taking a raw `Int` return a `Result` that fails with an [[kyo.HttpConfigException]] naming the setting.
  *
  * @see
  *   [[kyo.HttpServerConfig]] Accepts an `HttpTransportConfig` via the `transportConfig` field
  * @see
  *   [[kyo.HttpClient.init]] Accepts an `HttpTransportConfig` (a construction-time setting for the pooled client)
  */
case class HttpTransportConfig(
    channelCapacity: HttpTransportConfig.Size,
    readChunkSize: HttpTransportConfig.Size,
    maxHeaderSize: HttpTransportConfig.Size,
    handshakeTimeout: HttpTransportConfig.HandshakeTimeout = HttpTransportConfig.HandshakeTimeout.unlimited
) derives CanEqual:
    def channelCapacity(v: HttpTransportConfig.Size): HttpTransportConfig = copy(channelCapacity = v)
    def readChunkSize(v: HttpTransportConfig.Size): HttpTransportConfig   = copy(readChunkSize = v)
    def maxHeaderSize(v: HttpTransportConfig.Size): HttpTransportConfig   = copy(maxHeaderSize = v)

    /** This config with `v` as its channel capacity, or the [[kyo.HttpConfigException]] refusing a value below one. */
    def channelCapacity(v: Int)(using Frame): Result[HttpConfigException, HttpTransportConfig] =
        HttpTransportConfig.Size.check("channelCapacity", v).map(channelCapacity)

    /** This config with `v` as its read chunk size, or the [[kyo.HttpConfigException]] refusing a value below one. */
    def readChunkSize(v: Int)(using Frame): Result[HttpConfigException, HttpTransportConfig] =
        HttpTransportConfig.Size.check("readChunkSize", v).map(readChunkSize)

    /** This config with `v` as its header limit, or the [[kyo.HttpConfigException]] refusing a value below one. */
    def maxHeaderSize(v: Int)(using Frame): Result[HttpConfigException, HttpTransportConfig] =
        HttpTransportConfig.Size.check("maxHeaderSize", v).map(maxHeaderSize)

    def handshakeTimeout(limit: HttpTransportConfig.HandshakeTimeout): HttpTransportConfig = copy(handshakeTimeout = limit)

    /** This config with `d` as its handshake deadline, or the [[kyo.HttpConfigException]] refusing a zero duration. */
    def handshakeTimeout(d: Duration)(using Frame): Result[HttpConfigException, HttpTransportConfig] =
        HttpTransportConfig.HandshakeTimeout.init(d).map(handshakeTimeout)
end HttpTransportConfig

object HttpTransportConfig:
    val default: HttpTransportConfig = HttpTransportConfig(
        channelCapacity = Size(4),
        readChunkSize = Size(8192),
        maxHeaderSize = Size(65536),
        handshakeTimeout = HandshakeTimeout.unlimited
    )

    /** A count or byte size the transport needs at least one of: a channel capacity, a read chunk size, or a header limit. */
    opaque type Size = Int

    object Size:
        /** The size for a literal `n`, checked at compile time: a literal below one, or an argument that is not a constant, does not
          * compile. A value known only at runtime goes through [[init]].
          */
        inline def apply(inline n: Int): Size =
            inline if n < 1 then compiletime.error("HttpTransportConfig.Size must be one or more")
            else n

        /** `n` as a size, or the [[kyo.HttpConfigException]] refusing a value below one. */
        def init(n: Int)(using Frame): Result[HttpConfigException, Size] = check("size", n)

        private[kyo] def check(setting: String, n: Int)(using Frame): Result[HttpConfigException, Size] =
            if n >= 1 then Result.succeed(n)
            else Result.fail(HttpConfigException(setting, n.toString, "one or more"))

        given CanEqual[Size, Size] = CanEqual.derived

        extension (self: Size) def value: Int = self
    end Size

    /** A TLS handshake deadline: positive, or `Duration.Infinity` for none. Zero would reap every handshake before it starts, so it is
      * refused rather than held.
      */
    opaque type HandshakeTimeout = Duration

    object HandshakeTimeout:
        val unlimited: HandshakeTimeout = Duration.Infinity

        /** `d` as a handshake deadline, or the [[kyo.HttpConfigException]] refusing a zero duration. */
        def init(d: Duration)(using Frame): Result[HttpConfigException, HandshakeTimeout] =
            if d > Duration.Zero then Result.succeed(d)
            else Result.fail(HttpConfigException("handshakeTimeout", d.show, "positive, or Duration.Infinity for no deadline"))

        given CanEqual[HandshakeTimeout, HandshakeTimeout] = CanEqual.derived

        extension (self: HandshakeTimeout) def duration: Duration = self
    end HandshakeTimeout
end HttpTransportConfig
