package kyo

/** JVM platform backends — Netty for both client and server. */
object HttpPlatformBackend:
    val client: Backend.Client = NettyClientBackend
    val server: Backend.Server = NettyServerBackend()
end HttpPlatformBackend
