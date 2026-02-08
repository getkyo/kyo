package kyo

/** JS platform default backend using the Fetch API. */
object HttpPlatformBackend:
    val default: Backend = FetchBackend
end HttpPlatformBackend
