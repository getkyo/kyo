package kyo

/** JS tests use in-memory TestBackend (no server support in FetchBackend). */
object PlatformTestBackend:
    val backend: Backend = internal.TestBackend
end PlatformTestBackend
