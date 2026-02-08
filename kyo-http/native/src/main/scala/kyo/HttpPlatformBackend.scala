package kyo

/** Native platform default backend using libcurl. */
object HttpPlatformBackend:
    val default: Backend = CurlBackend
end HttpPlatformBackend
