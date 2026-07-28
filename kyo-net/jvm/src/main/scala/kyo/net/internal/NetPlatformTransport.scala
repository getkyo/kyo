package kyo.net.internal

/** JVM platform bootstrap. Lazily builds the production transport through the capability-probed backend registry: on a posix host this is the
  * unified `PosixTransport` over io_uring/epoll/kqueue, falling back to the pure-JDK `NioTransport` floor when no posix syscall is available or
  * when `-Dkyo.net.backend=nio` forces it. The bootstrap body is the shared [[NetPlatformTransportBase]]; this object adds only the JVM identity.
  */
private[kyo] object NetPlatformTransport extends NetPlatformTransportBase
