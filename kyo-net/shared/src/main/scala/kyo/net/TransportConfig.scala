package kyo.net

import kyo.Duration

/** Low-level transport tuning for the NIO pump-and-parser pipeline used by both client and server.
  *
  * All parameters have production-ready defaults. Override only when profiling reveals a bottleneck or when deploying on hardware with
  * unusual memory or CPU characteristics.
  *
  *   - `channelCapacity`: number of in-flight chunks that the inbound/outbound pump channels can buffer before backpressure kicks in.
  *     Increasing this trades memory for throughput on high-latency connections.
  *   - `readChunkSize`: size of each read buffer allocated per connection (bytes). Larger values reduce system-call overhead on bulk
  *     transfers at the cost of higher per-connection memory usage.
  *   - `ioPoolSize`: sizes the io_uring submission-queue depth on the Linux io_uring backend, where the depth is `max(256, ioPoolSize * 64)`.
  *     It has no effect on the epoll, kqueue, NIO, or Node backends. It does NOT set a thread count: every backend runs a single I/O
  *     event-loop driver per transport (the driver fibers run on the shared Kyo scheduler). Defaults to `max(1, cores / 2)`.
  *   - `handshakeTimeout`: deadline for a server-side accept TLS handshake to complete. A client that finishes the TCP accept and then
  *     stalls the TLS handshake (sends nothing, or a partial ClientHello, and never finishes) would otherwise pin the accepted fd, the TLS
  *     engine, and the per-connection buffers indefinitely (a slowloris handshake-stall denial of service, CWE-400). When this is finite, the
  *     transport arms a `Clock`-driven deadline as each accepted connection begins its handshake and reaps the connection (the same fd +
  *     engine teardown a failed handshake already runs) if the handshake has not completed by the deadline. The default `Duration.Infinity`
  *     arms no timer and preserves the original behavior: read, write, idle, and client-side connect/handshake deadlines stay caller-composable
  *     via `Async.timeout`, which is the only path a caller cannot self-serve being the server accept handshake addressed here.
  */
case class TransportConfig(
    channelCapacity: Int,
    readChunkSize: Int,
    ioPoolSize: Int,
    handshakeTimeout: Duration
) derives CanEqual
end TransportConfig

object TransportConfig:
    val default: TransportConfig = TransportConfig(
        channelCapacity = 4,
        readChunkSize = 8192,
        ioPoolSize = Math.max(1, Runtime.getRuntime.availableProcessors() / 2),
        handshakeTimeout = Duration.Infinity
    )
end TransportConfig
