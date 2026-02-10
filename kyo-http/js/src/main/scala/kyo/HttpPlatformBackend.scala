package kyo

/** JS platform backends — Fetch API for client, Node.js http for server. */
object HttpPlatformBackend:
    val client: Backend.Client = FetchClientBackend
    val server: Backend.Server = NodeServerBackend
end HttpPlatformBackend
