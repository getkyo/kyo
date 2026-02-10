package kyo

/** JS tests use Fetch client and Node.js HTTP server over localhost. */
object PlatformTestBackend:
    val client: Backend.Client = FetchClientBackend
    val server: Backend.Server = NodeServerBackend
end PlatformTestBackend
