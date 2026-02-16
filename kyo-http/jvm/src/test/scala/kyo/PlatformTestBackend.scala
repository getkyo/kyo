package kyo

/** JVM tests use real Netty backend. */
object PlatformTestBackend:
    val client: Backend.Client = NettyClientBackend
    val server: Backend.Server = NettyServerBackend
end PlatformTestBackend
