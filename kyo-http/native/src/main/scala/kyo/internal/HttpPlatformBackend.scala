package kyo.internal

import kyo.HttpBackend

private[kyo] object HttpPlatformBackend:
    lazy val client: HttpBackend.Client = new CurlClientBackend(daemon = true)
    lazy val server: HttpBackend.Server = new H2oServerBackend
end HttpPlatformBackend
