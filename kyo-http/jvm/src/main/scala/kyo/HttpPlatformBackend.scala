package kyo

/** JVM platform default backend — uses Netty for HTTP I/O. */
object HttpPlatformBackend:
    val default: Backend = NettyBackend
