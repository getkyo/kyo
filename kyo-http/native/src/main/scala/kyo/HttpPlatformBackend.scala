package kyo

/** Native platform backends — libcurl for client, no server support. */
object HttpPlatformBackend:
    val client: Backend.Client = CurlBackend
    def server: Backend.Server = throw new UnsupportedOperationException("HTTP server is not supported on Scala Native platform")
end HttpPlatformBackend
