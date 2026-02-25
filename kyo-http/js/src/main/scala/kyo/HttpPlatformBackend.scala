package kyo

/** JS platform backends — old kyo package, stubbed out. Use kyo.http2 instead. */
object HttpPlatformBackend:
    def client: Backend.Client =
        throw new UnsupportedOperationException("Old kyo.HttpClient is not supported on JS. Use kyo.http2.HttpClient instead.")
    def server: Backend.Server =
        throw new UnsupportedOperationException("Old kyo.HttpServer is not supported on JS. Use kyo.http2.HttpServer instead.")
end HttpPlatformBackend
