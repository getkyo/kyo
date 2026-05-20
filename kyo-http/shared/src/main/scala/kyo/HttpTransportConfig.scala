package kyo

/** Low-level transport tuning for the NIO pump-and-parser pipeline used by both client and server.
  *
  * All parameters have production-ready defaults. Override only when profiling reveals a bottleneck or when deploying on hardware with
  * unusual memory or CPU characteristics.
  *
  *   - `channelCapacity` — number of in-flight chunks that the inbound/outbound pump channels can buffer before backpressure kicks in.
  *     Increasing this trades memory for throughput on high-latency connections.
  *   - `readChunkSize` — size of each read buffer allocated per connection (bytes). Larger values reduce system-call overhead on bulk
  *     transfers at the cost of higher per-connection memory usage.
  *   - `writeBatchSize` — maximum number of `Span[Byte]` chunks gathered into a single write call. Higher values reduce write system calls
  *     at the cost of slightly increased latency on small messages.
  *   - `maxHeaderSize` — hard limit on the total byte size of HTTP headers. Requests or responses exceeding this limit are rejected with a
  *     protocol error. Default 65536 (64 KiB).
  *   - `ioPoolSize` — number of OS threads dedicated to I/O event loops. Defaults to `max(1, cores / 2)`. Setting this higher than the
  *     number of physical cores rarely helps.
  *
  * @see
  *   [[kyo.HttpServerConfig]] Accepts an `HttpTransportConfig` via the `transportConfig` field
  * @see
  *   [[kyo.HttpClientConfig]] Accepts an `HttpTransportConfig` via the `transportConfig` field
  */
case class HttpTransportConfig(
    channelCapacity: Int,
    readChunkSize: Int,
    writeBatchSize: Int,
    maxHeaderSize: Int,
    ioPoolSize: Int
) derives CanEqual:
    def channelCapacity(v: Int): HttpTransportConfig = copy(channelCapacity = v)
    def readChunkSize(v: Int): HttpTransportConfig   = copy(readChunkSize = v)
    def writeBatchSize(v: Int): HttpTransportConfig  = copy(writeBatchSize = v)
    def maxHeaderSize(v: Int): HttpTransportConfig   = copy(maxHeaderSize = v)
    def ioPoolSize(v: Int): HttpTransportConfig      = copy(ioPoolSize = v)
end HttpTransportConfig

object HttpTransportConfig:
    val default: HttpTransportConfig = HttpTransportConfig(
        channelCapacity = 4,
        readChunkSize = 8192,
        writeBatchSize = 16,
        maxHeaderSize = 65536,
        ioPoolSize = Math.max(1, Runtime.getRuntime.availableProcessors() / 2)
    )
end HttpTransportConfig
