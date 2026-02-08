package kyo

/** JVM tests use real Netty backend. */
object PlatformTestBackend:
    val backend: Backend = PlatformBackend.default
end PlatformTestBackend
