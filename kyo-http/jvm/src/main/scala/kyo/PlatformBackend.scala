package kyo

/** JVM platform default backend — uses Netty for HTTP I/O. */
object PlatformBackend:
    val default: Backend = NettyBackend
end PlatformBackend
