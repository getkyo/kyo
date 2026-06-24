package kyo.net

import kyo.Absent
import kyo.Duration
import kyo.Maybe

/** Low-level transport tuning for the NIO pump-and-parser pipeline used by both client and server.
  *
  * All parameters have production-ready defaults. Override only when profiling reveals a bottleneck or when deploying on hardware with
  * unusual memory or CPU characteristics.
  *
  *   - `channelCapacity`: number of in-flight chunks that the inbound/outbound pump channels can buffer before backpressure kicks in.
  *     Increasing this trades memory for throughput on high-latency connections.
  *   - `readChunkSize`: size of each read buffer allocated per connection (bytes). Larger values reduce system-call overhead on bulk
  *     transfers at the cost of higher per-connection memory usage.
  *   - `ioPoolSize`: number of independent I/O driver instances the transport builds at startup. Each driver owns its own poller or io_uring
  *     ring fd plus one carrier fiber; new connections are distributed round-robin across the pool so concurrent workloads can use multiple
  *     event loops. On the io_uring backend the ring submission-queue depth is `max(256, ioPoolSize * 64)`. On the NIO and Node backends the
  *     pool is compiled in but the extra drivers are no-ops (NIO has its own threading model; Node is single-threaded). Defaults to
  *     `max(1, cores / 2)`.
  *   - `soRcvBuf`: when `Present(n)`, sets `SO_RCVBUF` on each socket fd to `n` bytes via `setsockopt`. `Absent` (the default) issues no
  *     `setsockopt` and leaves the kernel's default buffer size unchanged. Applied to both client connect fds and listen accept fds.
  *   - `soSndBuf`: same as `soRcvBuf` for `SO_SNDBUF` (the send buffer). `Absent` (the default) leaves the kernel default unchanged.
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
    handshakeTimeout: Duration,
    soRcvBuf: Maybe[Int] = Absent,
    soSndBuf: Maybe[Int] = Absent
) derives CanEqual
end TransportConfig

object TransportConfig:
    val default: TransportConfig = TransportConfig(
        channelCapacity = 4,
        readChunkSize = 8192,
        ioPoolSize = Math.max(1, Runtime.getRuntime.availableProcessors() / 2),
        handshakeTimeout = Duration.Infinity,
        soRcvBuf = Absent,
        soSndBuf = Absent
    )
end TransportConfig
