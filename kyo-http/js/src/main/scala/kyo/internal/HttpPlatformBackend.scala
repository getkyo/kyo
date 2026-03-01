package kyo.internal

import kyo.HttpBackend

private[kyo] object HttpPlatformBackend:
    lazy val client: HttpBackend.Client = new FetchClientBackend
    lazy val server: HttpBackend.Server = new NodeServerBackend
end HttpPlatformBackend
