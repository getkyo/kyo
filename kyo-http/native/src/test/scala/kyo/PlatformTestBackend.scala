package kyo

/** Native tests use in-memory TestBackend (no server support in CurlBackend). */
object PlatformTestBackend:
    val backend: Backend = internal.TestBackend
end PlatformTestBackend
