package kyo

/** Native tests use in-memory TestBackend (no server support in CurlBackend). */
object PlatformTestBackend:
    val client: Backend.Client = internal.TestBackend
    val server: Backend.Server = internal.TestBackend
end PlatformTestBackend
