package kyo.net.internal

/** Native platform bootstrap. Lazily builds the production transport through the capability-probed backend registry: on a posix host this is
  * the unified `PosixTransport` over io_uring/epoll/kqueue, selected by the OS-gated probes (and forceable via `-Dkyo.net.backend`). The
  * bootstrap body is the shared [[NetPlatformTransportBase]]; this object adds only the Native identity.
  */
private[kyo] object NetPlatformTransport extends NetPlatformTransportBase
