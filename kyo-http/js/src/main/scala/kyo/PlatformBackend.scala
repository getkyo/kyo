package kyo

/** JS platform default backend using the Fetch API. */
object PlatformBackend:
    val default: Backend = FetchBackend
end PlatformBackend
